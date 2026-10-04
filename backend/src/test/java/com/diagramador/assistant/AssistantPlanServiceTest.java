package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ProjectApiService;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.identity.ProjectAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantPlanServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CLASSIFIER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ATTRIBUTE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String UTTERANCE = "Renombrá Persona a Humana";

    private final ProjectApiService projectApiService = mock(ProjectApiService.class);
    private final ProjectAuthorizationService authorizationService = mock(ProjectAuthorizationService.class);
    private final AssistantPlanValidator validator = new AssistantPlanValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelResource model = model(7L);

    @BeforeEach
    void loadSnapshot() {
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model);
    }

    @Test
    void extractsFencedJsonAndBuildsTheFixtureContextShape() throws Exception {
        AiAssistantProvider provider = mock(AiAssistantProvider.class);
        when(provider.draft(anyString(), eq(UTTERANCE))).thenReturn("Texto previo\n```json\n"
                + "{\"intent\":\"plan\",\"operations\":[{\"type\":\"classifier.rename\","
                + "\"targetName\":\"Persona\",\"newName\":\"Humana\"}],"
                + "\"question\":null,\"explanation\":\"\"}\n```\nTexto posterior");
        AssistantPlanService service = service(provider);

        AssistantPlanResponse response = service.plan(PROJECT_ID, new AssistantPlanRequest(UTTERANCE, 7L, null));

        assertThat(response.intent()).isEqualTo("PLAN");
        assertThat(response.operations()).hasSize(1);
        assertThat(response.operations().get(0).targetId()).isEqualTo(CLASSIFIER_ID);
        assertThat(response.operations().get(0).args()).containsEntry("newName", "Humana");
        verify(authorizationService).requireEditor(PROJECT_ID);

        org.mockito.ArgumentCaptor<String> context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(provider).draft(context.capture(), eq(UTTERANCE));
        JsonNode json = objectMapper.readTree(context.getValue());
        assertThat(json.path("classifiers").get(0).path("name").asText()).isEqualTo("Persona");
        assertThat(json.path("classifiers").get(0).path("kind").asText()).isEqualTo("CLASS");
        assertThat(json.path("classifiers").get(0).path("attributes").get(0).path("name").asText())
                .isEqualTo("nombre");
        assertThat(json.path("classifiers").get(0).path("attributes").get(0).path("type").asText())
                .isEqualTo("String");
        assertThat(json.path("classifiers").get(0).path("operations").get(0).path("name").asText())
                .isEqualTo("calcular");
        assertThat(json.path("classifiers").get(0).path("operations").get(0).path("returnType").asText())
                .isEqualTo("void");
        assertThat(json.path("relationships").get(0).path("source").asText()).isEqualTo("Persona");
        assertThat(json.path("relationships").get(0).path("target").asText()).isEqualTo("Cuenta");
    }

    @Test
    void unparseableDraftIsRejectedWithDiagnosticInsteadOfThrowing() {
        AiAssistantProvider provider = mock(AiAssistantProvider.class);
        when(provider.draft(anyString(), eq(UTTERANCE))).thenReturn("No puedo formar un plan válido.");

        AssistantPlanResponse response = service(provider)
                .plan(PROJECT_ID, new AssistantPlanRequest(UTTERANCE, 7L, null));

        assertThat(response.intent()).isEqualTo("REJECT");
        assertThat(response.operations()).isEmpty();
        assertThat(response.diagnostics()).extracting(AssistantPlanResponse.Diagnostic::code)
                .contains("UNPARSEABLE_DRAFT");
    }

    @Test
    void disabledProviderReturnsTheUnavailableDiagnosticForTheHttp503Path() {
        AiAssistantProvider provider = mock(AiAssistantProvider.class);
        when(provider.draft(anyString(), eq(UTTERANCE))).thenThrow(new IllegalStateException("AI provider disabled"));

        AssistantPlanResponse response = service(provider)
                .plan(PROJECT_ID, new AssistantPlanRequest(UTTERANCE, 7L, null));

        assertThat(response.intent()).isEqualTo("REJECT");
        assertThat(response.diagnostics()).extracting(AssistantPlanResponse.Diagnostic::code)
                .contains("PROVIDER_DISABLED");
    }

    @Test
    void staleRevisionDoesNotCallProvider() {
        AiAssistantProvider provider = mock(AiAssistantProvider.class);

        AssistantPlanResponse response = service(provider)
                .plan(PROJECT_ID, new AssistantPlanRequest(UTTERANCE, 6L, null));

        assertThat(response.intent()).isEqualTo("REJECT");
        assertThat(response.operations()).isEmpty();
        assertThat(response.diagnostics()).extracting(AssistantPlanResponse.Diagnostic::code)
                .contains("REVIEW_REQUIRED");
        verify(provider, never()).draft(anyString(), anyString());
    }

    private AssistantPlanService service(AiAssistantProvider provider) {
        return new AssistantPlanService(projectApiService, authorizationService, provider, validator, objectMapper);
    }

    private static ModelResource model(long revision) {
        ClassifierResource persona = new ClassifierResource(CLASSIFIER_ID, "Persona", ClassifierKind.CLASS, false, null,
                List.of(new AttributeResource(ATTRIBUTE_ID, "nombre", "ATTRIBUTE", "PUBLIC", null, null,
                        null, "String", 0)),
                List.of(new ModelResource.OperationResource(UUID.randomUUID(), "calcular", "PUBLIC", false, false,
                        null, "void", 0, List.of())), List.of());
        ClassifierResource cuenta = new ClassifierResource(UUID.randomUUID(), "Cuenta", ClassifierKind.CLASS,
                false, null, List.of(), List.of(), List.of());
        ModelResource.RelationshipResource relationship = new ModelResource.RelationshipResource(UUID.randomUUID(),
                "ASSOCIATION", null, List.of(
                        new ModelResource.RelationshipEndResource(UUID.randomUUID(), CLASSIFIER_ID, null, null, null,
                                null, (short) 0),
                        new ModelResource.RelationshipEndResource(UUID.randomUUID(), cuenta.id(), null, null, null,
                                null, (short) 1)), List.of());
        return new ModelResource(UUID.randomUUID(), PROJECT_ID, revision, UUID.randomUUID(), List.of(),
                List.of(persona, cuenta), List.of(relationship));
    }
}
