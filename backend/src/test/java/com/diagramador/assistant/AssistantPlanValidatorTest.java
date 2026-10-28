package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.domain.ClassifierKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssistantPlanValidatorTest {

    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NAME_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID RELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private final AssistantPlanValidator validator = new AssistantPlanValidator();

    @Test
    void unknownOperationIsDroppedAndAPlanWithOnlyUnknownOperationsIsRejected() {
        AssistantPlanResponse response = validate("plan", List.of(operation("xmi.export", "diagrama")), model());

        assertThat(response.intent()).isEqualTo("REJECT");
        assertThat(response.operations()).isEmpty();
        assertThat(diagnosticCodes(response)).contains("UNKNOWN_OPERATION");
    }

    @Test
    void ambiguousTargetAsksAQuestionInSpanish() {
        ModelResource ambiguousModel = model(classifier(PERSON_ID, "Cafe"), classifier(ACCOUNT_ID, "Café"));

        AssistantPlanResponse response = validate("plan", List.of(operation("classifier.rename", "cafe")),
                ambiguousModel);

        assertThat(response.intent()).isEqualTo("CLARIFY");
        assertThat(response.question()).startsWith("¿A cuál te referís: ").contains("Cafe").contains("Café");
        assertThat(diagnosticCodes(response)).contains("AMBIGUOUS_TARGET");
    }

    @Test
    void nonexistentTargetRejectsThePlan() {
        AssistantPlanResponse response = validate("plan", List.of(operation("classifier.rename", "Fantasma")), model());

        assertThat(response.intent()).isEqualTo("REJECT");
        assertThat(response.operations()).isEmpty();
        assertThat(diagnosticCodes(response)).contains("UNKNOWN_TARGET");
    }

    @Test
    void classifierCreateCollisionDropsTheOperationAndRejectsAnEmptyPlan() {
        AssistantPlanResponse response = validate("plan", List.of(operation("classifier.create", "pérsona")), model());

        assertThat(response.intent()).isEqualTo("REJECT");
        assertThat(response.operations()).isEmpty();
        assertThat(diagnosticCodes(response)).contains("NAME_COLLISION");
    }

    @Test
    void unresolvedDestructiveTargetRejectsThePlan() {
        AssistantPlanResponse response = validate("plan", List.of(operation("classifier.delete", "Fantasma")), model());

        assertThat(response.intent()).isEqualTo("REJECT");
        assertThat(response.operations()).isEmpty();
        assertThat(diagnosticCodes(response)).contains("UNKNOWN_TARGET");
    }

    @Test
    void validCreateAddUpdateAndRelationshipTargetsResolveDeterministically() {
        List<Map<String, Object>> draft = List.of(
                operation("classifier.create", "Factura", "kind", "CLASS"),
                operation("classifier.rename", "Persona", "name", "Humana"),
                operation("attribute.update", "Persona.nombre", "args", Map.of("type", "String")),
                operation("attribute.add", "Persona.edad", "args", Map.of("type", "Integer")),
                operation("operation.add", "Cuenta.calcular", "returnType", "void"),
                operation("relationship.update", "Cuenta->Persona", "kind", "ASSOCIATION"),
                operation("relationship.create", "Persona->Cuenta", "kind", "ASSOCIATION"),
                operation("relationship.delete", "Persona->Cuenta"));

        AssistantPlanResponse response = validate("plan", draft, model());

        assertThat(response.intent()).isEqualTo("PLAN");
        assertThat(response.operations()).hasSize(draft.size());
        assertThat(response.operations().get(0).targetId()).isNull();
        assertThat(response.operations().get(0).args()).containsEntry("name", "Factura");
        assertThat(response.operations().get(1).targetId()).isEqualTo(PERSON_ID);
        assertThat(response.operations().get(2).targetId()).isEqualTo(NAME_ID);
        assertThat(response.operations().get(2).args()).containsEntry("type", "String");
        assertThat(response.operations().get(3).targetId()).isEqualTo(PERSON_ID);
        assertThat(response.operations().get(3).args()).containsEntry("type", "Integer");
        assertThat(response.operations().get(4).targetId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.operations().get(5).targetId()).isEqualTo(RELATION_ID);
        assertThat(response.operations().get(6).targetId()).isNull();
        assertThat(response.operations().get(6).args()).containsKeys("source", "target");
        assertThat(response.operations().get(7).targetId()).isEqualTo(RELATION_ID);
        assertThat(response.diagnostics()).isEmpty();
    }

    @Test
    void classifierMoveIsInTheCatalogAndResolvesItsTargetByName() {
        AssistantPlanResponse response = validate("plan",
                List.of(operation("classifier.move", "Persona", "args", Map.of("x", 300, "y", 120))),
                model(classifier(PERSON_ID, "Persona"), classifier(ACCOUNT_ID, "Cliente")));

        assertThat(response.intent()).isEqualTo("PLAN");
        assertThat(response.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.type()).isEqualTo("classifier.move");
            assertThat(operation.targetId()).isEqualTo(PERSON_ID);
            assertThat(operation.args()).containsEntry("x", 300).containsEntry("y", 120);
        });
    }

    @Test
    void sameDraftCreateAttributeAndRelationshipResolvesAgainstThePlannedCreate() {
        List<Map<String, Object>> draft = List.of(
                operation("classifier.create", "Pedido", "kind", "CLASS"),
                operation("attribute.add", "Pedido.fecha", "args", Map.of("type", "Date")),
                operation("relationship.create", "Pedido->Cliente", "kind", "ASSOCIATION"));

        AssistantPlanResponse response = validate("plan", draft,
                model(classifier(PERSON_ID, "Persona"), classifier(ACCOUNT_ID, "Cliente")));

        assertThat(response.intent()).isEqualTo("PLAN");
        assertThat(response.operations()).hasSize(3);
        assertThat(response.operations().get(0).targetId()).isNull();
        assertThat(response.operations().get(0).args()).containsEntry("name", "Pedido");
        assertThat(response.operations().get(1).targetId()).isNull();
        assertThat(response.operations().get(1).args())
                .containsEntry("name", "fecha")
                .containsEntry("targetClassifierName", "Pedido");
        assertThat(response.operations().get(2).args())
                .containsEntry("source", Map.of("classifierName", "Pedido"))
                .containsEntry("target", Map.of("classifierId", ACCOUNT_ID));
        assertThat(response.diagnostics()).isEmpty();
    }

    @Test
    void attributeAddOnALaterOperationResolvesAPlanLocalCreate() {
        List<Map<String, Object>> draft = List.of(
                operation("classifier.create", "Factura", "kind", "CLASS"),
                operation("attribute.add", "Factura.fecha", "args", Map.of("type", "Date")));

        AssistantPlanResponse response = validate("plan", draft, model());

        assertThat(response.intent()).isEqualTo("PLAN");
        assertThat(response.operations()).hasSize(2);
        assertThat(response.operations().get(1).targetId()).isNull();
        assertThat(response.operations().get(1).args())
                .containsEntry("name", "fecha")
                .containsEntry("targetClassifierName", "Factura");
        assertThat(response.diagnostics()).isEmpty();
    }

    @Test
    void duplicatePlanLocalCreateNameCollides() {
        AssistantPlanResponse response = validate("plan", List.of(
                operation("classifier.create", "Pedido"),
                operation("classifier.create", "pedido")), model());

        assertThat(diagnosticCodes(response)).contains("NAME_COLLISION");
        assertThat(response.operations()).hasSize(1);
        assertThat(response.operations().get(0).args()).containsEntry("name", "Pedido");
    }

    @Test
    void relationshipBetweenAPlanLocalCreateAndAnExistingClassifierResolves() {
        List<Map<String, Object>> draft = List.of(
                operation("classifier.create", "Factura"),
                operation("relationship.create", "Factura->Persona", "kind", "ASSOCIATION"));

        AssistantPlanResponse response = validate("plan", draft, model());

        assertThat(response.intent()).isEqualTo("PLAN");
        assertThat(response.operations()).hasSize(2);
        assertThat(response.operations().get(1).args())
                .containsEntry("source", Map.of("classifierName", "Factura"))
                .containsEntry("target", Map.of("classifierId", PERSON_ID));
        assertThat(response.diagnostics()).isEmpty();
    }

    private AssistantPlanResponse validate(String intent, List<Map<String, Object>> operations, ModelResource model) {
        return validator.validate(intent, operations, model, null, "");
    }

    private static List<String> diagnosticCodes(AssistantPlanResponse response) {
        return response.diagnostics().stream().map(AssistantPlanResponse.Diagnostic::code).toList();
    }

    private static Map<String, Object> operation(String type, String targetName, Object... args) {
        java.util.HashMap<String, Object> operation = new java.util.HashMap<>();
        operation.put("type", type);
        operation.put("targetName", targetName);
        for (int index = 0; index < args.length; index += 2) {
            operation.put((String) args[index], args[index + 1]);
        }
        return operation;
    }

    private static ModelResource model(ClassifierResource... classifiers) {
        List<ClassifierResource> values = classifiers.length == 0
                ? List.of(
                        classifier(PERSON_ID, "Persona", List.of(attribute(NAME_ID, "nombre")), List.of()),
                        classifier(ACCOUNT_ID, "Cuenta", List.of(), List.of(operationResource("calcular"))))
                : List.of(classifiers);
        List<RelationshipResource> relationships = List.of(new RelationshipResource(RELATION_ID,
                "ASSOCIATION", null,
                List.of(end(PERSON_ID), end(ACCOUNT_ID)), List.of()));
        return new ModelResource(UUID.randomUUID(), UUID.randomUUID(), 7L, UUID.randomUUID(), List.of(), values,
                relationships);
    }

    private static ClassifierResource classifier(UUID id, String name) {
        return classifier(id, name, List.of(), List.of());
    }

    private static ClassifierResource classifier(UUID id, String name, List<AttributeResource> attributes,
            List<OperationResource> operations) {
        return new ClassifierResource(id, name, ClassifierKind.CLASS, false, null, attributes, operations, List.of());
    }

    private static AttributeResource attribute(UUID id, String name) {
        return new AttributeResource(id, name, "ATTRIBUTE", "PUBLIC", null, null, null, "String", 0);
    }

    private static OperationResource operationResource(String name) {
        return new OperationResource(UUID.randomUUID(), name, "PUBLIC", false, false, null, "void", 0, List.of());
    }

    private static RelationshipEndResource end(UUID classifierId) {
        return new RelationshipEndResource(UUID.randomUUID(), classifierId, null, null, null, null, (short) 0);
    }
}
