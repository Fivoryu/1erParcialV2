package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** A platform administrator can see and open every project, but gets no edit rights from it. */
class PlatformAdminProjectsApiTest extends AuthenticatedApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private boolean lists(String username, UUID projectId) throws Exception {
        var body = mvc.perform(authorizedAs(username, get("/projects"))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        for (JsonNode item : json.readTree(body)) {
            if (projectId.toString().equals(item.path("id").asText())) return true;
        }
        return false;
    }

    @Test
    void onlyAFlaggedAdministratorSeesProjectsTheyDoNotBelongTo() throws Exception {
        String creator = "admin-creator-" + UUID.randomUUID();
        String outsider = "admin-outsider-" + UUID.randomUUID();
        bearer(creator);
        bearer(outsider);
        UUID project = UUID.fromString(json.readTree(mvc.perform(authorizedAs(creator, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Private " + UUID.randomUUID())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText());

        assertThat(lists(outsider, project)).isFalse();
        mvc.perform(authorizedAs(outsider, get("/projects/{id}", project))).andExpect(status().isForbidden());

        jdbc.update("update diagrammer.user_account set is_platform_admin = true where user_id = ?", userIdFor(outsider));

        assertThat(lists(outsider, project)).isTrue();
        mvc.perform(authorizedAs(outsider, get("/projects/{id}", project))).andExpect(status().isOk());
        mvc.perform(authorizedAs(outsider, post("/projects/{id}/vcs/branches", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "x", "fromBranch", "main")))))
                .andExpect(status().isForbidden());
        mvc.perform(authorizedAs(outsider, get("/projects/{id}/vcs/merge-requests", project))).andExpect(status().isOk());
        JsonNode model = json.readTree(mvc.perform(authorizedAs(outsider, get("/projects/{id}/model", project)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Map<String, Object> envelope = new java.util.HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("projectId", project);
        envelope.put("modelId", UUID.fromString(model.path("id").asText()));
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", java.util.List.of());
        envelope.put("preconditions", java.util.List.of());
        envelope.put("payload", Map.of("id", UUID.randomUUID(), "name", "Nope", "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        mvc.perform(authorizedAs(outsider, post("/projects/{id}/commands", project)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(envelope))))
                .andExpect(status().isForbidden());
    }
}
