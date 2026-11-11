package com.diagramador.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure semantic rules R1–R6 over the validation read-model.
 *
 * <p>R4 deviation recorded in the acta (odd/tasks/validacion-uml-semantica.md §3.1 R4 note): RANGE multiplicity
 * bounds do not exist in the canonical model, so the rule reports INCOMPLETE_MULTIPLICITY_SPECIFICATION (WARNING)
 * instead of comparing lower/upper bounds.
 */
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
                        end(24, 12, PERSON_ID, (short) 1, null, MultiplicityKind.EXACTLY_ONE),
                        end(25, 12, ORDER_ID, (short) 2, null, MultiplicityKind.ZERO_OR_MANY)));

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
                List.of(end(20, 10, PERSON_ID, (short) 1, null, MultiplicityKind.ZERO_OR_ONE),
                        end(21, 10, PERSON_ID, (short) 2, null, MultiplicityKind.ZERO_OR_MANY)));

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

    // ---- R3: relationship end validity ----

    @Test
    void duplicateRoleNamesAcrossGeneralizationEndsAreReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee")),
                List.of(relationship(20, RelationshipKind.GENERALIZATION, null)),
                List.of(end(30, 20, PERSON_ID, (short) 1, "parent", MultiplicityKind.EXACTLY_ONE),
                        end(31, 20, EMPLOYEE_ID, (short) 2, "parent", MultiplicityKind.EXACTLY_ONE)));

        List<ModelValidationDiagnostic> diagnostics = validator.relationshipEndValidity(model);

        assertThat(diagnostics).hasSize(1);
        ModelValidationDiagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.code()).isEqualTo("DUPLICATE_ROLE_NAME");
        assertThat(diagnostic.severity()).isEqualTo(SeverityKind.ERROR);
        assertThat(diagnostic.elementId()).isEqualTo(id(20));
        assertThat(diagnostic.feature()).isEqualTo("role");
        assertThat(diagnostic.message()).isNotBlank();
    }

    @Test
    void duplicateRoleIsReportedOnlyWhenBothRolesAreNonNull() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee"),
                        classifier(ANIMAL_ID, null, ClassifierKind.CLASS, "Animal")),
                List.of(relationship(20, RelationshipKind.GENERALIZATION, null),
                        relationship(21, RelationshipKind.GENERALIZATION, null)),
                List.of(end(30, 20, PERSON_ID, (short) 1, null, MultiplicityKind.EXACTLY_ONE),
                        end(31, 20, EMPLOYEE_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE),
                        end(32, 21, PERSON_ID, (short) 1, "child", MultiplicityKind.EXACTLY_ONE),
                        end(33, 21, ANIMAL_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE)));

        assertThat(validator.relationshipEndValidity(model)).isEmpty();
    }

    @Test
    void toManyMultiplicityOnGeneralizationEndIsReportedAtTheEndClassifier() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee"),
                        classifier(ANIMAL_ID, null, ClassifierKind.CLASS, "Animal"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Order")),
                List.of(relationship(20, RelationshipKind.GENERALIZATION, null),
                        relationship(21, RelationshipKind.GENERALIZATION, null),
                        relationship(22, RelationshipKind.GENERALIZATION, null)),
                List.of(end(30, 20, PERSON_ID, (short) 1, null, MultiplicityKind.ONE_OR_MANY),
                        end(31, 20, ORDER_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE),
                        end(32, 21, EMPLOYEE_ID, (short) 1, null, MultiplicityKind.ZERO_OR_MANY),
                        end(33, 21, ORDER_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE),
                        end(34, 22, ANIMAL_ID, (short) 1, null, MultiplicityKind.RANGE),
                        end(35, 22, ORDER_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE)));

        List<ModelValidationDiagnostic> diagnostics = validator.relationshipEndValidity(model);

        assertThat(diagnostics).hasSize(3);
        assertThat(diagnostics).allSatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("INVALID_GENERALIZATION_MULTIPLICITY");
            assertThat(diagnostic.severity()).isEqualTo(SeverityKind.ERROR);
            assertThat(diagnostic.feature()).isEqualTo("multiplicity");
            assertThat(diagnostic.message()).isNotBlank();
        });
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactlyInAnyOrder(PERSON_ID, EMPLOYEE_ID, ANIMAL_ID);
    }

    @Test
    void toOneOrMissingMultiplicityOnGeneralizationEndIsFine() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee"),
                        classifier(ANIMAL_ID, null, ClassifierKind.CLASS, "Animal"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Order")),
                List.of(relationship(20, RelationshipKind.GENERALIZATION, null),
                        relationship(21, RelationshipKind.GENERALIZATION, null),
                        relationship(22, RelationshipKind.GENERALIZATION, null)),
                List.of(end(30, 20, PERSON_ID, (short) 1, null, MultiplicityKind.EXACTLY_ONE),
                        end(31, 20, ORDER_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE),
                        end(32, 21, EMPLOYEE_ID, (short) 1, null, MultiplicityKind.ZERO_OR_ONE),
                        end(33, 21, ORDER_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE),
                        end(34, 22, ANIMAL_ID, (short) 1, null, null),
                        end(35, 22, ORDER_ID, (short) 2, null, null)));

        assertThat(validator.validate(model)).isEmpty();
    }

    @Test
    void generalizationEndWithoutClassifierIdReportsTheRelationshipId() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Order")),
                List.of(relationship(20, RelationshipKind.GENERALIZATION, null)),
                List.of(end(30, 20, null, (short) 1, null, MultiplicityKind.ZERO_OR_MANY),
                        end(31, 20, ORDER_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE)));

        List<ModelValidationDiagnostic> diagnostics = validator.relationshipEndValidity(model);

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo("INVALID_GENERALIZATION_MULTIPLICITY");
        assertThat(diagnostics.get(0).elementId()).isEqualTo(id(20));
    }

    @Test
    void toManyMultiplicityOnInterfaceRealizationEndIsReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.INTERFACE, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee")),
                List.of(relationship(20, RelationshipKind.INTERFACE_REALIZATION, null)),
                List.of(end(30, 20, EMPLOYEE_ID, (short) 1, null, MultiplicityKind.RANGE),
                        end(31, 20, PERSON_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE)));

        List<ModelValidationDiagnostic> diagnostics = validator.relationshipEndValidity(model);

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo("INVALID_GENERALIZATION_MULTIPLICITY");
        assertThat(diagnostics.get(0).elementId()).isEqualTo(EMPLOYEE_ID);
    }

    @Test
    void unknownGeneralizationEndsAreIgnoredByEndValidity() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(relationship(20, RelationshipKind.GENERALIZATION, null)),
                List.of(end(30, 20, UNKNOWN_ID, (short) 1, "same", MultiplicityKind.RANGE),
                        end(31, 20, UNKNOWN_ID, (short) 2, "same", MultiplicityKind.RANGE)));

        assertThat(validator.relationshipEndValidity(model)).isEmpty();
    }

    // ---- R4: incomplete RANGE multiplicity specification (acta deviation, see class javadoc) ----

    @Test
    void rangeAttributeWithoutLowerBoundIsReportedAsIncompleteSpecification() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, null, "tags", MultiplicityKind.RANGE),
                        attribute(41, PERSON_ID, null, "name", MultiplicityKind.EXACTLY_ONE)),
                List.of(), List.of(), List.of());

        List<ModelValidationDiagnostic> diagnostics = validator.incompleteRangeMultiplicities(model);

        assertThat(diagnostics).hasSize(1);
        ModelValidationDiagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.code()).isEqualTo("INCOMPLETE_MULTIPLICITY_SPECIFICATION");
        assertThat(diagnostic.severity()).isEqualTo(SeverityKind.WARNING);
        assertThat(diagnostic.elementId()).isEqualTo(PERSON_ID);
        assertThat(diagnostic.feature()).isEqualTo("tags");
        assertThat(diagnostic.message()).isNotBlank();
    }

    @Test
    void rangeAttributeWithBoundsIsNotReported() {
        UmlAttribute bounded = UmlAttribute.create(id(42), classifier(PERSON_ID, null, ClassifierKind.CLASS, "Owner"),
                null, "tags", "property", "private", MultiplicityKind.RANGE, 2, 5, null, 0);
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(), List.of(), List.of(withId(bounded, id(42))), List.of(), List.of(), List.of());

        assertThat(validator.incompleteRangeMultiplicities(model)).isEmpty();
    }

    // ---- R5: unique member names per namespace ----

    @Test
    void duplicateAttributeNamesWithinClassifierAreReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, null, "name", MultiplicityKind.EXACTLY_ONE),
                        attribute(41, PERSON_ID, null, "name", MultiplicityKind.ZERO_OR_ONE),
                        attribute(42, EMPLOYEE_ID, null, "name", MultiplicityKind.EXACTLY_ONE)),
                List.of(), List.of(), List.of());

        List<ModelValidationDiagnostic> diagnostics = validator.duplicateMemberNames(model);

        assertThat(diagnostics).hasSize(1);
        ModelValidationDiagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.code()).isEqualTo("DUPLICATE_MEMBER_NAME");
        assertThat(diagnostic.severity()).isEqualTo(SeverityKind.ERROR);
        assertThat(diagnostic.elementId()).isEqualTo(PERSON_ID);
        assertThat(diagnostic.feature()).isEqualTo("name");
        assertThat(diagnostic.message()).isNotBlank();
    }

    @Test
    void duplicateOperationNamesWithinClassifierAreReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(), List.of(), List.of(),
                List.of(operation(50, PERSON_ID, null, "execute"), operation(51, PERSON_ID, null, "execute")),
                List.of(), List.of());

        List<ModelValidationDiagnostic> diagnostics = validator.duplicateMemberNames(model);

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo("DUPLICATE_MEMBER_NAME");
        assertThat(diagnostics.get(0).elementId()).isEqualTo(PERSON_ID);
        assertThat(diagnostics.get(0).feature()).isEqualTo("execute");
    }

    @Test
    void attributeAndOperationWithSameNameDoNotCollide() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, null, "x", MultiplicityKind.EXACTLY_ONE)),
                List.of(operation(50, PERSON_ID, null, "x")),
                List.of(), List.of());

        assertThat(validator.duplicateMemberNames(model)).isEmpty();
    }

    @Test
    void memberNameCollisionsAreCaseSensitive() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, null, "Name", MultiplicityKind.EXACTLY_ONE),
                        attribute(41, PERSON_ID, null, "name", MultiplicityKind.EXACTLY_ONE)),
                List.of(), List.of(), List.of());

        assertThat(validator.duplicateMemberNames(model)).isEmpty();
    }

    // ---- R6: resolvable type references ----

    @Test
    void primitiveAndClassifierTypeReferencesResolve() {
        TypeReference stringRef = primitiveReference(60, "String");
        TypeReference integerRef = primitiveReference(61, "Integer");
        TypeReference orderRef = classifierReference(62, ORDER_ID);
        UmlOperation operation = operation(50, PERSON_ID, orderRef, "execute");
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Order")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, stringRef, "name", MultiplicityKind.EXACTLY_ONE),
                        attribute(41, PERSON_ID, orderRef, "order", MultiplicityKind.EXACTLY_ONE)),
                List.of(operation),
                List.of(parameter(70, operation, integerRef, "amount")),
                List.of(stringRef, integerRef, orderRef));

        assertThat(validator.unresolvedTypes(model)).isEmpty();
    }

    @Test
    void unresolvedTypeReferenceIsReportedPerFeature() {
        UmlOperation operation = operation(50, PERSON_ID, null, "execute");
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, primitiveReference(60, "Money"), "price",
                        MultiplicityKind.EXACTLY_ONE)),
                List.of(operation),
                List.of(parameter(70, operation, null, "amount")),
                List.of());

        List<ModelValidationDiagnostic> diagnostics = validator.unresolvedTypes(model);

        assertThat(diagnostics).hasSize(2);
        assertThat(diagnostics).allSatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("UNRESOLVED_TYPE");
            assertThat(diagnostic.severity()).isEqualTo(SeverityKind.ERROR);
            assertThat(diagnostic.elementId()).isEqualTo(PERSON_ID);
        });
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::feature)
                .containsExactlyInAnyOrder("price", "amount");
    }

    @Test
    void typeReferenceToUnknownOrPackageClassifierIsUnresolved() {
        TypeReference unknownRef = classifierReference(60, UNKNOWN_ID);
        TypeReference packageRef = withId(TypeReference.forClassifier(null,
                classifier(PACKAGE_ID, null, ClassifierKind.PACKAGE, "domain")), id(61));
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(PACKAGE_ID, null, ClassifierKind.PACKAGE, "domain")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, unknownRef, "a", MultiplicityKind.EXACTLY_ONE),
                        attribute(41, PERSON_ID, packageRef, "b", MultiplicityKind.EXACTLY_ONE)),
                List.of(), List.of(), List.of(unknownRef, packageRef));

        List<ModelValidationDiagnostic> diagnostics = validator.unresolvedTypes(model);

        assertThat(diagnostics).hasSize(2);
        assertThat(diagnostics).allSatisfy(
                diagnostic -> assertThat(diagnostic.code()).isEqualTo("UNRESOLVED_TYPE"));
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::feature)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void missingTypeReferenceOnAttributeIsUnresolved() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")),
                List.of(), List.of(),
                List.of(attribute(40, PERSON_ID, null, "name", MultiplicityKind.EXACTLY_ONE)),
                List.of(), List.of(), List.of());

        List<ModelValidationDiagnostic> diagnostics = validator.unresolvedTypes(model);

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo("UNRESOLVED_TYPE");
        assertThat(diagnostics.get(0).elementId()).isEqualTo(PERSON_ID);
        assertThat(diagnostics.get(0).feature()).isEqualTo("name");
    }

    @Test
    void manyToManyAssociationIsReportedAsUnsupportedMultiplicityCombination() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Student"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Course")),
                List.of(relationship(10, RelationshipKind.ASSOCIATION, "enrolls")),
                List.of(end(20, 10, PERSON_ID, (short) 1, null, MultiplicityKind.ZERO_OR_MANY),
                        end(21, 10, ORDER_ID, (short) 2, null, MultiplicityKind.ONE_OR_MANY)));

        List<ModelValidationDiagnostic> diagnostics = validator.validate(model);

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo("UNSUPPORTED_MULTIPLICITY_COMBINATION");
        assertThat(diagnostics.get(0).severity()).isEqualTo(SeverityKind.ERROR);
        assertThat(diagnostics.get(0).elementId()).isEqualTo(id(10));
        assertThat(diagnostics.get(0).feature()).isEqualTo("multiplicity");
        assertThat(diagnostics.get(0).message()).contains("enrolls", "ZERO_OR_MANY", "ONE_OR_MANY",
                "intermediate class");
    }

    @Test
    void oneToOneCompositionIsReportedAsUnsupportedMultiplicityCombination() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Passport")),
                List.of(relationship(10, RelationshipKind.COMPOSITION, "holds")),
                List.of(end(20, 10, PERSON_ID, (short) 1, null, MultiplicityKind.EXACTLY_ONE),
                        end(21, 10, ORDER_ID, (short) 2, null, MultiplicityKind.ZERO_OR_ONE)));

        assertThat(validator.validate(model)).extracting(ModelValidationDiagnostic::code)
                .containsExactly("UNSUPPORTED_MULTIPLICITY_COMBINATION");
    }

    @Test
    void oneToManyAggregationHasNoDiagnostics() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Team"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "Player")),
                List.of(relationship(10, RelationshipKind.AGGREGATION, "groups")),
                List.of(end(20, 10, PERSON_ID, (short) 1, null, MultiplicityKind.ZERO_OR_ONE),
                        end(21, 10, ORDER_ID, (short) 2, null, MultiplicityKind.ZERO_OR_MANY)));

        assertThat(validator.validate(model)).isEmpty();
    }

    @Test
    void mandatoryReferenceCycleIsReportedForEveryParticipant() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Department"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee")),
                List.of(relationship(10, RelationshipKind.ASSOCIATION, "worksIn"),
                        relationship(11, RelationshipKind.ASSOCIATION, "managedBy")),
                List.of(end(20, 10, EMPLOYEE_ID, (short) 1, null, MultiplicityKind.ZERO_OR_MANY),
                        end(21, 10, PERSON_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE),
                        end(22, 11, PERSON_ID, (short) 1, null, MultiplicityKind.ZERO_OR_MANY),
                        end(23, 11, EMPLOYEE_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE)));

        List<ModelValidationDiagnostic> diagnostics = validator.validate(model);

        assertThat(diagnostics).extracting(ModelValidationDiagnostic::code)
                .containsExactly("MANDATORY_REFERENCE_CYCLE", "MANDATORY_REFERENCE_CYCLE");
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactly(PERSON_ID, EMPLOYEE_ID);
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::severity).containsOnly(SeverityKind.ERROR);
        assertThat(diagnostics.get(1).message()).contains("Employee");
    }

    @Test
    void optionalReferenceCycleIsNotReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Department"),
                        classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee")),
                List.of(relationship(10, RelationshipKind.ASSOCIATION, "worksIn"),
                        relationship(11, RelationshipKind.ASSOCIATION, "managedBy")),
                List.of(end(20, 10, EMPLOYEE_ID, (short) 1, null, MultiplicityKind.ZERO_OR_MANY),
                        end(21, 10, PERSON_ID, (short) 2, null, MultiplicityKind.EXACTLY_ONE),
                        end(22, 11, PERSON_ID, (short) 1, null, MultiplicityKind.ZERO_OR_MANY),
                        end(23, 11, EMPLOYEE_ID, (short) 2, null, MultiplicityKind.ZERO_OR_ONE)));

        assertThat(validator.validate(model)).isEmpty();
    }

    @Test
    void mandatorySelfReferenceIsReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(EMPLOYEE_ID, null, ClassifierKind.CLASS, "Employee")),
                List.of(relationship(10, RelationshipKind.ASSOCIATION, "reportsTo")),
                List.of(end(20, 10, EMPLOYEE_ID, (short) 1, "subordinates", MultiplicityKind.ZERO_OR_MANY),
                        end(21, 10, EMPLOYEE_ID, (short) 2, "manager", MultiplicityKind.EXACTLY_ONE)));

        List<ModelValidationDiagnostic> diagnostics = validator.validate(model);

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo("MANDATORY_REFERENCE_CYCLE");
        assertThat(diagnostics.get(0).elementId()).isEqualTo(EMPLOYEE_ID);
    }

    @Test
    void compositionCycleIsReportedAsWarningForEveryParticipant() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Order"),
                        classifier(ORDER_ID, null, ClassifierKind.CLASS, "OrderLine")),
                List.of(relationship(10, RelationshipKind.COMPOSITION, "contains"),
                        relationship(11, RelationshipKind.COMPOSITION, "owns")),
                List.of(end(20, 10, PERSON_ID, (short) 1, null, MultiplicityKind.ZERO_OR_ONE),
                        end(21, 10, ORDER_ID, (short) 2, null, MultiplicityKind.ZERO_OR_MANY),
                        end(22, 11, ORDER_ID, (short) 1, null, MultiplicityKind.ZERO_OR_ONE),
                        end(23, 11, PERSON_ID, (short) 2, null, MultiplicityKind.ZERO_OR_MANY)));

        List<ModelValidationDiagnostic> diagnostics = validator.validate(model);

        assertThat(diagnostics).extracting(ModelValidationDiagnostic::code)
                .containsExactly("COMPOSITION_CYCLE", "COMPOSITION_CYCLE");
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::elementId)
                .containsExactly(PERSON_ID, ORDER_ID);
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::severity)
                .containsOnly(SeverityKind.WARNING);
    }

    @Test
    void selfCompositionIsNotReported() {
        ModelValidationReadModel model = readModel(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Folder")),
                List.of(relationship(10, RelationshipKind.COMPOSITION, "children")),
                List.of(end(20, 10, PERSON_ID, (short) 1, "parent", MultiplicityKind.ZERO_OR_ONE),
                        end(21, 10, PERSON_ID, (short) 2, "children", MultiplicityKind.ZERO_OR_MANY)));

        assertThat(validator.validate(model)).isEmpty();
    }

    private static ModelValidationReadModel readModel(List<Classifier> classifiers,
            List<UmlRelationship> relationships, List<RelationshipEnd> ends) {
        return ModelValidationReadModel.from(classifiers, relationships, ends, List.of(), List.of(), List.of());
    }

    private static ModelValidationReadModel readModel(List<Classifier> classifiers,
            List<UmlRelationship> relationships, List<RelationshipEnd> ends, List<UmlAttribute> attributes,
            List<UmlOperation> operations, List<OperationParameter> parameters, List<TypeReference> typeReferences) {
        return ModelValidationReadModel.from(classifiers, relationships, ends, attributes, operations, parameters,
                typeReferences);
    }

    private static UmlAttribute attribute(long attributeId, UUID classifierId, TypeReference typeReference,
            String name, MultiplicityKind multiplicity) {
        return withId(UmlAttribute.create(id(attributeId), classifier(classifierId, null, ClassifierKind.CLASS,
                "Owner-" + classifierId), typeReference, name, "property", "private", multiplicity, null, 0),
                id(attributeId));
    }

    private static UmlOperation operation(long operationId, UUID classifierId, TypeReference returnType,
            String name) {
        return withId(UmlOperation.create(id(operationId), classifier(classifierId, null, ClassifierKind.CLASS,
                "Owner-" + classifierId), name, returnType, "public", false, false, 0), id(operationId));
    }

    private static OperationParameter parameter(long parameterId, UmlOperation operation,
            TypeReference typeReference, String name) {
        return withId(OperationParameter.create(id(parameterId), operation, typeReference, name, "IN", 0),
                id(parameterId));
    }

    private static TypeReference primitiveReference(long referenceId, String primitiveName) {
        return withId(TypeReference.forPrimitive(null, primitiveName), id(referenceId));
    }

    private static TypeReference classifierReference(long referenceId, UUID classifierId) {
        return withId(TypeReference.forClassifier(null, classifier(classifierId, null, ClassifierKind.CLASS,
                "Ref-" + classifierId)), id(referenceId));
    }


    private static Classifier classifier(UUID id, Classifier namespace, ClassifierKind kind, String name) {
        return Classifier.create(id, null, namespace, kind, name, false);
    }

    private static UmlRelationship relationship(long id, RelationshipKind kind, String name) {
        UmlRelationship relationship = UmlRelationship.create(null, kind, name);
        return withId(relationship, id(id));
    }

    private static RelationshipEnd end(long id, long relationshipId, UUID classifierId, short ordinal) {
        return end(id, relationshipId, classifierId, ordinal, null, MultiplicityKind.EXACTLY_ONE);
    }

    private static RelationshipEnd end(long id, long relationshipId, UUID classifierId, short ordinal, String role,
            MultiplicityKind multiplicity) {
        UmlRelationship relationship = UmlRelationship.create(null, RelationshipKind.GENERALIZATION, null);
        withId(relationship, id(relationshipId));
        RelationshipEnd end = RelationshipEnd.create(relationship, classifier(classifierId, null,
                ClassifierKind.CLASS, "End-" + classifierId), ordinal, role, multiplicity,
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
