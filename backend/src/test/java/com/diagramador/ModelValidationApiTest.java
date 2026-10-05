package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API tests for GET /projects/{projectId}/model/validation: 404 for an unknown project, an empty
 * diagnostic list for a well-formed model, and the semantic diagnostics for generalization cycles,
 * self-generalizations and unresolved types.
 */
class ModelValidationApiTest extends AuthenticatedApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UmlModelRepository modelRepository;
    @Autowired private ClassifierRepository classifierRepository;
    @Autowired private UmlRelationshipRepository relationshipRepository;
    @Autowired private RelationshipEndRepository relationshipEndRepository;
    @Autowired private UmlAttributeRepository attributeRepository;
    @Autowired private TypeReferenceRepository typeReferenceRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        MvcResult result = mockMvc.perform(authorized(get("/projects/{projectId}/model/validation",
                UUID.randomUUID()))).andExpect(status().isNotFound()).andReturn();

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void wellFormedModelReturnsAnEmptyDiagnosticList() throws Exception {
        UUID projectId = createProject();
        seedModel(projectId, model -> {
            Classifier person = classifier(model, ClassifierKind.CLASS, "Person");
            Classifier employee = classifier(model, ClassifierKind.CLASS, "Employee");
            generalization(model, person, employee);
        });

        JsonNode response = getValidation(projectId);

        assertThat(response.path("diagnostics").isArray()).isTrue();
        assertThat(response.path("diagnostics")).isEmpty();
    }

    @Test
    void generalizationCycleReturnsErrorDiagnosticsForEveryParticipant() throws Exception {
        UUID projectId = createProject();
        seedModel(projectId, model -> {
            Classifier person = classifier(model, ClassifierKind.CLASS, "Person");
            Classifier employee = classifier(model, ClassifierKind.CLASS, "Employee");
            generalization(model, person, employee);
            generalization(model, employee, person);
        });

        JsonNode response = getValidation(projectId);

        JsonNode diagnostics = response.path("diagnostics");
        assertThat(diagnostics).hasSize(2);
        assertThat(diagnostics.findValuesAsText("code"))
                .containsOnly("GENERALIZATION_CYCLE");
        assertThat(diagnostics.findValuesAsText("severity")).containsOnly("ERROR");
        assertThat(diagnostics.findValuesAsText("message")).allSatisfy(message ->
                assertThat(message).isNotBlank());
    }

    @Test
    void selfGeneralizationAndUnresolvedTypeAreBothReported() throws Exception {
        UUID projectId = createProject();
        seedModel(projectId, model -> {
            Classifier person = classifier(model, ClassifierKind.CLASS, "Person");
            generalization(model, person, person);
            TypeReference money = typeReferenceRepository
                    .save(TypeReference.forPrimitive(model, "Money"));
            attribute(person, money, "price");
        });

        JsonNode response = getValidation(projectId);

        JsonNode diagnostics = response.path("diagnostics");
        assertThat(diagnostics.findValuesAsText("code"))
                .containsExactlyInAnyOrder("SELF_GENERALIZATION", "UNRESOLVED_TYPE");
        JsonNode selfGeneralization = diagnosticWithCode(diagnostics, "SELF_GENERALIZATION");
        assertThat(selfGeneralization.path("severity").asText()).isEqualTo("ERROR");
        assertThat(selfGeneralization.path("feature").asText()).isEqualTo("generalization");
        assertThat(selfGeneralization.path("elementId").asText()).isNotBlank();
        JsonNode unresolvedType = diagnosticWithCode(diagnostics, "UNRESOLVED_TYPE");
        assertThat(unresolvedType.path("severity").asText()).isEqualTo("ERROR");
        assertThat(unresolvedType.path("feature").asText()).isEqualTo("price");
    }

    // ---- fixture helpers ----

    private JsonNode getValidation(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorized(get("/projects/{projectId}/model/validation",
                projectId))).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private UUID createProject() throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name",
                        "Validation API " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private interface ModelFixture {
        void build(UmlModel model);
    }

    private void seedModel(UUID projectId, ModelFixture fixture) {
        transactionTemplate.executeWithoutResult(status -> {
            UmlModel model = modelRepository.findByProjectId(projectId).orElseThrow();
            fixture.build(model);
        });
    }

    private Classifier classifier(UmlModel model, ClassifierKind kind, String name) {
        return classifierRepository.saveAndFlush(Classifier.create(model, null, kind, name, false));
    }

    /** Persists a generalization whose ordinal-1 end is the subtype and ordinal-2 end the supertype. */
    private void generalization(UmlModel model, Classifier subtype, Classifier supertype) {
        UmlRelationship relationship = relationshipRepository
                .saveAndFlush(UmlRelationship.create(model, RelationshipKind.GENERALIZATION, null));
        relationshipEndRepository.save(RelationshipEnd.create(relationship, subtype, (short) 1, null,
                MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "none"));
        relationshipEndRepository.save(RelationshipEnd.create(relationship, supertype, (short) 2, null,
                MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "none"));
    }

    private UmlAttribute attribute(Classifier classifier, TypeReference typeReference, String name) {
        return attributeRepository.saveAndFlush(UmlAttribute.create(classifier, typeReference, name,
                "property", "private", MultiplicityKind.EXACTLY_ONE, null, 0));
    }

    private JsonNode diagnosticWithCode(JsonNode diagnostics, String code) {
        for (JsonNode diagnostic : diagnostics) {
            if (diagnostic.path("code").asText().equals(code)) {
                return diagnostic;
            }
        }
        throw new AssertionError("No diagnostic with code " + code);
    }
}
