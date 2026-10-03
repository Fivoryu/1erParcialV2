package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.collaboration.ChatService;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ChatApiTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatService chatService;

    @Autowired
    private CurrentUserContext currentUserContext;

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
    void memberCanPostAndReadTheCreatedMessage() throws Exception {
        String owner = username("post-read");
        UUID projectId = createProject(owner);

        MvcResult posted = mockMvc.perform(authorizedAs(owner, postMessageRequest(projectId, "Hello, diagram!")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode resource = objectMapper.readTree(posted.getResponse().getContentAsByteArray());
        UUID messageId = UUID.fromString(resource.path("id").asText());

        assertThat(resource.path("authorUserId").asText()).isEqualTo(userIdFor(owner).toString());
        assertThat(resource.path("authorDisplayName").asText()).isEqualTo("Integration Test");
        assertThat(resource.path("body").asText()).isEqualTo("Hello, diagram!");
        assertThat(resource.path("createdAt").asText()).isNotBlank();

        JsonNode history = readHistory(owner, projectId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("id").asText()).isEqualTo(messageId.toString());
        assertThat(history.get(0).path("body").asText()).isEqualTo("Hello, diagram!");
    }

    @Test
    void unproxiedServiceCanPostAndTheMessageIsReadableThroughTheApi() throws Exception {
        String owner = username("unproxied-post");
        UUID projectId = createProject(owner);
        // The injected service is a transactional proxy; use its target to exercise callers that bypass it.
        ChatService unproxiedService = AopTestUtils.getTargetObject(chatService);

        currentUserContext.set(new CurrentUser(userIdFor(owner), owner, "Integration Test"));
        try {
            assertThatCode(() -> unproxiedService.post(projectId, "Unproxied message"))
                    .doesNotThrowAnyException();
        } finally {
            currentUserContext.clear();
        }

        JsonNode history = readHistory(owner, projectId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("body").asText()).isEqualTo("Unproxied message");
    }

    @Test
    void historyOrdersByTimeThenByIdForMessagesWithTheSameTimestamp() throws Exception {
        String owner = username("ordered-history");
        UUID projectId = createProject(owner);
        JsonNode later = postMessage(owner, projectId, "later");
        JsonNode sameFirst = postMessage(owner, projectId, "same-first");
        JsonNode sameSecond = postMessage(owner, projectId, "same-second");
        JsonNode earlier = postMessage(owner, projectId, "earlier");

        setCreatedAt(earlier, "2020-01-01T00:00:00Z");
        setCreatedAt(sameFirst, "2020-01-01T00:00:01Z");
        setCreatedAt(sameSecond, "2020-01-01T00:00:01Z");
        setCreatedAt(later, "2020-01-01T00:00:02Z");

        List<String> sameInstantIds = List.of(
                sameFirst.path("id").asText(), sameSecond.path("id").asText()).stream().sorted().toList();
        JsonNode history = readHistory(owner, projectId);
        assertThat(history).extracting(message -> message.path("id").asText())
                .containsExactly(earlier.path("id").asText(), sameInstantIds.get(0), sameInstantIds.get(1),
                        later.path("id").asText());
    }

    @Test
    void readingAndPostingRequireAuthenticationAndProjectMembership() throws Exception {
        String owner = username("access-owner");
        UUID projectId = createProject(owner);
        String outsider = username("outsider");
        bearer(outsider);

        mockMvc.perform(get("/projects/{id}/messages", projectId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(postMessageRequest(projectId, "unauthenticated"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(authorizedAs(outsider, get("/projects/{id}/messages", projectId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(outsider, postMessageRequest(projectId, "not a member")))
                .andExpect(status().isForbidden());

        assertThat(readHistory(owner, projectId)).isEmpty();
    }

    @Test
    void viewerCanPostBecauseChatIsConversationNotModelEditing() throws Exception {
        String owner = username("viewer-owner");
        UUID projectId = createProject(owner);
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);

        MvcResult posted = mockMvc.perform(authorizedAs(viewer, postMessageRequest(projectId, "Viewer message")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode resource = objectMapper.readTree(posted.getResponse().getContentAsByteArray());
        assertThat(resource.path("authorUserId").asText()).isEqualTo(userIdFor(viewer).toString());
        assertThat(readHistory(viewer, projectId)).extracting(message -> message.path("body").asText())
                .containsExactly("Viewer message");
    }

    @Test
    void blankAndOverLimitBodiesAreRejectedWithoutWritingMessages() throws Exception {
        String owner = username("invalid-body");
        UUID projectId = createProject(owner);

        MvcResult blank = mockMvc.perform(authorizedAs(owner, postMessageRequest(projectId, " \t\n ")))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode blankError = objectMapper.readTree(blank.getResponse().getContentAsByteArray());
        assertThat(blankError.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(blankError.path("message").asText()).contains("non-blank body");

        MvcResult tooLong = mockMvc.perform(authorizedAs(owner, postMessageRequest(projectId, "x".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode lengthError = objectMapper.readTree(tooLong.getResponse().getContentAsByteArray());
        assertThat(lengthError.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(lengthError.path("message").asText()).contains("2000 characters");

        assertThat(readHistory(owner, projectId)).isEmpty();
    }

    @Test
    void projectSubscriberReceivesMessagePostedWithMessageBodyAndAuthor() throws Exception {
        String owner = username("event-subscriber");
        UUID projectId = createProject(owner);
        MvcResult stream = openStream(owner, projectId);

        MvcResult posted = mockMvc.perform(authorizedAs(owner, postMessageRequest(projectId, "event body")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode resource = objectMapper.readTree(posted.getResponse().getContentAsByteArray());
        JsonNode event = latestEvent(stream, "message.posted");
        JsonNode message = event.path("payload").path("message");

        assertThat(event.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(event.path("projectId").asText()).isEqualTo(projectId.toString());
        assertThat(message.path("id").asText()).isEqualTo(resource.path("id").asText());
        assertThat(message.path("authorUserId").asText()).isEqualTo(userIdFor(owner).toString());
        assertThat(message.path("authorDisplayName").asText()).isEqualTo("Integration Test");
        assertThat(message.path("body").asText()).isEqualTo("event body");
        assertThat(message.path("createdAt").asText()).isEqualTo(resource.path("createdAt").asText());
    }

    @Test
    void surroundingWhitespaceIsTrimmedInTheStoredAndReadBackBody() throws Exception {
        String owner = username("trimmed-body");
        UUID projectId = createProject(owner);

        MvcResult posted = mockMvc.perform(authorizedAs(owner,
                postMessageRequest(projectId, "  keep only this  ")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode resource = objectMapper.readTree(posted.getResponse().getContentAsByteArray());
        JsonNode history = readHistory(owner, projectId);

        assertThat(resource.path("body").asText()).isEqualTo("keep only this");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("id").asText()).isEqualTo(resource.path("id").asText());
        assertThat(history.get(0).path("body").asText()).isEqualTo("keep only this");
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Chat " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode project = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return UUID.fromString(project.path("id").asText());
    }

    private JsonNode postMessage(String username, UUID projectId, String body) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, postMessageRequest(projectId, body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postMessageRequest(
            UUID projectId, String body) throws Exception {
        return post("/projects/{id}/messages", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("body", body)));
    }

    private JsonNode readHistory(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, get("/projects/{id}/messages", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
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

    private JsonNode latestEvent(MvcResult stream, String eventType) throws Exception {
        JsonNode matchingEvent = null;
        for (String line : stream.getResponse().getContentAsString().split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            JsonNode event = objectMapper.readTree(line.substring("data:".length()).trim());
            if (eventType.equals(event.path("type").asText())) {
                matchingEvent = event;
            }
        }
        assertThat(matchingEvent).as("an SSE %s event", eventType).isNotNull();
        return matchingEvent;
    }

    private void setCreatedAt(JsonNode message, String timestamp) {
        jdbcTemplate.update("UPDATE diagrammer.diagram_message SET created_at = ?::timestamptz "
                        + "WHERE message_id = ?::uuid",
                timestamp, message.path("id").asText());
    }

    private String username(String role) {
        return "chat-" + role + "-" + UUID.randomUUID();
    }
}
