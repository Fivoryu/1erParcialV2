package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class T5ProjectApiTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SEED_OWNER_SUBJECT = "seed-owner-local";
    private static final String EDITOR_ORIGIN = "http://localhost:5173";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    T5ProjectApiTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @Test
    void creatingProjectReturnsCreatedResourceAndListContainsItInStableOrder() throws Exception {
        JsonNode created = createProject("API create " + UUID.randomUUID(), "Created over HTTP");
        UUID projectId = UUID.fromString(created.path("id").asText());

        assertThat(created.path("name").asText()).startsWith("API create ");
        assertThat(created.path("description").asText()).isEqualTo("Created over HTTP");
        assertThat(created.path("id").asText()).isEqualTo(projectId.toString());

        JsonNode list = getJson("/projects");
        assertThat(findById(list, projectId.toString())).isNotNull();
        List<ProjectSummary> actual = new ArrayList<>();
        list.forEach(project -> actual.add(new ProjectSummary(project.path("name").asText(),
                project.path("id").asText())));
        List<ProjectSummary> expected = actual.stream()
                .sorted(Comparator.comparing(ProjectSummary::name).thenComparing(ProjectSummary::id))
                .toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void blankProjectNameReturnsBadRequestWithTheTransportErrorShape() throws Exception {
        MvcResult result = mockMvc.perform(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = assertErrorShape(result);
        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(error.path("details").toString()).contains("name");
    }

    @Test
    void malformedJsonReturnsBadRequestWithTheTransportErrorShape() throws Exception {
        MvcResult result = mockMvc.perform(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = assertErrorShape(result);
        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
    }

    @Test
    void unknownProjectReturnsNotFoundWithTheTransportErrorShape() throws Exception {
        MvcResult result = mockMvc.perform(get("/projects/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode error = assertErrorShape(result);
        assertThat(error.path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformedProjectPathIdReturnsBadRequestWithTheTransportErrorShape() throws Exception {
        MvcResult result = mockMvc.perform(get("/projects/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = assertErrorShape(result);
        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
    }

    @Test
    void freshProjectModelHasRevisionOneDefaultViewAndNonNullEmptyCollections() throws Exception {
        UUID projectId = UUID.fromString(createProject("Fresh model " + UUID.randomUUID(), null)
                .path("id").asText());

        JsonNode model = getJson("/projects/{id}/model", projectId);

        assertThat(model.path("projectId").asText()).isEqualTo(projectId.toString());
        assertThat(model.path("id").asText()).isNotBlank();
        assertThat(model.path("revision").asLong()).isEqualTo(1L);
        assertThat(model.path("views").isArray()).isTrue();
        assertThat(model.path("views")).hasSize(1);
        JsonNode view = model.path("views").get(0);
        assertThat(view.path("id").asText()).isNotBlank();
        assertThat(view.path("is_default").asBoolean()).isTrue();
        assertThat(view.path("element_geometry").isArray()).isTrue();
        assertThat(view.path("element_geometry")).isEmpty();
        assertThat(view.path("connector_path").isArray()).isTrue();
        assertThat(view.path("connector_path")).isEmpty();
        assertThat(model.path("classifiers").isArray()).isTrue();
        assertThat(model.path("classifiers")).isEmpty();
        assertThat(model.path("relationships").isArray()).isTrue();
        assertThat(model.path("relationships")).isEmpty();
    }

    @Test
    void fullModelJourneyOverHttpPreservesIdsMembersRelationshipEndsAndLayoutSeparation() throws Exception {
        UUID projectId = UUID.fromString(createProject("HTTP journey " + UUID.randomUUID(), null)
                .path("id").asText());
        JsonNode initialModel = getJson("/projects/{id}/model", projectId);
        UUID modelId = UUID.fromString(initialModel.path("id").asText());
        UUID viewId = UUID.fromString(initialModel.path("views").get(0).path("id").asText());
        UUID firstClassifierId = UUID.randomUUID();
        UUID secondClassifierId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID parameterId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();

        postCommand(projectId, projectId, modelId, 1L, "classifier.create", List.of(),
                Map.of("id", firstClassifierId, "name", "Customer", "kind", "CLASS"));
        postCommand(projectId, projectId, modelId, 2L, "classifier.create", List.of(),
                Map.of("id", secondClassifierId, "name", "Order", "kind", "CLASS"));
        postCommand(projectId, projectId, modelId, 3L, "attribute.add",
                List.of(target("classifier", firstClassifierId)),
                Map.of("id", attributeId, "name", "name", "type", Map.of("primitiveName", "String"),
                        "visibility", "PRIVATE", "ordinal", 0));
        postCommand(projectId, projectId, modelId, 4L, "operation.add",
                List.of(target("classifier", firstClassifierId)),
                Map.of("id", operationId, "name", "rename", "returnType", Map.of("primitiveName", "Boolean"),
                        "ordinal", 0));
        postCommand(projectId, projectId, modelId, 5L, "parameter.add",
                List.of(target("operation", operationId)),
                Map.of("id", parameterId, "name", "newName", "type", Map.of("primitiveName", "String"),
                        "direction", "IN", "ordinal", 0));
        postCommand(projectId, projectId, modelId, 6L, "constraint.add",
                List.of(target("classifier", firstClassifierId)),
                Map.of("id", constraintId, "ownerType", "CLASSIFIER", "ownerId", firstClassifierId,
                        "expression", "name <> ''", "severity", "ERROR"));

        Map<String, Object> sourceEnd = Map.of("classifierId", firstClassifierId, "role", "customer",
                "multiplicity", "EXACTLY_ONE", "navigability", "NAVIGABLE");
        Map<String, Object> targetEnd = Map.of("classifierId", secondClassifierId, "role", "orders",
                "multiplicity", "ZERO_OR_MANY", "navigability", "NOT_NAVIGABLE");
        Map<String, Object> relationshipPayload = new HashMap<>();
        relationshipPayload.put("id", relationshipId);
        relationshipPayload.put("kind", "ASSOCIATION");
        relationshipPayload.put("name", "places");
        relationshipPayload.put("source", sourceEnd);
        relationshipPayload.put("target", targetEnd);
        relationshipPayload.put("waypoints", List.of(Map.of("x", 240, "y", 120), Map.of("x", 360, "y", 180)));
        postCommand(projectId, projectId, modelId, 7L, "relationship.create",
                List.of(target("classifier", firstClassifierId), target("classifier", secondClassifierId)),
                relationshipPayload);

        JsonNode beforeGeometry = getJson("/projects/{id}/model", projectId);
        assertThat(beforeGeometry.path("revision").asLong()).isEqualTo(8L);
        String classifiersBeforeGeometry = beforeGeometry.path("classifiers").toString();

        postCommand(projectId, projectId, modelId, 8L, "geometry.set",
                List.of(target("classifier", firstClassifierId)),
                Map.of("targetType", "CLASSIFIER", "viewId", viewId, "targetId", firstClassifierId,
                        "x", 120, "y", 80, "w", 240, "h", 160, "zOrder", 2));

        JsonNode model = getJson("/projects/{id}/model", projectId);
        assertThat(model.path("id").asText()).isEqualTo(modelId.toString());
        assertThat(model.path("projectId").asText()).isEqualTo(projectId.toString());
        assertThat(model.path("revision").asLong()).isEqualTo(9L);
        assertThat(model.path("classifiers").toString()).isEqualTo(classifiersBeforeGeometry);
        assertThat(model.path("classifiers")).hasSize(2);

        JsonNode firstClassifier = findById(model.path("classifiers"), firstClassifierId.toString());
        JsonNode secondClassifier = findById(model.path("classifiers"), secondClassifierId.toString());
        assertThat(firstClassifier).isNotNull();
        assertThat(firstClassifier.path("name").asText()).isEqualTo("Customer");
        assertThat(firstClassifier.path("kind").asText()).isEqualTo("CLASS");
        assertThat(firstClassifier.path("isAbstract").asBoolean()).isFalse();
        assertThat(firstClassifier.path("namespaceId").isNull()).isTrue();
        assertThat(secondClassifier).isNotNull();
        assertThat(secondClassifier.path("attributes")).isEmpty();
        assertThat(secondClassifier.path("operations")).isEmpty();
        assertThat(secondClassifier.path("constraints")).isEmpty();

        JsonNode attribute = firstClassifier.path("attributes").get(0);
        assertThat(attribute.path("id").asText()).isEqualTo(attributeId.toString());
        assertThat(attribute.path("name").asText()).isEqualTo("name");
        assertThat(attribute.path("type").asText()).isEqualTo("String");
        assertThat(attribute.path("ordinal").asInt()).isZero();

        JsonNode operation = firstClassifier.path("operations").get(0);
        assertThat(operation.path("id").asText()).isEqualTo(operationId.toString());
        assertThat(operation.path("name").asText()).isEqualTo("rename");
        assertThat(operation.path("returnType").asText()).isEqualTo("Boolean");
        assertThat(operation.path("parameters")).hasSize(1);
        assertThat(operation.path("parameters").get(0).path("id").asText()).isEqualTo(parameterId.toString());
        assertThat(operation.path("parameters").get(0).path("type").asText()).isEqualTo("String");

        assertThat(firstClassifier.path("constraints")).hasSize(1);
        assertThat(firstClassifier.path("constraints").get(0).path("id").asText()).isEqualTo(constraintId.toString());
        assertThat(firstClassifier.path("constraints").get(0).path("expression").asText()).isEqualTo("name <> ''");

        assertThat(model.path("relationships")).hasSize(1);
        JsonNode relationship = findById(model.path("relationships"), relationshipId.toString());
        assertThat(relationship.path("kind").asText()).isEqualTo("ASSOCIATION");
        assertThat(relationship.path("name").asText()).isEqualTo("places");
        assertThat(relationship.path("ends")).hasSize(2);
        assertThat(relationship.path("constraints")).isEmpty();
        JsonNode firstEnd = relationship.path("ends").get(0);
        JsonNode secondEnd = relationship.path("ends").get(1);
        assertThat(firstEnd.path("classifierId").asText()).isEqualTo(firstClassifierId.toString());
        assertThat(firstEnd.path("role").asText()).isEqualTo("customer");
        assertThat(firstEnd.path("multiplicity").asText()).isEqualTo("EXACTLY_ONE");
        assertThat(firstEnd.path("navigability").asText()).isEqualTo("NAVIGABLE");
        assertThat(firstEnd.path("aggregationKind").asText()).isEqualTo("NONE");
        assertThat(secondEnd.path("classifierId").asText()).isEqualTo(secondClassifierId.toString());
        assertThat(secondEnd.path("role").asText()).isEqualTo("orders");
        assertThat(secondEnd.path("multiplicity").asText()).isEqualTo("ZERO_OR_MANY");
        assertThat(secondEnd.path("navigability").asText()).isEqualTo("NOT_NAVIGABLE");
        assertThat(secondEnd.path("aggregationKind").asText()).isEqualTo("NONE");

        JsonNode view = findById(model.path("views"), viewId.toString());
        assertThat(view.path("element_geometry")).hasSize(1);
        JsonNode geometry = view.path("element_geometry").get(0);
        assertThat(geometry.path("targetId").asText()).isEqualTo(firstClassifierId.toString());
        assertThat(geometry.path("x").asInt()).isEqualTo(120);
        assertThat(geometry.path("y").asInt()).isEqualTo(80);
        assertThat(geometry.path("w").asInt()).isEqualTo(240);
        assertThat(geometry.path("h").asInt()).isEqualTo(160);
        assertThat(view.path("connector_path")).hasSize(2);
        assertThat(view.path("connector_path").get(0).path("relationshipId").asText())
                .isEqualTo(relationshipId.toString());
        assertThat(view.path("connector_path").get(0).path("ordinal").asInt()).isZero();
        assertThat(view.path("connector_path").get(1).path("ordinal").asInt()).isEqualTo(1);
    }

    @Test
    void constraintUpdatePersistsExpressionAndEverySupportedSeverityAcrossModelReloads() throws Exception {
        UUID projectId = UUID.fromString(createProject("Constraint update " + UUID.randomUUID(), null)
                .path("id").asText());
        JsonNode initialModel = getJson("/projects/{id}/model", projectId);
        UUID modelId = UUID.fromString(initialModel.path("id").asText());
        UUID classifierId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        postCommand(projectId, projectId, modelId, 1L, "classifier.create", List.of(),
                Map.of("id", classifierId, "name", "Account", "kind", "CLASS"));
        postCommand(projectId, projectId, modelId, 2L, "constraint.add", List.of(target("classifier", classifierId)),
                Map.of("id", constraintId, "ownerType", "CLASSIFIER", "ownerId", classifierId,
                        "expression", "self.name <> ''", "severity", "ERROR"));

        postCommand(projectId, projectId, modelId, 3L, "constraint.update", List.of(target("constraint", constraintId)),
                Map.of("expression", "self.name.size() > 0"));
        JsonNode afterExpression = findById(getJson("/projects/{id}/model", projectId).path("classifiers"),
                classifierId.toString()).path("constraints").get(0);
        assertThat(afterExpression.path("expression").asText()).isEqualTo("self.name.size() > 0");
        assertThat(afterExpression.path("severity").asText()).isEqualTo("ERROR");

        long revision = 4L;
        for (String severity : List.of("WARNING", "INFO", "ERROR")) {
            postCommand(projectId, projectId, modelId, revision++, "constraint.update",
                    List.of(target("constraint", constraintId)), Map.of("severity", severity));
            JsonNode persisted = findById(getJson("/projects/{id}/model", projectId).path("classifiers"),
                    classifierId.toString()).path("constraints").get(0);
            assertThat(persisted.path("expression").asText()).isEqualTo("self.name.size() > 0");
            assertThat(persisted.path("severity").asText()).isEqualTo(severity);
        }
    }

    @Test
    void relationshipOwnedConstraintCanBeCreatedReadAndEdited() throws Exception {
        UUID projectId = UUID.fromString(createProject("Relationship constraint " + UUID.randomUUID(), null)
                .path("id").asText());
        JsonNode initialModel = getJson("/projects/{id}/model", projectId);
        UUID modelId = UUID.fromString(initialModel.path("id").asText());
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        postCommand(projectId, projectId, modelId, 1L, "classifier.create", List.of(),
                Map.of("id", sourceId, "name", "Customer", "kind", "CLASS"));
        postCommand(projectId, projectId, modelId, 2L, "classifier.create", List.of(),
                Map.of("id", targetId, "name", "Order", "kind", "CLASS"));
        Map<String, Object> relationshipPayload = new HashMap<>();
        relationshipPayload.put("id", relationshipId);
        relationshipPayload.put("kind", "ASSOCIATION");
        relationshipPayload.put("name", "places");
        relationshipPayload.put("source", Map.of("classifierId", sourceId));
        relationshipPayload.put("target", Map.of("classifierId", targetId));
        relationshipPayload.put("waypoints", List.of());
        postCommand(projectId, projectId, modelId, 3L, "relationship.create",
                List.of(target("classifier", sourceId), target("classifier", targetId)), relationshipPayload);
        postCommand(projectId, projectId, modelId, 4L, "constraint.add", List.of(target("relationship", relationshipId)),
                Map.of("id", constraintId, "ownerType", "RELATIONSHIP", "ownerId", relationshipId,
                        "expression", "customer.orders->notEmpty()", "severity", "WARNING"));

        JsonNode relationshipConstraint = findById(getJson("/projects/{id}/model", projectId).path("relationships"),
                relationshipId.toString()).path("constraints").get(0);
        assertThat(relationshipConstraint.path("id").asText()).isEqualTo(constraintId.toString());
        assertThat(relationshipConstraint.path("expression").asText()).isEqualTo("customer.orders->notEmpty()");
        assertThat(relationshipConstraint.path("severity").asText()).isEqualTo("WARNING");

        postCommand(projectId, projectId, modelId, 5L, "constraint.update", List.of(target("constraint", constraintId)),
                Map.of("expression", "customer.orders->size() > 0", "severity", "INFO"));
        JsonNode edited = findById(getJson("/projects/{id}/model", projectId).path("relationships"),
                relationshipId.toString()).path("constraints").get(0);
        assertThat(edited.path("expression").asText()).isEqualTo("customer.orders->size() > 0");
        assertThat(edited.path("severity").asText()).isEqualTo("INFO");

        Map<String, Object> cascadeCount = new HashMap<>();
        cascadeCount.put("kind", "cascade_count");
        cascadeCount.put("expect", 4);
        cascadeCount.put("scope", null);
        MvcResult deleted = postEnvelope(projectId, projectId, modelId, UUID.randomUUID(), 6L,
                "relationship.delete", List.of(target("relationship", relationshipId)), Map.of(), List.of(cascadeCount))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(deleted.getResponse().getContentAsByteArray()).path("status").asText())
                .isEqualTo("ACCEPTED");
        assertThat(findById(getJson("/projects/{id}/model", projectId).path("relationships"),
                relationshipId.toString())).isNull();
    }

    @Test
    void constraintUpdateRejectsAnUnknownSeverityNamingItAndAnUnknownConstraintNamingItsId() throws Exception {
        UUID projectId = UUID.fromString(createProject("Rejected constraint update " + UUID.randomUUID(), null)
                .path("id").asText());
        JsonNode initialModel = getJson("/projects/{id}/model", projectId);
        UUID modelId = UUID.fromString(initialModel.path("id").asText());
        UUID classifierId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        UUID unknownConstraintId = UUID.randomUUID();
        postCommand(projectId, projectId, modelId, 1L, "classifier.create", List.of(),
                Map.of("id", classifierId, "name", "Account", "kind", "CLASS"));
        postCommand(projectId, projectId, modelId, 2L, "constraint.add", List.of(target("classifier", classifierId)),
                Map.of("id", constraintId, "ownerType", "CLASSIFIER", "ownerId", classifierId,
                        "expression", "self.name <> ''"));

        MvcResult invalidSeverityResult = postEnvelope(projectId, projectId, modelId, UUID.randomUUID(), 3L,
                "constraint.update", List.of(target("constraint", constraintId)), Map.of("severity", "TRACE"))
                .andExpect(status().isOk()).andReturn();
        JsonNode invalidSeverity = objectMapper.readTree(invalidSeverityResult.getResponse().getContentAsByteArray());
        assertThat(invalidSeverity.path("status").asText()).isEqualTo("REJECTED");
        assertThat(invalidSeverity.path("diagnostics").get(0).path("message").asText()).contains("TRACE");

        MvcResult unknownConstraintResult = postEnvelope(projectId, projectId, modelId, UUID.randomUUID(), 3L,
                "constraint.update", List.of(target("constraint", unknownConstraintId)),
                Map.of("expression", "missing"))
                .andExpect(status().isOk()).andReturn();
        JsonNode unknownConstraint = objectMapper.readTree(unknownConstraintResult.getResponse().getContentAsByteArray());
        assertThat(unknownConstraint.path("status").asText()).isEqualTo("REJECTED");
        assertThat(unknownConstraint.path("diagnostics").get(0).path("message").asText())
                .contains(unknownConstraintId.toString());
    }

    @Test
    void rejectedCommandReturnsHttp200AndKeepsItsErrorCodeInDiagnostics() throws Exception {
        UUID projectId = UUID.fromString(createProject("Rejected command " + UUID.randomUUID(), null)
                .path("id").asText());
        JsonNode model = getJson("/projects/{id}/model", projectId);
        UUID modelId = UUID.fromString(model.path("id").asText());
        UUID operationId = UUID.randomUUID();

        MvcResult result = postEnvelope(projectId, projectId, modelId, operationId, 1L, "classifier.create",
                List.of(), Map.of("id", UUID.randomUUID(), "name", "Unsupported", "kind", "ENUMERATION"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode commandResult = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(commandResult.path("operationId").asText()).isEqualTo(operationId.toString());
        assertThat(commandResult.path("status").asText()).isEqualTo("REJECTED");
        assertThat(commandResult.path("diagnostics").get(0).path("code").asText())
                .isEqualTo("PRECONDITION_FAILED");
    }

    @Test
    void envelopeProjectIdMismatchReturnsBadRequestWithOperationId() throws Exception {
        UUID pathProjectId = UUID.fromString(createProject("Mismatched command " + UUID.randomUUID(), null)
                .path("id").asText());
        JsonNode model = getJson("/projects/{id}/model", pathProjectId);
        UUID modelId = UUID.fromString(model.path("id").asText());
        UUID operationId = UUID.randomUUID();

        MvcResult result = postEnvelope(pathProjectId, UUID.randomUUID(), modelId, operationId, 1L,
                "classifier.create", List.of(),
                Map.of("id", UUID.randomUUID(), "name", "Mismatch", "kind", "CLASS"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = assertErrorShape(result);
        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(error.path("operationId").asText()).isEqualTo(operationId.toString());
    }

    @Test
    void corsAllowsOnlyTheViteDevelopmentOriginForApiMethods() throws Exception {
        mockMvc.perform(options("/projects")
                .header("Origin", EDITOR_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", EDITOR_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")));
    }

    private JsonNode createProject(String name, String description) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", description);
        MvcResult result = mockMvc.perform(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode getJson(String path, Object... pathVariables) throws Exception {
        MvcResult result = mockMvc.perform(get(path, pathVariables)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postCommand(UUID pathProjectId, UUID envelopeProjectId, UUID modelId, long baseRevision,
            String type, List<Map<String, Object>> targets, Map<String, Object> payload) throws Exception {
        MvcResult result = postEnvelope(pathProjectId, envelopeProjectId, modelId, UUID.randomUUID(), baseRevision,
                type, targets, payload)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode commandResult = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(commandResult.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(commandResult.path("resultRevision").asLong()).isEqualTo(baseRevision + 1L);
        return commandResult;
    }

    private org.springframework.test.web.servlet.ResultActions postEnvelope(UUID pathProjectId, UUID envelopeProjectId,
            UUID modelId, UUID operationId, long baseRevision, String type, List<Map<String, Object>> targets,
            Map<String, Object> payload) throws Exception {
        return postEnvelope(pathProjectId, envelopeProjectId, modelId, operationId, baseRevision, type, targets,
                payload, List.of());
    }

    private org.springframework.test.web.servlet.ResultActions postEnvelope(UUID pathProjectId, UUID envelopeProjectId,
            UUID modelId, UUID operationId, long baseRevision, String type, List<Map<String, Object>> targets,
            Map<String, Object> payload, List<Map<String, Object>> preconditions) throws Exception {
        Map<String, Object> author = Map.of("userId", SEED_OWNER_ID, "externalSubject", SEED_OWNER_SUBJECT);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", operationId);
        envelope.put("type", type);
        envelope.put("author", author);
        envelope.put("projectId", envelopeProjectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", baseRevision);
        envelope.put("targets", targets);
        envelope.put("preconditions", preconditions);
        envelope.put("payload", payload);
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        return mockMvc.perform(post("/projects/{id}/commands", pathProjectId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope)));
    }

    private JsonNode assertErrorShape(MvcResult result) throws Exception {
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.has("code")).isTrue();
        assertThat(error.path("code").isTextual()).isTrue();
        assertThat(error.has("message")).isTrue();
        assertThat(error.path("message").isTextual()).isTrue();
        assertThat(error.has("details")).isTrue();
        assertThat(error.path("details").isArray()).isTrue();
        assertThat(error.has("operationId")).isTrue();
        return error;
    }

    private static Map<String, Object> target(String scope, UUID id) {
        return Map.of("scope", scope, "id", id);
    }

    private static JsonNode findById(JsonNode array, String id) {
        for (JsonNode candidate : array) {
            if (id.equals(candidate.path("id").asText())) {
                return candidate;
            }
        }
        return null;
    }

    private record ProjectSummary(String name, String id) {
    }
}
