package com.diagramador.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.SeverityKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelResourceValidationAdapterTest {

    private static UUID id(long value) {
        return UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
    }

    private static ModelResource.AttributeResource attribute(long id, String name, UUID typeId, String type) {
        return new ModelResource.AttributeResource(id(id), name, "ATTRIBUTE", "PUBLIC", MultiplicityKind.EXACTLY_ONE,
                null, typeId, type, 0);
    }

    private static ModelResource model(List<ModelResource.ClassifierResource> classifiers) {
        return new ModelResource(id(900), id(901), 1, id(902), List.of(), classifiers, List.of());
    }

    private static ModelResource.ClassifierResource clazz(long id, String name,
            List<ModelResource.AttributeResource> attributes) {
        return new ModelResource.ClassifierResource(id(id), name, ClassifierKind.CLASS, false, null, attributes,
                List.of(), List.of());
    }

    private final ModelValidator validator = new ModelValidator();

    @Test
    void aWellTypedModelHasNoDiagnostics() {
        ModelResource model = model(List.of(
                clazz(1, "Owner", List.of(attribute(10, "name", id(50), "String"),
                        attribute(11, "pet", id(2), "Pet"))),
                clazz(2, "Pet", List.of())));

        assertThat(validator.validate(ModelResourceValidationAdapter.toReadModel(model))).isEmpty();
    }

    @Test
    void duplicateMemberNamesAreReportedAsErrorsOnTheImportedModel() {
        ModelResource model = model(List.of(clazz(1, "Owner", List.of(
                attribute(10, "name", id(50), "String"), attribute(11, "name", id(50), "String")))));

        List<ModelValidationDiagnostic> diagnostics = validator
                .validate(ModelResourceValidationAdapter.toReadModel(model));

        assertThat(diagnostics).extracting(ModelValidationDiagnostic::code)
                .contains(ModelValidator.DUPLICATE_MEMBER_NAME);
        assertThat(diagnostics).extracting(ModelValidationDiagnostic::severity).contains(SeverityKind.ERROR);
    }

    @Test
    void aTypeNamedAfterAnImportedClassifierResolvesEvenWithoutAnExplicitReferenceId() {
        ModelResource model = model(List.of(
                clazz(1, "Owner", List.of(attribute(10, "pet", id(77), "Pet"))),
                clazz(2, "Pet", List.of())));

        assertThat(validator.validate(ModelResourceValidationAdapter.toReadModel(model))).isEmpty();
    }
}
