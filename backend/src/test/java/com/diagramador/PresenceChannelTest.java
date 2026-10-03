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
    void cursorMovePublishesOnlyTheCursorAndFullSnapshotsRetainItsPosition() throws Exception {
        String owner = username("cursor-watcher");
        UUID projectId = createProject(owner);
        String member = username("cursor-mover");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);
        MvcResult watcherStream = openStream(owner, projectId);
        MvcResult firstMemberStream = openStream(member, projectId);
        MvcResult secondMemberStream = openStream(member, projectId);
        UUID memberId = userIdFor(member);
        int eventsBeforeMove = presenceEventCount(watcherStream);
        double cursorX = 172.25;
        double cursorY = -34.5;

        mockMvc.perform(authorizedAs(member, cursorRequest(projectId, cursorX, cursorY)))
                .andExpect(status().isNoContent());

        assertThat(presenceEventCount(watcherStream)).isEqualTo(eventsBeforeMove + 1);
        JsonNode cursorEvent = latestPresenceEvent(watcherStream);
        JsonNode payload = cursorEvent.path("payload");
        JsonNode cursor = payload.path("cursor");
        assertThat(cursorEvent.path("type").asText()).isEqualTo("presence.updated");
        assertThat(payload.size()).isEqualTo(1);
        assertThat(payload.has("presence")).isFalse();
        assertThat(cursor.path("userId").asText()).isEqualTo(memberId.toString());
        assertThat(cursor.path("x").asDouble()).isEqualTo(cursorX);
        assertThat(cursor.path("y").asDouble()).isEqualTo(cursorY);

        UUID targetElementId = UUID.randomUUID();
        mockMvc.perform(authorizedAs(member, presenceRequest(projectId, targetElementId, cursorX, cursorY)))
                .andExpect(status().isNoContent());
        JsonNode fullPayload = latestPresenceEvent(watcherStream).path("payload");
        JsonNode fullSnapshot = fullPayload.path("presence");
        JsonNode memberPresence = findPerson(fullSnapshot, memberId);
        assertThat(fullPayload.has("cursor")).isFalse();
        assertThat(memberPresence.path("targetElementId").asText()).isEqualTo(targetElementId.toString());
        assertThat(memberPresence.path("cursorX").asDouble()).isEqualTo(cursorX);
        assertThat(memberPresence.path("cursorY").asDouble()).isEqualTo(cursorY);

        // Removing the most recently active tab must leave the cursor on the user's other live tab.
        secondMemberStream.getRequest().getAsyncContext().complete();
        PresenceEntry remainingEntry = presenceRegistry.snapshot(projectId).stream()
                .filter(entry -> entry.userId().equals(memberId))
                .findFirst()
                .orElseThrow();
        assertThat(remainingEntry.cursorX()).isEqualTo(cursorX);
        assertThat(remainingEntry.cursorY()).isEqualTo(cursorY);
        assertThat(firstMemberStream.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void partialCursorReportDoesNotChangeAnExistingTargetOrPublish() throws Exception {
        String owner = username("partial-cursor");
        UUID projectId = createProject(owner);
        MvcResult ownerStream = openStream(owner, projectId);
        UUID targetElementId = UUID.randomUUID();
        mockMvc.perform(authorizedAs(owner, touchRequest(projectId, targetElementId)))
                .andExpect(status().isNoContent());
        PresenceEntry before = presenceRegistry.snapshot(projectId).get(0);
        int eventsBeforeReport = presenceEventCount(ownerStream);

        mockMvc.perform(authorizedAs(owner, partialCursorRequest(projectId, 12.5))).andReturn();

        assertThat(presenceRegistry.snapshot(projectId)).containsExactly(before);
        assertThat(presenceEventCount(ownerStream)).isEqualTo(eventsBeforeReport);
    }

    @Test
    void partialCursorReportReturnsTheInvalidPayloadErrorEnvelope() throws Exception {
        String owner = username("invalid-cursor");
        UUID projectId = createProject(owner);

        MvcResult result = mockMvc.perform(authorizedAs(owner, partialCursorRequest(projectId, 12.5)))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(error.path("message").asText()).isEqualTo("Cursor coordinates must be provided together.");
        assertThat(error.path("details").isArray()).isTrue();
    }

    @Test
    void openingAnotherSessionPreservesTheUsersCursorAndTarget() throws Exception {
        String owner = username("cursor-new-tab");
        UUID projectId = createProject(owner);
        MvcResult firstStream = openStream(owner, projectId);
        UUID ownerId = userIdFor(owner);
        double cursorX = 172.25;
        double cursorY = -34.5;
        UUID targetElementId = UUID.randomUUID();

        mockMvc.perform(authorizedAs(owner, cursorRequest(projectId, cursorX, cursorY)))
                .andExpect(status().isNoContent());
        mockMvc.perform(authorizedAs(owner, touchRequest(projectId, targetElementId)))
                .andExpect(status().isNoContent());
        MvcResult secondStream = openStream(owner, projectId);

        JsonNode ownerPresence = findPerson(latestPresenceEvent(secondStream).path("payload").path("presence"), ownerId);
        assertThat(ownerPresence.path("cursorX").asDouble()).isEqualTo(cursorX);
        assertThat(ownerPresence.path("cursorY").asDouble()).isEqualTo(cursorY);
        assertThat(ownerPresence.path("targetElementId").asText()).isEqualTo(targetElementId.toString());
        assertThat(firstStream.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void cursorReportWithoutALiveSessionIsA204NoOpThatCreatesNothing() throws Exception {
        String owner = username("cursor-owner");
        UUID projectId = createProject(owner);
        MvcResult ownerStream = openStream(owner, projectId);
        String offlineMember = username("offline-cursor");
        bearer(offlineMember);
        addMembership(offlineMember, projectId, ProjectMemberRole.VIEWER);
        UUID memberId = userIdFor(offlineMember);
        List<PresenceEntry> before = presenceRegistry.snapshot(projectId);
        int eventsBeforeReport = presenceEventCount(ownerStream);

        mockMvc.perform(authorizedAs(offlineMember, cursorRequest(projectId, 12.5, 99.25)))
                .andExpect(status().isNoContent());

        assertThat(presenceRegistry.snapshot(projectId)).isEqualTo(before)
                .extracting(PresenceEntry::userId)
                .doesNotContain(memberId);
        assertThat(presenceRegistry.sessionCount(projectId)).isEqualTo(1);
        assertThat(presenceEventCount(ownerStream)).isEqualTo(eventsBeforeReport);
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder partialCursorRequest(
            UUID projectId, double cursorX) throws Exception {
        return post("/projects/{id}/presence", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("cursorX", cursorX)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cursorRequest(
            UUID projectId, double cursorX, double cursorY) throws Exception {
        return post("/projects/{id}/presence", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("cursorX", cursorX, "cursorY", cursorY)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder presenceRequest(
            UUID projectId, UUID targetElementId, double cursorX, double cursorY) throws Exception {
        return post("/projects/{id}/presence", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "targetElementId", targetElementId, "cursorX", cursorX, "cursorY", cursorY)));
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
        assertThat(person.has("cursorX")).isTrue();
        assertThat(person.path("cursorX").isNull()).isTrue();
        assertThat(person.has("cursorY")).isTrue();
        assertThat(person.path("cursorY").isNull()).isTrue();
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
