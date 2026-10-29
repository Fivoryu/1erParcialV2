package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class InvitationConcurrencyApiTest extends AuthenticatedApiTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void competingTransitionsHaveExactlyOneWinnerAndConsistentAccess() throws Exception {
        for (int iteration = 0; iteration < 3; iteration++) {
            String owner = "race-owner-" + UUID.randomUUID();
            String invitee = "race-invitee-" + UUID.randomUUID();
            bearer(invitee);
            var project = mockMvc.perform(authorizedAs(owner, post("/projects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("name", "Race", "description", "Test")))))
                    .andExpect(status().isCreated()).andReturn();
            UUID projectId = UUID.fromString(mapper.readTree(project.getResponse().getContentAsByteArray()).path("id").asText());
            var invitation = mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/invitations", projectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("username", invitee, "role", "VIEWER")))))
                    .andExpect(status().isCreated()).andReturn();
            UUID invitationId = UUID.fromString(mapper.readTree(invitation.getResponse().getContentAsByteArray()).path("id").asText());
            List<MockHttpServletRequestBuilder> requests = List.of(
                    authorizedAs(invitee, post("/projects/{projectId}/invitations/{invitationId}/accept", projectId, invitationId)),
                    authorizedAs(invitee, post("/projects/{projectId}/invitations/{invitationId}/decline", projectId, invitationId)),
                    authorizedAs(owner, post("/projects/{projectId}/invitations/{invitationId}/revoke", projectId, invitationId)));
            ExecutorService executor = Executors.newFixedThreadPool(3);
            CountDownLatch ready = new CountDownLatch(3);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Integer>> results = requests.stream().map(request -> executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    return mockMvc.perform(request).andReturn().getResponse().getStatus();
                })).toList();
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<Integer> statuses = List.of(results.get(0).get(30, TimeUnit.SECONDS),
                        results.get(1).get(30, TimeUnit.SECONDS), results.get(2).get(30, TimeUnit.SECONDS));
                assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409);
                var own = mockMvc.perform(authorizedAs(invitee, get("/invitations")))
                        .andExpect(status().isOk()).andReturn();
                String finalStatus = mapper.readTree(own.getResponse().getContentAsByteArray()).get(0).path("status").asText();
                assertThat(finalStatus).isIn("ACCEPTED", "DECLINED", "REVOKED");
                int access = mockMvc.perform(authorizedAs(invitee, get("/projects/{projectId}/model", projectId)))
                        .andReturn().getResponse().getStatus();
                assertThat(access).isEqualTo(finalStatus.equals("ACCEPTED") ? 200 : 403);
                assertThat(statuses.get(0) == 200).isEqualTo(finalStatus.equals("ACCEPTED"));
                assertThat(statuses.get(1) == 200).isEqualTo(finalStatus.equals("DECLINED"));
                assertThat(statuses.get(2) == 200).isEqualTo(finalStatus.equals("REVOKED"));
            } finally {
                start.countDown();
                executor.shutdownNow();
            }
        }
    }
}
