package com.diagramador.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.UmlRelationship;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure generalization-cycle and self-generalization rules (R1, R2). */
class ModelValidatorTest {

    private static final UUID PACKAGE_ID = id(100);
    private static final UUID PERSON_ID = id(1);
    private static final UUID EMPLOYEE_ID = id(2);
    private static final UUID ANIMAL_ID = id(3);
    private static final UUID ORDER_ID = id(4);
    private static final UUID X_ID = id(5);
    private static final UUID Y_ID = id(6);
    private static final UUID UNKNOWN_ID = id(50);

    private final ModelValidator validator = new ModelValidator();

    @Test
    void modelWithoutGeneralizationCyclesHasNoDiagnostics() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee"),
                        classifier(ANIMAL_ID, null, ClassifierKind.CLASS, "Animal"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Order")),
                List.of(relationship(10, RelationshipKind.GENERALIZATION, null),
                        relationship(11, RelationshipKind.GENERALIZATION, null),
                        relationship(12, RelationshipKind.ASSOCIATION, "places")),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, EMPLOYEE_ID, (short) 2),
                        end(22, 11, ANIMAL_ID, (short) 1), end(23, 11, PERSON_ID, (short) 2),
                        end(24, 12, PERSON_ID, (short) 1), end(25, 12, ORDER_ID, (short) 2)));

        assertThat(validator.validate(model)).isEmpty();
    }

    @Test
    void twoClassifierGeneralizationCycleIsReportedForEveryParticipant() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee")),
                List.of(relationship(10, RelationshipKind.GENERALIZATION, null),
                        relationship(11, RelationshipKind.GENERALIZATION, null)),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, EMPLOYEE_ID, (short) 2),
                        end(22, 11, EMPLOYEE_ID, (short) 1), end(23, 11, PERSON_ID, (short) 2)));

        List<ModelValidationDiagnostic> diagnostics = validator.generalizationCycles(model);

        assertThat(diagnostics).hasSize(2);
        assertThat(diagnostics).allSatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("GENERALIZATION_CYCLE");
            assertThat(diagnostic.severity()).isEqualTo(SeverityKind.ERROR);
            assertThat(diagnostic.message()).isNotBlank();
        });
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactlyInAnyOrder(PERSON_ID, EMPLOYEE_ID);
    }

    @Test
    void threeClassifierGeneralizationCycleIsReportedForEveryParticipant() {
        UUID thirdId = id(3);
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee"),
                        classifier(thirdId, null, ClassifierKind.CLASS, "Manager")),
                List.of(relationship(10, RelationshipKind.GENERALIZATION, null),
                        relationship(11, RelationshipKind.GENERALIZATION, null),
                        relationship(12, RelationshipKind.GENERALIZATION, null)),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, EMPLOYEE_ID, (short) 2),
                        end(22, 11, EMPLOYEE_ID, (short) 1), end(23, 11, thirdId, (short) 2),
                        end(24, 12, thirdId, (short) 1), end(25, 12, PERSON_ID, (short) 2)));

        List<ModelValidationDiagnostic> diagnostics = validator.generalizationCycles(model);

        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactlyInAnyOrder(PERSON_ID, EMPLOYEE_ID, thirdId);
    }

    @Test
    void classifierOutsideACycleIsNotReported() {
        UUID outsideId = id(9);
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee"),
                        classifier(outsideId, null, ClassifierKind.CLASS, "Visitor")),
                List.of(relationship(10, RelationshipKind.GENERALIZATION, null),
                        relationship(11, RelationshipKind.GENERALIZATION, null),
                        relationship(12, RelationshipKind.GENERALIZATION, null)),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, EMPLOYEE_ID, (short) 2),
                        end(22, 11, EMPLOYEE_ID, (short) 1), end(23, 11, PERSON_ID, (short) 2),
                        end(24, 12, outsideId, (short) 1), end(25, 12, PERSON_ID, (short) 2)));

        List<ModelValidationDiagnostic> diagnostics = validator.generalizationCycles(model);

        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactlyInAnyOrder(PERSON_ID, EMPLOYEE_ID);
    }

    @Test
    void generalizationCycleInsidePackageNamespaceIsReported() {
        Classifier namespace = classifier(PACKAGE_ID, null, ClassifierKind.PACKAGE, "domain");
        ModelValidationReadModel model = readModel(
                List.of(namespace, classifier(X_ID, namespace, ClassifierKind.CLASS, "X"),
                        classifier(Y_ID, namespace, ClassifierKind.CLASS, "Y")),
                List.of(relationship(10, RelationshipKind.GENERALIZATION, null),
                        relationship(11, RelationshipKind.GENERALIZATION, null)),
                List.of(end(20, 10, X_ID, (short) 1), end(21, 10, Y_ID, (short) 2),
                        end(22, 11, Y_ID, (short) 1), end(23, 11, X_ID, (short) 2)));

        List<ModelValidationDiagnostic> diagnostics = validator.generalizationCycles(model);

        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactlyInAnyOrder(X_ID, Y_ID);
    }

    @Test
    void interfaceRealizationCycleIsReportedAsGeneralizationCycle() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.INTERFACE, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.INTERFACE, "Employee")),
                List.of(relationship(10, RelationshipKind.INTERFACE_REALIZATION, null),
                        relationship(11, RelationshipKind.INTERFACE_REALIZATION, null)),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, EMPLOYEE_ID, (short) 2),
                        end(22, 11, EMPLOYEE_ID, (short) 1), end(23, 11, PERSON_ID, (short) 2)));

        List<ModelValidationDiagnostic> diagnostics = validator.generalizationCycles(model);

        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactlyInAnyOrder(PERSON_ID, EMPLOYEE_ID);
        assertThat(diagnostics).allSatisfy(
                diagnostic -> assertThat(diagnostic.code()).isEqualTo("GENERALIZATION_CYCLE"));
    }

    @Test
    void selfGeneralizationIsReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(relationship(10, RelationshipKind.GENERALIZATION, null)),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, PERSON_ID, (short) 2)));

        List<ModelValidationDiagnostic> diagnostics = validator.selfGeneralizations(model);

        assertThat(diagnostics).hasSize(1);
        ModelValidationDiagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.code()).isEqualTo("SELF_GENERALIZATION");
        assertThat(diagnostic.severity()).isEqualTo(SeverityKind.ERROR);
        assertThat(diagnostic.elementId()).isEqualTo(id(10));
        assertThat(diagnostic.feature()).isEqualTo("generalization");
        assertThat(diagnostic.message()).isNotBlank();
        assertThat(validator.generalizationCycles(model)).isEmpty();
    }

    @Test
    void selfInterfaceRealizationIsReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.INTERFACE, "Person")),
                List.of(relationship(10, RelationshipKind.INTERFACE_REALIZATION, null)),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, PERSON_ID, (short) 2)));

        List<ModelValidationDiagnostic> diagnostics = validator.selfGeneralizations(model);

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo("SELF_GENERALIZATION");
        assertThat(diagnostics.get(0).feature()).isEqualTo("interfaceRealization");
    }

    @Test
    void reflexiveAssociationIsNotSelfGeneralization() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(relationship(10, RelationshipKind.ASSOCIATION, "knows")),
                List.of(end(20, 10, PERSON_ID, (short) 1), end(21, 10, PERSON_ID, (short) 2)));

        assertThat(validator.validate(model)).isEmpty();
    }

    @Test
    void unresolvedRelationshipEndsAreIgnoredByCycleRules() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(
                        // both ends reference a classifier outside the model
                        relationship(10, RelationshipKind.GENERALIZATION, null),
                        // one end resolved, one dangling
                        relationship(11, RelationshipKind.GENERALIZATION, null),
                        // single-end hierarchy relationship
                        relationship(12, RelationshipKind.GENERALIZATION, null),
                        // unresolved self-referencing ends
                        relationship(13, RelationshipKind.GENERALIZATION, null)),
                List.of(end(20, 10, UNKNOWN_ID, (short) 1), end(21, 10, UNKNOWN_ID, (short) 2),
                        end(22, 11, PERSON_ID, (short) 1), end(23, 11, UNKNOWN_ID, (short) 2),
                        end(24, 12, PERSON_ID, (short) 1),
                        end(25, 13, UNKNOWN_ID, (short) 1), end(26, 13, UNKNOWN_ID, (short) 2)));

        assertThat(validator.validate(model)).isEmpty();
    }

    private static ModelValidationReadModel readModel(List<Classifier> classifiers,
            List<UmlRelationship> relationships, List<RelationshipEnd> ends) {
        return ModelValidationReadModel.from(classifiers, relationships, ends, List.of(), List.of(), List.of());
    }


    private static Classifier classifier(UUID id, Classifier namespace, ClassifierKind kind, String name) {
        return Classifier.create(id, null, namespace, kind, name, false);
    }

    private static UmlRelationship relationship(long id, RelationshipKind kind, String name) {
        UmlRelationship relationship = UmlRelationship.create(null, kind, name);
        return withId(relationship, id(id));
    }

    private static RelationshipEnd end(long id, long relationshipId, UUID classifierId, short ordinal) {
        UmlRelationship relationship = UmlRelationship.create(null, RelationshipKind.GENERALIZATION, null);
        withId(relationship, id(relationshipId));
        RelationshipEnd end = RelationshipEnd.create(relationship, classifier(classifierId, null,
                ClassifierKind.CLASS, "End-" + classifierId), ordinal, null, MultiplicityKind.EXACTLY_ONE,
                NavigationKind.UNSPECIFIED, "none");
        return withId(end, id(id));
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static <T> T withId(T entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot set entity id", exception);
        }
    }
}
