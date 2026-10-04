package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ProjectApiService;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.Diagnostic;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlOperation;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.undo.UndoCaptureService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantApplyServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_ID = UUID.fromString("15000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID CLASSIFIER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID UNKNOWN_ID = UUID.fromString("29000000-0000-0000-0000-000000000009");

    private final ProjectApiService projectApiService = mock(ProjectApiService.class);
    private final ProjectAuthorizationService authorizationService = mock(ProjectAuthorizationService.class);
    private final CommandService commandService = mock(CommandService.class);
    private final UmlAttributeRepository attributeRepository = mock(UmlAttributeRepository.class);
    private final UmlOperationRepository operationRepository = mock(UmlOperationRepository.class);
    private final OperationParameterRepository parameterRepository = mock(OperationParameterRepository.class);
    private final UmlConstraintRepository constraintRepository = mock(UmlConstraintRepository.class);
    private final RelationshipEndRepository relationshipEndRepository = mock(RelationshipEndRepository.class);
    private final UndoCaptureService undoCaptureService = mock(UndoCaptureService.class);
    private final CurrentUserContext currentUserContext = new CurrentUserContext();

    @BeforeEach
    void setUp() {
        currentUserContext.set(new CurrentUser(USER_ID, "owner", "Owner"));
    }

    @AfterEach
    void tearDown() {
        currentUserContext.clear();
    }

    @Test
    void appliesAPlanStepByStepReReadingTheRevisionAndStoppingAtNothing() {
        UUID createdClassifierId = UUID.randomUUID();
        ModelResource before = model(7L);
        ModelResource after = model(8L, new ClassifierResource(createdClassifierId, "Order", ClassifierKind.CLASS,
                false, null, List.of(), List.of(), List.of()));
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(before, after);
        when(commandService.execute(any())).thenReturn(
                accepted(8L),
                accepted(9L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.create", null, Map.of("name", "Order")),
                new AssistantApplyOp("attribute.add", createdClassifierId,
                        Map.of("name", "number", "type", "String"))), 7L));

        verify(authorizationService).requireEditor(PROJECT_ID);
        ArgumentCaptor<OperationEnvelope> envelopes = ArgumentCaptor.forClass(OperationEnvelope.class);
        verify(commandService, times(2)).execute(envelopes.capture());
        OperationEnvelope create = envelopes.getAllValues().get(0);
        assertThat(create.type()).isEqualTo("classifier.create");
        assertThat(create.origin()).isEqualTo(CommandOrigin.AI);
        assertThat(create.author().userId()).isEqualTo(USER_ID);
        assertThat(create.projectId()).isEqualTo(PROJECT_ID);
        assertThat(create.modelId()).isEqualTo(MODEL_ID);
        assertThat(create.baseRevision()).isEqualTo(7L);
        assertThat(create.targets()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> createPayload = (Map<String, Object>) create.payload();
        assertThat(createPayload.keySet()).isEqualTo(java.util.Set.of("id", "name", "kind"));
        assertThat(createPayload).containsEntry("name", "Order").containsEntry("kind", "CLASS");
        assertThat(createPayload.get("id")).isInstanceOf(UUID.class);
        OperationEnvelope attribute = envelopes.getAllValues().get(1);
        assertThat(attribute.baseRevision()).isEqualTo(8L);
        assertThat(attribute.targets()).singleElement().satisfies(target -> {
            assertThat(target.scope()).isEqualTo("classifier");
            assertThat(target.id()).isEqualTo(createdClassifierId);
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> attributePayload = (Map<String, Object>) attribute.payload();
        assertThat(attributePayload.keySet())
                .isEqualTo(java.util.Set.of("id", "name", "type", "visibility"));
        assertThat(attributePayload).containsEntry("name", "number")
                .containsEntry("type", Map.of("primitiveName", "String"))
                .containsEntry("visibility", "PUBLIC");
        assertThat(attributePayload.get("id")).isInstanceOf(UUID.class);

        assertThat(response.steps()).hasSize(2);
        assertThat(response.steps().get(0).status()).isEqualTo("ACCEPTED");
        assertThat(response.steps().get(0).type()).isEqualTo("classifier.create");
        assertThat(response.steps().get(0).resultRevision()).isEqualTo(8L);
        assertThat(response.steps().get(1).status()).isEqualTo("ACCEPTED");
        assertThat(response.steps().get(1).targetId()).isEqualTo(createdClassifierId);
        assertThat(response.finalRevision()).isEqualTo(9L);

        // IA4-04: every accepted AI step is captured for undo with the session user.
        verify(undoCaptureService, times(2)).snapshotTargets(any());
        verify(undoCaptureService, times(2)).recordAccepted(any(), any(), eq(USER_ID), any());
    }

    @Test
    void staleBaseRevisionRejectsWithReviewRequiredAndExecutesNothing() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.create", null, Map.of("name", "Order")),
                new AssistantApplyOp("attribute.add", CLASSIFIER_ID, Map.of("name", "n", "type", "String"))), 6L));

        verifyNoInteractions(commandService);
        assertThat(response.steps()).hasSize(1);
        assertThat(response.steps().get(0).status()).isEqualTo("REJECTED");
        assertThat(response.steps().get(0).diagnostics())
                .extracting(AssistantPlanResponse.Diagnostic::code)
                .containsExactly("REVIEW_REQUIRED");
        assertThat(response.finalRevision()).isEqualTo(7L);
    }

    @Test
    void unknownTargetMidPlanStopsTheSequenceAndSkipsTheRemainingSteps() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        when(commandService.execute(any())).thenReturn(accepted(8L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.rename", CLASSIFIER_ID, Map.of("newName", "Humana")),
                new AssistantApplyOp("attribute.add", UNKNOWN_ID, Map.of("name", "n", "type", "String")),
                new AssistantApplyOp("classifier.setAbstract", CLASSIFIER_ID, Map.of("value", true))), 7L));

        verify(commandService, times(1)).execute(any());
        assertThat(response.steps()).hasSize(3);
        assertThat(response.steps().get(0).status()).isEqualTo("ACCEPTED");
        assertThat(response.steps().get(1).status()).isEqualTo("REJECTED");
        assertThat(response.steps().get(1).diagnostics())
                .extracting(AssistantPlanResponse.Diagnostic::code)
                .containsExactly("UNKNOWN_TARGET");
        assertThat(response.steps().get(2).status()).isEqualTo("SKIPPED");
        assertThat(response.steps().get(2).type()).isEqualTo("classifier.setAbstract");
        assertThat(response.finalRevision()).isEqualTo(8L);
    }

    @Test
    void missingRequiredArgumentRejectsTheStepWithoutExecutingAndSkipsTheRest() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.create", null, Map.of()),
                new AssistantApplyOp("classifier.rename", CLASSIFIER_ID, Map.of("newName", "Humana"))), 7L));

        verifyNoInteractions(commandService);
        assertThat(response.steps()).hasSize(2);
        assertThat(response.steps().get(0).status()).isEqualTo("REJECTED");
        assertThat(response.steps().get(0).diagnostics())
                .extracting(AssistantPlanResponse.Diagnostic::code)
                .containsExactly("MISSING_ARGUMENT");
        assertThat(response.steps().get(1).status()).isEqualTo("SKIPPED");
        assertThat(response.finalRevision()).isEqualTo(7L);
    }

    @Test
    void classifierDeleteCarriesTheComputedCascadeCountPrecondition() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        UmlAttribute attribute = mock(UmlAttribute.class);
        UmlOperation operation = mock(UmlOperation.class);
        when(operation.getId()).thenReturn(UUID.randomUUID());
        when(attributeRepository.findByClassifierId(CLASSIFIER_ID)).thenReturn(List.of(attribute));
        when(operationRepository.findByClassifierId(CLASSIFIER_ID)).thenReturn(List.of(operation));
        when(parameterRepository.findByOperationId(any())).thenReturn(List.of());
        when(constraintRepository.findByModelId(MODEL_ID)).thenReturn(List.of());
        when(relationshipEndRepository.findByClassifierId(CLASSIFIER_ID)).thenReturn(List.of());
        when(commandService.execute(any())).thenReturn(accepted(8L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.delete", CLASSIFIER_ID, Map.of())), 7L));

        ArgumentCaptor<OperationEnvelope> envelopes = ArgumentCaptor.forClass(OperationEnvelope.class);
        verify(commandService).execute(envelopes.capture());
        OperationEnvelope envelope = envelopes.getValue();
        assertThat(envelope.targets()).singleElement().satisfies(target -> {
            assertThat(target.scope()).isEqualTo("classifier");
            assertThat(target.id()).isEqualTo(CLASSIFIER_ID);
        });
        assertThat(envelope.preconditions()).singleElement().satisfies(precondition -> {
            assertThat(precondition.kind()).isEqualTo("cascade_count");
            assertThat(precondition.expect()).isEqualTo(3);
        });
        assertThat(response.steps().get(0).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void commandRejectionIsMappedToAStepDiagnosticAndStopsTheSequence() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        when(commandService.execute(any())).thenReturn(new CommandResult(UUID.randomUUID(),
                CommandStatus.REJECTED, null,
                List.of(new Diagnostic(ErrorCode.REVISION_MISMATCH, "revision changed")), null));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.rename", CLASSIFIER_ID, Map.of("newName", "Humana")),
                new AssistantApplyOp("classifier.setAbstract", CLASSIFIER_ID, Map.of("value", true))), 7L));

        verify(commandService, times(1)).execute(any());
        verify(undoCaptureService, never()).recordAccepted(any(), any(), any(), any());
        assertThat(response.steps()).hasSize(2);
        assertThat(response.steps().get(0).status()).isEqualTo("REJECTED");
        assertThat(response.steps().get(0).diagnostics())
                .extracting(AssistantPlanResponse.Diagnostic::code)
                .containsExactly("REVISION_MISMATCH");
        assertThat(response.steps().get(1).status()).isEqualTo("SKIPPED");
    }

    @Test
    void emptyPlanIsAcceptedWithoutTouchingTheModel() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(), 7L));

        verifyNoInteractions(commandService);
        assertThat(response.steps()).isEmpty();
        assertThat(response.finalRevision()).isEqualTo(7L);
    }

    @Test
    void attributeAddReferencingAPlanLocalCreateResolvesByNameAtStepTime() {
        UUID createdClassifierId = UUID.randomUUID();
        ModelResource before = model(7L);
        ModelResource after = model(8L, new ClassifierResource(createdClassifierId, "Pedido",
                ClassifierKind.CLASS, false, null, List.of(), List.of(), List.of()));
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(before, after);
        when(commandService.execute(any())).thenReturn(accepted(8L), accepted(9L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.create", null, Map.of("name", "Pedido")),
                new AssistantApplyOp("attribute.add", null,
                        Map.of("name", "fecha", "type", "Date", "targetClassifierName", "Pedido"))), 7L));

        ArgumentCaptor<OperationEnvelope> envelopes = ArgumentCaptor.forClass(OperationEnvelope.class);
        verify(commandService, times(2)).execute(envelopes.capture());
        OperationEnvelope attribute = envelopes.getAllValues().get(1);
        assertThat(attribute.targets()).singleElement().satisfies(target -> {
            assertThat(target.scope()).isEqualTo("classifier");
            assertThat(target.id()).isEqualTo(createdClassifierId);
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> attributePayload = (Map<String, Object>) attribute.payload();
        assertThat(attributePayload).containsEntry("name", "fecha");
        assertThat(response.steps()).hasSize(2);
        assertThat(response.steps().get(0).status()).isEqualTo("ACCEPTED");
        assertThat(response.steps().get(1).status()).isEqualTo("ACCEPTED");
        assertThat(response.finalRevision()).isEqualTo(9L);
    }

    @Test
    void renameAcceptsTheDeclaredNewNameAndTheArgsNameFallbackAndRejectsEmptyArgs() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L), model(8L), model(9L));
        when(commandService.execute(any())).thenReturn(accepted(8L), accepted(9L));
        AssistantApplyService service = service();

        // Prompt v3 declares classifier.rename args {newName}; args.name remains a tolerated fallback.
        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.rename", CLASSIFIER_ID, Map.of("newName", "Humana")),
                new AssistantApplyOp("classifier.rename", CLASSIFIER_ID, Map.of("name", "HumanaFallback"))),
                7L));

        ArgumentCaptor<OperationEnvelope> envelopes = ArgumentCaptor.forClass(OperationEnvelope.class);
        verify(commandService, times(2)).execute(envelopes.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPayload = (Map<String, Object>) envelopes.getAllValues().get(0).payload();
        @SuppressWarnings("unchecked")
        Map<String, Object> secondPayload = (Map<String, Object>) envelopes.getAllValues().get(1).payload();
        assertThat(firstPayload).containsEntry("name", "Humana");
        assertThat(secondPayload).containsEntry("name", "HumanaFallback");
        assertThat(response.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("ACCEPTED"));
    }

    @Test
    void renameWithEmptyArgsStillRejectsWithMissingArgumentWithoutGuessing() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("classifier.rename", CLASSIFIER_ID, Map.of())), 7L));

        verifyNoInteractions(commandService);
        assertThat(response.steps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo("REJECTED");
            assertThat(step.diagnostics())
                    .extracting(AssistantPlanResponse.Diagnostic::code)
                    .containsExactly("MISSING_ARGUMENT");
        });
    }

    @Test
    void attributeAddDefaultsTheDeclaredStringTypeWhenArgsCarryNoType() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L), model(8L));
        when(commandService.execute(any())).thenReturn(accepted(8L));
        AssistantApplyService service = service();

        // The 2B model dropped the type ("fecha" with no type); prompt v3 declares the
        // "String" default and the apply side enforces the same declared default.
        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("attribute.add", CLASSIFIER_ID, Map.of("name", "fecha"))), 7L));

        ArgumentCaptor<OperationEnvelope> envelopes = ArgumentCaptor.forClass(OperationEnvelope.class);
        verify(commandService).execute(envelopes.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelopes.getValue().payload();
        assertThat(payload).containsEntry("name", "fecha")
                .containsEntry("type", Map.of("primitiveName", "String"))
                .containsEntry("visibility", "PUBLIC");
        assertThat(response.steps().get(0).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void attributeAddStillRequiresTheNameEvenWithTheDeclaredTypeDefault() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("attribute.add", CLASSIFIER_ID, Map.of("type", "Date"))), 7L));

        verifyNoInteractions(commandService);
        assertThat(response.steps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo("REJECTED");
            assertThat(step.diagnostics())
                    .extracting(AssistantPlanResponse.Diagnostic::code)
                    .containsExactly("MISSING_ARGUMENT");
        });
    }

    @Test
    void relationshipCreateDefaultsTheDeclaredAssociationKindWhenArgsCarryNoKind() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model(7L), model(8L));
        when(commandService.execute(any())).thenReturn(accepted(8L));
        AssistantApplyService service = service();

        AssistantApplyResponse response = service.apply(PROJECT_ID, new AssistantApplyRequest(List.of(
                new AssistantApplyOp("relationship.create", null, Map.of(
                        "source", Map.of("classifierName", "Persona"),
                        "target", Map.of("classifierName", "Persona")))), 7L));

        ArgumentCaptor<OperationEnvelope> envelopes = ArgumentCaptor.forClass(OperationEnvelope.class);
        verify(commandService).execute(envelopes.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelopes.getValue().payload();
        assertThat(payload).containsEntry("kind", "ASSOCIATION");
        assertThat(response.steps().get(0).status()).isEqualTo("ACCEPTED");
    }

    private AssistantApplyService service() {
        return new AssistantApplyService(projectApiService, authorizationService, currentUserContext, commandService,
                attributeRepository, operationRepository, parameterRepository, constraintRepository,
                relationshipEndRepository, undoCaptureService);
    }

    private static CommandResult accepted(long revision) {
        return new CommandResult(UUID.randomUUID(), CommandStatus.ACCEPTED, revision, List.of(), null);
    }

    private static ModelResource model(long revision) {
        return model(revision, new ClassifierResource(CLASSIFIER_ID, "Persona", ClassifierKind.CLASS, false, null,
                List.of(new AttributeResource(UUID.fromString("30000000-0000-0000-0000-000000000001"), "nombre",
                        "ATTRIBUTE", "PUBLIC", null, null, null, "String", 0)),
                List.of(), List.of()));
    }

    private static ModelResource model(long revision, ClassifierResource... classifiers) {
        return new ModelResource(MODEL_ID, PROJECT_ID, revision, UUID.randomUUID(), List.of(),
                List.of(classifiers), List.of());
    }
}
