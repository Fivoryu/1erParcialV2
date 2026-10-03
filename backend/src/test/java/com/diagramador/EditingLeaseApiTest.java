package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "diagrammer.collaboration.editing-lease-ttl=PT2S")
@AutoConfigureMockMvc
class EditingLeaseApiTest extends AuthenticatedApiTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    EditingLeaseApiTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @Test
    void leaseCanBeAcquiredRenewedReadReleasedAndReadAsOneEnvelope() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        UUID targetId = UUID.randomUUID();
        MockHttpServletRequestBuilder acquire = post(leasePath(projectId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetType", "CLASSIFIER", "targetId", targetId)));

        JsonNode acquired = leaseResponse(mockMvc.perform(authorized(acquire))
                .andExpect(status().isOk()).andReturn());
        assertThat(acquired.path("lease").path("modelId").asText()).isEqualTo(modelId.toString());
        assertThat(acquired.path("lease").path("editorDisplayName").asText()).isEqualTo("Integration Test");
        assertThat(acquired.path("lease").path("targetType").asText()).isEqualTo("CLASSIFIER");
        assertThat(acquired.path("lease").path("targetId").asText()).isEqualTo(targetId.toString());

        JsonNode renewed = leaseResponse(mockMvc.perform(authorized(post(leasePath(projectId) + "/renew")))
                .andExpect(status().isOk()).andReturn());
        assertThat(renewed.path("lease").path("modelId").asText()).isEqualTo(modelId.toString());

        JsonNode current = leaseResponse(mockMvc.perform(authorized(get(leasePath(projectId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(current.path("lease").path("editorUserId").asText()).isEqualTo(
                renewed.path("lease").path("editorUserId").asText());

        JsonNode released = leaseResponse(mockMvc.perform(authorized(delete(leasePath(projectId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(released.path("lease").isNull()).isTrue();
        JsonNode empty = leaseResponse(mockMvc.perform(authorized(get(leasePath(projectId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(empty.path("lease").isNull()).isTrue();
    }

    @Test
    void acquireAcceptsAnOmittedOptionalBody() throws Exception {
        UUID projectId = createProject();

        JsonNode response = leaseResponse(mockMvc.perform(authorized(post(leasePath(projectId))))
                .andExpect(status().isOk()).andReturn());

        assertThat(response.path("lease").path("targetType").isNull()).isTrue();
        assertThat(response.path("lease").path("targetId").isNull()).isTrue();
    }

    @Test
    void failedRenewalUsesAnEmptyOrRecordedConflictDetailsListAsAppropriate() throws Exception {
        UUID projectId = createProject();

        MvcResult unleasedRenewal = mockMvc.perform(authorized(post(leasePath(projectId) + "/renew")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode unleasedError = objectMapper.readTree(unleasedRenewal.getResponse().getContentAsByteArray());
        assertThat(unleasedError.path("code").asText()).isEqualTo("EXPORT_BLOCKED_EDITING");
        assertThat(unleasedError.path("details").size()).isZero();

        JsonNode acquired = leaseResponse(mockMvc.perform(authorized(post(leasePath(projectId))))
                .andExpect(status().isOk()).andReturn());
        Instant expiresAt = Instant.parse(acquired.path("lease").path("expiresAt").asText());
        Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> !Instant.now().isBefore(expiresAt));

        JsonNode expired = leaseResponse(mockMvc.perform(authorized(get(leasePath(projectId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(expired.path("lease").isObject()).isTrue();
        MvcResult expiredRenewal = mockMvc.perform(authorized(post(leasePath(projectId) + "/renew")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode expiredError = objectMapper.readTree(expiredRenewal.getResponse().getContentAsByteArray());
        assertThat(expiredError.path("code").asText()).isEqualTo("EXPORT_BLOCKED_EDITING");
        assertConflictKeys(expiredError.path("details").get(0));
        assertThat(expiredError.path("details").get(0).path("reclaimable").asBoolean()).isTrue();
        assertThat(expiredError.path("details").get(0).path("retryAfterSeconds").asInt()).isZero();
    }

    @Test
    void acquiringOverAnExpiredForeignLeaseReturnsOrderedReclaimableConflictAndCanBeReclaimed()
            throws Exception {
        UUID projectId = createProject();
        String holder = "lease-holder-" + UUID.randomUUID();
        bearer(holder);
        addMembership(holder, projectId, ProjectMemberRole.EDITOR);
        MockHttpServletRequestBuilder acquireAsHolder = post(leasePath(projectId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetType", "CLASSIFIER", "targetId", UUID.randomUUID())));

        JsonNode acquired = leaseResponse(mockMvc.perform(authorizedAs(holder, acquireAsHolder))
                .andExpect(status().isOk()).andReturn());
        Instant expiresAt = Instant.parse(acquired.path("lease").path("expiresAt").asText());
        Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> !Instant.now().isBefore(expiresAt));

        MockHttpServletRequestBuilder acquireAsOwner = post(leasePath(projectId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetType", "OPERATION", "targetId", UUID.randomUUID())));
        MvcResult conflictResult = mockMvc.perform(authorized(acquireAsOwner))
                .andExpect(status().isConflict()).andReturn();
        JsonNode conflict = objectMapper.readTree(conflictResult.getResponse().getContentAsByteArray());
        JsonNode detail = conflict.path("details").get(0);
        assertThat(conflict.path("code").asText()).isEqualTo("EXPORT_BLOCKED_EDITING");
        assertConflictKeys(detail);
        assertThat(detail.path("editorDisplayName").asText()).isEqualTo("Integration Test");
        assertThat(detail.path("targetType").asText()).isEqualTo("CLASSIFIER");
        assertThat(detail.path("reclaimable").asBoolean()).isTrue();
        assertThat(detail.path("retryAfterSeconds").asInt()).isZero();

        MockHttpServletRequestBuilder reclaim = post(leasePath(projectId) + "/reclaim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetType", "OPERATION", "targetId", UUID.randomUUID())));
        JsonNode reclaimed = leaseResponse(mockMvc.perform(authorized(reclaim))
                .andExpect(status().isOk()).andReturn());
        assertThat(reclaimed.path("lease").path("targetType").asText()).isEqualTo("OPERATION");
        assertThat(reclaimed.path("lease").path("editorUserId").asText()).isNotEqualTo(
                acquired.path("lease").path("editorUserId").asText());
    }

    @Test
    void partialTargetPairIsRejectedAsInvalidPayload() throws Exception {
        UUID projectId = createProject();
        MockHttpServletRequestBuilder acquire = post(leasePath(projectId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetType", "CLASSIFIER")));

        MvcResult result = mockMvc.perform(authorized(acquire))
                .andExpect(status().isBadRequest()).andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("code").asText()).isEqualTo("INVALID_PAYLOAD");
    }

    @Test
    void unknownTargetTypeUsesTheProjectsInvalidPayloadEnvelope() throws Exception {
        UUID projectId = createProject();
        MockHttpServletRequestBuilder acquire = post(leasePath(projectId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetType", "NOT_A_TARGET", "targetId", UUID.randomUUID())));

        MvcResult result = mockMvc.perform(authorized(acquire))
                .andExpect(status().isBadRequest()).andReturn();

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(error.path("message").asText()).isNotBlank();
    }

    @Test
    void nonEditorCannotReadOrChangeAnEditingLease() throws Exception {
        UUID projectId = createProject();
        String viewer = "lease-viewer-" + UUID.randomUUID();
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);

        MvcResult result = mockMvc.perform(authorizedAs(viewer, get(leasePath(projectId))))
                .andExpect(status().isForbidden()).andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("code").asText()).isEqualTo("FORBIDDEN");
    }

    private UUID createProject() throws Exception {
        MockHttpServletRequestBuilder create = post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Editing Lease " + UUID.randomUUID())));
        MvcResult result = mockMvc.perform(authorized(create)).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private UUID modelId(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorized(get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private JsonNode leaseResponse(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.has("lease")).isTrue();
        return response;
    }

    private void assertConflictKeys(JsonNode detail) {
        List<String> keys = new ArrayList<>();
        detail.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly(
                "editorDisplayName", "targetType", "targetId", "reclaimable", "retryAfterSeconds");
    }

    private static String leasePath(UUID projectId) {
        return "/projects/" + projectId + "/editing-lease";
    }

    private byte[] json(Object value) throws Exception {
        return objectMapper.writeValueAsBytes(value);
    }
}
