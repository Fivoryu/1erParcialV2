package com.diagramador.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the immutable validation read-model built from domain entities. */
class ModelValidationReadModelTest {

    private static final UUID PACKAGE_ID = id(100);
    private static final UUID PERSON_ID = id(1);
    private static final UUID ORDER_ID = id(2);
    private static final UUID RELATIONSHIP_ID = id(10);
    private static final UUID END_ONE_ID = id(11);
    private static final UUID END_TWO_ID = id(12);
    private static final UUID ATTRIBUTE_ID = id(30);
    private static final UUID OPERATION_ID = id(40);
    private static final UUID CLASSIFIER_TYPE_ID = id(50);
    private static final UUID PRIMITIVE_TYPE_ID = id(51);

    @Test
    void buildsReadModelFromDomainEntities() {
        Classifier namespace = classifier(PACKAGE_ID, null, ClassifierKind.PACKAGE, "domain");
        Classifier person = classifier(PERSON_ID, namespace, ClassifierKind.CLASS, "Person");
        Classifier order = classifier(ORDER_ID, null, ClassifierKind.CLASS, "Order");
        UmlRelationship relationship = withId(UmlRelationship.create(null, RelationshipKind.GENERALIZATION, null),
                RELATIONSHIP_ID);
        RelationshipEnd subtypeEnd = withId(RelationshipEnd.create(relationship, person, (short) 1, null,
                MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "none"), END_ONE_ID);
        RelationshipEnd supertypeEnd = withId(RelationshipEnd.create(relationship, order, (short) 2, "parent",
                MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "none"), END_TWO_ID);
        TypeReference primitive = withId(TypeReference.forPrimitive(null, "String"), PRIMITIVE_TYPE_ID);
        TypeReference classifierType = withId(TypeReference.forClassifier(null, order), CLASSIFIER_TYPE_ID);
        UmlAttribute attribute = withId(UmlAttribute.create(ATTRIBUTE_ID, person, primitive, "name", "property",
                "private", MultiplicityKind.EXACTLY_ONE, null, 0), ATTRIBUTE_ID);
        UmlOperation operation = withId(UmlOperation.create(OPERATION_ID, person, "rename", classifierType,
                "public", false, false, 0), OPERATION_ID);

        ModelValidationReadModel model = ModelValidationReadModel.from(List.of(person, order, namespace),
                List.of(relationship), List.of(supertypeEnd, subtypeEnd), List.of(attribute), List.of(operation),
                List.of(primitive, classifierType));

        assertThat(model.classifiers()).containsExactly(
                new ModelValidationReadModel.ClassifierElement(PERSON_ID, PACKAGE_ID, ClassifierKind.CLASS, "Person"),
                new ModelValidationReadModel.ClassifierElement(ORDER_ID, null, ClassifierKind.CLASS, "Order"),
                new ModelValidationReadModel.ClassifierElement(PACKAGE_ID, null, ClassifierKind.PACKAGE, "domain"));
        assertThat(model.relationships()).containsExactly(
                new ModelValidationReadModel.RelationshipElement(RELATIONSHIP_ID, RelationshipKind.GENERALIZATION,
                        null));
        assertThat(model.relationshipEnds()).containsExactly(
                new ModelValidationReadModel.RelationshipEndElement(END_ONE_ID, RELATIONSHIP_ID, PERSON_ID,
                        (short) 1, null, MultiplicityKind.EXACTLY_ONE),
                new ModelValidationReadModel.RelationshipEndElement(END_TWO_ID, RELATIONSHIP_ID, ORDER_ID,
                        (short) 2, "parent", MultiplicityKind.EXACTLY_ONE));
        assertThat(model.attributes()).containsExactly(
                new ModelValidationReadModel.AttributeElement(ATTRIBUTE_ID, PERSON_ID, PRIMITIVE_TYPE_ID, "name",
                        MultiplicityKind.EXACTLY_ONE, 0));
        assertThat(model.operations()).containsExactly(
                new ModelValidationReadModel.OperationElement(OPERATION_ID, PERSON_ID, CLASSIFIER_TYPE_ID, "rename",
                        0));
        assertThat(model.typeReferences()).containsExactly(
                new ModelValidationReadModel.TypeReferenceElement(CLASSIFIER_TYPE_ID, ORDER_ID, null),
                new ModelValidationReadModel.TypeReferenceElement(PRIMITIVE_TYPE_ID, null, "String"));
    }

    @Test
    void readModelIsImmutable() {
        ModelValidationReadModel model = ModelValidationReadModel.from(
                List.of(classifier(PERSON_ID, null, ClassifierKind.CLASS, "Person")), List.of(), List.of(),
                List.of(), List.of(), List.of());

        assertThatThrownBy(() -> model.classifiers().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Classifier classifier(UUID id, Classifier namespace, ClassifierKind kind, String name) {
        return Classifier.create(id, null, namespace, kind, name, false);
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
