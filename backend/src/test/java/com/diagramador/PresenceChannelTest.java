package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.collaboration.PresenceEntry;
import com.diagramador.collaboration.PresenceRegistry;
import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class PresenceChannelTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PresenceRegistry presenceRegistry;

    private final List<MvcResult> openStreams = new ArrayList<>();

    @AfterEach
    void completeOpenStreams() {
        for (MvcResult result : openStreams) {
            if (result.getRequest().isAsyncStarted()) {
                result.getRequest().getAsyncContext().complete();
            }
        }
        openStreams.clear();
    }

    @Test
    void bothSubscribersReceiveTheFullColoredSnapshotWithoutSessionIds() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String member = username("member");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);

        MvcResult ownerStream = openStream(owner, projectId);
        MvcResult memberStream = openStream(member, projectId);
        UUID ownerId = userIdFor(owner);
        UUID memberId = userIdFor(member);

        assertFullSnapshot(latestPresenceEvent(ownerStream), ownerId, memberId);
        assertFullSnapshot(latestPresenceEvent(memberStream), ownerId, memberId);
    }

    @Test
    void completingOneStreamRemovesItsUserFromTheNextPublishedSnapshot() throws Exception {
        String survivor = username("remaining");
        UUID projectId = createProject(survivor);
        String leaver = username("leaver");
        bearer(leaver);
        addMembership(leaver, projectId, ProjectMemberRole.VIEWER);

        MvcResult survivorStream = openStream(survivor, projectId);
        assertThat(presenceEventCount(survivorStream)).isEqualTo(1);
        MvcResult leaverStream = openStream(leaver, projectId);
        assertThat(presenceEventCount(survivorStream)).isEqualTo(2);
        UUID survivorId = userIdFor(survivor);
        UUID leaverId = userIdFor(leaver);

        leaverStream.getRequest().getAsyncContext().complete();
        assertThat(presenceEventCount(survivorStream)).isEqualTo(3);
        JsonNode completionEvent = latestPresenceEvent(survivorStream);
        JsonNode completionSnapshot = completionEvent.path("payload").path("presence");
        assertThat(completionSnapshot.size()).isEqualTo(1);
        assertThat(findPerson(completionSnapshot, survivorId)).isNotNull();
        assertThat(completionSnapshot.findValuesAsText("userId")).doesNotContain(leaverId.toString());

        assertThat(presenceRegistry.snapshot(projectId))
                .extracting(PresenceEntry::userId)
                .doesNotContain(leaverId);

        mockMvc.perform(authorizedAs(leaver, touchRequest(projectId, UUID.randomUUID())))
                .andExpect(status().isNoContent());

        JsonNode presenceEvent = latestPresenceEvent(survivorStream);
        JsonNode snapshot = presenceEvent.path("payload").path("presence");
        assertThat(snapshot.size()).isEqualTo(1);
        assertThat(findPerson(snapshot, survivorId)).isNotNull();
        assertThat(snapshot.findValuesAsText("userId")).doesNotContain(leaverId.toString());
    }

    @Test
    void oneMemberSeesAnotherMembersTouchedTargetAndColor() throws Exception {
        String owner = username("watcher");
        UUID projectId = createProject(owner);
        String member = username("toucher");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);
        MvcResult watcherStream = openStream(owner, projectId);
        openStream(member, projectId);

        UUID memberId = userIdFor(member);
        UUID targetElementId = UUID.randomUUID();
        mockMvc.perform(authorizedAs(member, touchRequest(projectId, targetElementId)))
                .andExpect(status().isNoContent());

        JsonNode touchedSnapshot = latestPresenceEvent(watcherStream).path("payload").path("presence");
        JsonNode touchedMember = findPerson(touchedSnapshot, memberId);
        assertThat(touchedSnapshot.size()).isEqualTo(2);
        assertThat(touchedMember).isNotNull();
        assertThat(touchedMember.path("targetElementId").asText()).isEqualTo(targetElementId.toString());
        assertThat(touchedMember.path("color").asText()).isEqualTo(presenceRegistry.colorFor(memberId));
        assertThat(latestPresenceEvent(watcherStream).toString()).doesNotContain("sessionId");

        mockMvc.perform(authorizedAs(member, touchRequest(projectId, null)))
                .andExpect(status().isNoContent());
        JsonNode clearedMember = findPerson(
                latestPresenceEvent(watcherStream).path("payload").path("presence"), memberId);
        assertThat(clearedMember.has("targetElementId")).isTrue();
        assertThat(clearedMember.path("targetElementId").isNull()).isTrue();

        mockMvc.perform(authorizedAs(member, touchRequest(projectId, targetElementId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(authorizedAs(member, touchRequestWithoutTarget(projectId)))
                .andExpect(status().isNoContent());
        JsonNode omittedTargetClearedMember = findPerson(
                latestPresenceEvent(watcherStream).path("payload").path("presence"), memberId);
        assertThat(omittedTargetClearedMember.has("targetElementId")).isTrue();
        assertThat(omittedTargetClearedMember.path("targetElementId").isNull()).isTrue();
    }

    @Test
    void touchRequiresAuthenticationAndMembershipWithoutCreatingPresence() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String outsider = username("outsider");
        bearer(outsider);
        UUID outsiderId = userIdFor(outsider);
        List<PresenceEntry> before = presenceRegistry.snapshot(projectId);

        mockMvc.perform(touchRequest(projectId, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        assertThat(presenceRegistry.snapshot(projectId)).isEqualTo(before);

        mockMvc.perform(authorizedAs(outsider, touchRequest(projectId, UUID.randomUUID())))
                .andExpect(status().isForbidden());
        assertThat(presenceRegistry.snapshot(projectId)).isEqualTo(before)
                .extracting(PresenceEntry::userId)
                .doesNotContain(outsiderId);
    }

    @Test
    void memberWithoutALiveStreamGetsNoPresenceFromTouch() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String member = username("offline-member");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);
        UUID memberId = userIdFor(member);
        List<PresenceEntry> before = presenceRegistry.snapshot(projectId);

        mockMvc.perform(authorizedAs(member, touchRequest(projectId, UUID.randomUUID())))
                .andExpect(status().isNoContent());

        assertThat(presenceRegistry.snapshot(projectId)).isEqualTo(before)
                .extracting(PresenceEntry::userId)
                .doesNotContain(memberId);
    }

    @Test
    void touchAcrossTwoSessionsUpdatesOnePerUserSnapshotEntry() throws Exception {
        String owner = username("two-tabs");
        UUID projectId = createProject(owner);
        MvcResult firstStream = openStream(owner, projectId);
        MvcResult secondStream = openStream(owner, projectId);
        UUID ownerId = userIdFor(owner);
        UUID targetElementId = UUID.randomUUID();

        assertThat(presenceRegistry.sessionCount(projectId)).isEqualTo(2);
        assertThat(presenceRegistry.snapshot(projectId)).hasSize(1);

        mockMvc.perform(authorizedAs(owner, touchRequest(projectId, targetElementId)))
                .andExpect(status().isNoContent());

        List<PresenceEntry> snapshot = presenceRegistry.snapshot(projectId);
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).userId()).isEqualTo(ownerId);
        assertThat(snapshot.get(0).targetElementId()).isEqualTo(targetElementId);
        JsonNode publishedSnapshot = latestPresenceEvent(firstStream).path("payload").path("presence");
        assertThat(publishedSnapshot.size()).isEqualTo(1);
        assertThat(findPerson(publishedSnapshot, ownerId).path("targetElementId").asText())
                .isEqualTo(targetElementId.toString());

        // A2 is the collapsed, most-recent session. Leaving it exposes A1's stale null if touch updated only A2.
        secondStream.getRequest().getAsyncContext().complete();
        JsonNode remainingSessionSnapshot = latestPresenceEvent(firstStream).path("payload").path("presence");
        assertThat(findPerson(remainingSessionSnapshot, ownerId).path("targetElementId").asText())
                .as("A1 retains the target after the collapsed A2 session leaves")
                .isEqualTo(targetElementId.toString());
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Presence " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode project = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return UUID.fromString(project.path("id").asText());
    }

    private MvcResult openStream(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                get("/projects/{id}/events", projectId).accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        openStreams.add(result);
        return result;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder touchRequest(
            UUID projectId, UUID targetElementId) throws Exception {
        return post("/projects/{id}/presence", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(
                        Collections.singletonMap("targetElementId", targetElementId)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder touchRequestWithoutTarget(
            UUID projectId) {
        return post("/projects/{id}/presence", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private int presenceEventCount(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString().split("event:presence.updated", -1).length - 1;
    }

    private JsonNode latestPresenceEvent(MvcResult result) throws Exception {
        JsonNode latest = null;
        for (String line : result.getResponse().getContentAsString().split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            JsonNode event = objectMapper.readTree(line.substring("data:".length()).trim());
            if ("presence.updated".equals(event.path("type").asText())) {
                latest = event;
            }
        }
        assertThat(latest).as("an SSE presence.updated event").isNotNull();
        return latest;
    }

    private void assertFullSnapshot(JsonNode event, UUID firstUserId, UUID secondUserId) {
        JsonNode snapshot = event.path("payload").path("presence");
        assertThat(event.path("type").asText()).isEqualTo("presence.updated");
        assertThat(snapshot.size()).isEqualTo(2);
        assertPerson(snapshot, firstUserId);
        assertPerson(snapshot, secondUserId);
        for (JsonNode person : snapshot) {
            assertThat(person.has("sessionId")).isFalse();
        }
        assertThat(event.toString()).doesNotContain("sessionId");
    }

    private void assertPerson(JsonNode snapshot, UUID userId) {
        JsonNode person = findPerson(snapshot, userId);
        assertThat(person).isNotNull();
        assertThat(person.path("color").asText()).isEqualTo(presenceRegistry.colorFor(userId));
        assertThat(person.has("displayName")).isTrue();
        assertThat(person.has("targetElementId")).isTrue();
    }

    private static JsonNode findPerson(JsonNode snapshot, UUID userId) {
        for (JsonNode person : snapshot) {
            if (userId.toString().equals(person.path("userId").asText())) {
                return person;
            }
        }
        return null;
    }

    private String username(String role) {
        return "presence-" + role + "-" + UUID.randomUUID();
    }
}
