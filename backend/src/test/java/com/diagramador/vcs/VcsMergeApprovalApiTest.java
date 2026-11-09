package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.persistence.VcsMergeRequestReviewRepository;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** One approval from another editor/owner is required to merge, unless nobody else could approve. */
@SpringBootTest
@AutoConfigureMockMvc
class VcsMergeApprovalApiTest extends AuthenticatedApiTest {

    private static final String APPROVE = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/approve";
    private static final String MERGE = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge";
    private static final String CLOSE = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/close";
    private static final String DETAIL = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private VcsMergeRequestReviewRepository reviewRepository;

    @Test
    void concurrentReviewAndDecisionRemainConsistent() throws Exception {
        for (String decision : List.of("close", "merge")) {
            String owner = username("race-owner");
            UUID project = createProject(owner);
            String editor = username("race-editor");
            bearer(editor);
            addMembership(editor, project, ProjectMemberRole.EDITOR);
            String id = openFeatureRequest(owner, project);
            if (decision.equals("merge")) postAction(editor, APPROVE, 200, project, id);
            CountDownLatch reviewReachedWrite = new CountDownLatch(1);
            CountDownLatch allowReview = new CountDownLatch(1);
            doAnswer(invocation -> {
                reviewReachedWrite.countDown();
                if (!allowReview.await(30, TimeUnit.SECONDS)) throw new AssertionError("review timed out");
                var saved = reviewRepository.save(invocation.getArgument(0));
                reviewRepository.flush();
                return saved;
            }).when(reviewRepository).saveAndFlush(any());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<MvcResult> reviewing = pool.submit(() -> mockMvc.perform(authorizedAs(editor,
                        post("/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/request-changes", project, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("message", "race review"))))).andReturn());
                assertThat(reviewReachedWrite.await(30, TimeUnit.SECONDS)).isTrue();
                Future<MvcResult> deciding = pool.submit(() -> mockMvc.perform(authorizedAs(owner,
                        post(decision.equals("close") ? CLOSE : MERGE, project, id))).andReturn());
                MvcResult earlyDecision = null;
                try { earlyDecision = deciding.get(2, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException expected) { /* Decision may wait for the review. */ }
                allowReview.countDown();
                MvcResult reviewResult = reviewing.get(30, TimeUnit.SECONDS);
                MvcResult decisionResult = earlyDecision == null ? deciding.get(30, TimeUnit.SECONDS) : earlyDecision;
                assertThat(decisionResult.getResponse().getStatus()).isEqualTo(200);
                JsonNode finalState = getJson(owner, DETAIL, project, id);
                assertThat(finalState.path("state").asText()).isEqualTo(decision.equals("close") ? "CLOSED" : "MERGED");
                if (read(decisionResult).path("reviews").isEmpty()) {
                    assertThat(reviewResult.getResponse().getStatus()).isEqualTo(409);
                    assertThat(finalState.path("reviews")).isEmpty();
                } else {
                    assertThat(reviewResult.getResponse().getStatus()).isEqualTo(200);
                    assertThat(finalState.path("reviews")).hasSize(1);
                }
            } finally {
                allowReview.countDown();
                pool.shutdownNow();
                reset(reviewRepository);
            }
        }
    }

    @Test
    void requestChangesPersistsWithoutChangingApprovalOrMergePolicy() throws Exception {
        String owner = username("changes-owner");
        UUID projectId = createProject(owner);
        String editor = username("changes-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String id = openFeatureRequest(owner, projectId);
        String path = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/request-changes";
        MvcResult result = mockMvc.perform(authorizedAs(editor, post(path, projectId, id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("message", "Please revise")))))
                .andExpect(status().isOk()).andReturn();
        JsonNode response = read(result);
        assertThat(jdbcTemplate.queryForObject("select count(*) from diagrammer.push_outbox where recipient_id = ? and merge_request_id = ? and event_type = 'REVIEW_CHANGES'",
                Long.class, userIdFor(owner), UUID.fromString(id))).isEqualTo(1L);
        assertThat(response.path("state").asText()).isEqualTo("OPEN");
        assertThat(response.path("approvalRequired").asBoolean()).isTrue();
        assertThat(response.path("reviews")).hasSize(1);
        assertThat(response.path("reviews").get(0).path("message").asText()).isEqualTo("Please revise");
        JsonNode storedReview = getJson(owner, DETAIL, projectId, id).path("reviews").get(0);
        assertThat(storedReview.path("reviewId")).isEqualTo(response.path("reviews").get(0).path("reviewId"));
        assertThat(storedReview.path("userId")).isEqualTo(response.path("reviews").get(0).path("userId"));
        assertThat(storedReview.path("message").asText()).isEqualTo("Please revise");
        assertThat(storedReview.path("createdAt").asText()).isNotBlank();
        postAction(editor, APPROVE, 200, projectId, id);
        assertThat(postAction(owner, MERGE, 200, projectId, id).path("state").asText()).isEqualTo("MERGED");
    }

    @Test
    void requestChangesRejectsUnauthorizedAndInvalidInputsWithoutChangingHistory() throws Exception {
        String owner = username("review-owner");
        UUID project = createProject(owner);
        String editor = username("review-editor");
        String viewer = username("review-viewer");
        String outsider = username("review-outsider");
        bearer(editor); bearer(viewer); bearer(outsider);
        addMembership(editor, project, ProjectMemberRole.EDITOR);
        addMembership(viewer, project, ProjectMemberRole.VIEWER);
        String id = openFeatureRequest(owner, project);
        String path = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/request-changes";
        JsonNode before = getJson(owner, DETAIL, project, id);
        for (String user : List.of(owner, viewer, outsider)) {
            mockMvc.perform(authorizedAs(user, post(path, project, id).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of("message", "revise")))))
                    .andExpect(status().isForbidden());
            assertThat(getJson(owner, DETAIL, project, id)).isEqualTo(before);
        }
        for (String message : List.of(" ", "x".repeat(2001))) {
            mockMvc.perform(authorizedAs(editor, post(path, project, id).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of("message", message)))))
                    .andExpect(status().isBadRequest());
            assertThat(getJson(owner, DETAIL, project, id)).isEqualTo(before);
        }
        postAction(owner, CLOSE, 200, project, id);
        JsonNode closed = getJson(owner, DETAIL, project, id);
        mockMvc.perform(authorizedAs(editor, post(path, project, id).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("message", "revise")))))
                .andExpect(status().isConflict());
        assertThat(getJson(owner, DETAIL, project, id)).isEqualTo(closed);
    }

    @Test
    void withAnotherEditorTheOwnerCannotMergeUntilTheEditorApproves() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode opened = getJson(owner, DETAIL, projectId, requestId);
        assertThat(opened.path("approvalRequired").asBoolean()).isTrue();
        assertThat(opened.path("approvals")).isEmpty();
        UUID mainHeadBefore = mainHead(projectId);

        JsonNode blocked = postAction(owner, MERGE, 409, projectId, requestId);
        assertThat(blocked.path("code").asText()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("state").asText()).isEqualTo("OPEN");
        assertThat(mainHead(projectId)).isEqualTo(mainHeadBefore);

        JsonNode approved = postAction(editor, APPROVE, 200, projectId, requestId);
        assertThat(approved.path("mergeRequestId").asText()).isEqualTo(requestId);
        assertThat(approved.path("state").asText()).isEqualTo("OPEN");
        assertThat(approved.path("approvalRequired").asBoolean()).isFalse();
        assertThat(approved.path("approvals")).hasSize(1);
        JsonNode approval = approved.path("approvals").get(0);
        assertThat(approval.path("userId").asText()).isEqualTo(userIdFor(editor).toString());
        assertThat(approval.path("displayName").asText()).isNotBlank();
        assertThat(approval.path("approvedAt").asText()).isNotBlank();

        JsonNode merged = postAction(owner, MERGE, 200, projectId, requestId);
        assertThat(merged.path("state").asText()).isEqualTo("MERGED");
        assertThat(merged.path("approvalRequired").asBoolean()).isFalse();
        assertThat(mainHead(projectId)).isNotEqualTo(mainHeadBefore);
    }

    @Test
    void selectedRecipientsPersistAndAnotherEditorMayApprove() throws Exception {
        String owner = username("recipients-owner");
        UUID projectId = createProject(owner);
        String selected = username("selected");
        bearer(selected);
        addMembership(selected, projectId, ProjectMemberRole.EDITOR);
        String other = username("other");
        bearer(other);
        addMembership(other, projectId, ProjectMemberRole.EDITOR);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postBody(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        JsonNode opened = postBody(owner, "/projects/{projectId}/vcs/merge-requests", projectId,
                Map.of("sourceBranch", "feature", "targetBranch", "main", "title", "Review",
                        "reviewerIds", List.of(userIdFor(selected))), 201);
        String id = opened.path("mergeRequestId").asText();
        assertThat(opened.path("reviewers")).hasSize(1);
        assertThat(getJson(owner, DETAIL, projectId, id).path("reviewers").get(0).path("userId").asText())
                .isEqualTo(userIdFor(selected).toString());
        assertThat(postAction(other, APPROVE, 200, projectId, id).path("approvalRequired").asBoolean()).isFalse();
        assertThat(postAction(owner, MERGE, 200, projectId, id).path("state").asText()).isEqualTo("MERGED");
    }

    @Test
    void rejectsNonMembersAndUnauthorizedOpenWithoutCreatingRequests() throws Exception {
        String owner = username("selection-owner");
        UUID projectId = createProject(owner);
        String viewer = username("selection-viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postBody(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        String path = "/projects/{projectId}/vcs/merge-requests";
        Map<String, Object> invalid = Map.of("sourceBranch", "feature", "targetBranch", "main",
                "title", "Review", "reviewerIds", List.of(UUID.randomUUID()));
        assertThat(postBody(owner, path, projectId, invalid, 400).path("code").asText())
                .isEqualTo("INVALID_PAYLOAD");
        Map<String, Object> unauthorized = Map.of("sourceBranch", "feature", "targetBranch", "main",
                "title", "Review", "reviewerIds", List.of(userIdFor(viewer)));
        assertThat(postBody(viewer, path, projectId, unauthorized, 403).path("code").asText())
                .isEqualTo("FORBIDDEN");
        assertThat(getJson(owner, path, projectId)).isEmpty();
    }

    @Test
    void rejectedOpenRequestsLeavePublicProjectStateUnchanged() throws Exception {
        String owner = username("atomic-owner");
        UUID projectId = createProject(owner);
        String editor = username("atomic-editor");
        String viewer = username("atomic-viewer");
        String outsider = username("atomic-outsider");
        bearer(editor);
        bearer(viewer);
        bearer(outsider);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        initializeBranches(owner, projectId, "feature");
        String path = "/projects/{projectId}/vcs/merge-requests";
        Map<String, Object> valid = reviewerPayload("feature", List.of(userIdFor(viewer)));
        Map<String, Object> mixed = reviewerPayload("feature", List.of(userIdFor(viewer), userIdFor(outsider)));
        List<JsonNode> before = publicProjectState(owner, projectId);

        mockMvc.perform(post(path, projectId).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(valid))).andExpect(status().isUnauthorized());
        assertThat(publicProjectState(owner, projectId)).isEqualTo(before);
        assertThat(postBody(outsider, path, projectId, valid, 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(publicProjectState(owner, projectId)).isEqualTo(before);
        assertThat(postBody(viewer, path, projectId, valid, 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(publicProjectState(owner, projectId)).isEqualTo(before);
        JsonNode rejected = postBody(editor, path, projectId, mixed, 400);
        assertThat(rejected.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(rejected.path("message").asText()).isEqualTo("Each reviewer must be an active project member.");
        assertThat(publicProjectState(owner, projectId)).isEqualTo(before);
    }

    @Test
    void repeatedRecipientIsStoredOnlyOnce() throws Exception {
        String owner = username("duplicate-owner");
        UUID projectId = createProject(owner);
        String viewer = username("duplicate-viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        initializeBranches(owner, projectId, "feature");
        UUID viewerId = userIdFor(viewer);
        JsonNode opened = postBody(owner, "/projects/{projectId}/vcs/merge-requests", projectId,
                reviewerPayload("feature", List.of(viewerId, viewerId)), 201);
        assertThat(opened.path("reviewers")).hasSize(1);
        assertThat(opened.path("reviewers").get(0).path("userId").asText()).isEqualTo(viewerId.toString());
        assertThat(getJson(owner, DETAIL, projectId, opened.path("mergeRequestId").asText()).path("reviewers"))
                .isEqualTo(opened.path("reviewers"));
    }

    @Test
    void concurrentDifferentRequestsKeepTheirOwnRecipients() throws Exception {
        String owner = username("parallel-owner");
        UUID projectId = createProject(owner);
        String first = username("parallel-first");
        String second = username("parallel-second");
        bearer(first);
        bearer(second);
        addMembership(first, projectId, ProjectMemberRole.VIEWER);
        addMembership(second, projectId, ProjectMemberRole.EDITOR);
        initializeBranches(owner, projectId, "first", "second");
        UUID firstId = userIdFor(first);
        UUID secondId = userIdFor(second);
        String path = "/projects/{projectId}/vcs/merge-requests";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> left = pool.submit(() -> {
                ready.countDown();
                if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                return postBody(owner, path, projectId, reviewerPayload("first", List.of(firstId)), 201);
            });
            Future<JsonNode> right = pool.submit(() -> {
                ready.countDown();
                if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                return postBody(owner, path, projectId, reviewerPayload("second", List.of(secondId)), 201);
            });
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            JsonNode firstRequest = left.get(30, TimeUnit.SECONDS);
            JsonNode secondRequest = right.get(30, TimeUnit.SECONDS);
            assertThat(firstRequest.path("reviewers")).hasSize(1);
            assertThat(secondRequest.path("reviewers")).hasSize(1);
            assertThat(firstRequest.path("reviewers").get(0).path("userId").asText()).isEqualTo(firstId.toString());
            assertThat(secondRequest.path("reviewers").get(0).path("userId").asText()).isEqualTo(secondId.toString());
            assertThat(getJson(owner, DETAIL, projectId, firstRequest.path("mergeRequestId").asText())
                    .path("reviewers")).isEqualTo(firstRequest.path("reviewers"));
            assertThat(getJson(owner, DETAIL, projectId, secondRequest.path("mergeRequestId").asText())
                    .path("reviewers")).isEqualTo(secondRequest.path("reviewers"));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsRemovedMembersWithoutCreatingARequest() throws Exception {
        String owner = username("inactive-owner");
        UUID projectId = createProject(owner);
        String removed = username("removed");
        bearer(removed);
        addMembership(removed, projectId, ProjectMemberRole.EDITOR);
        UUID removedId = userIdFor(removed);
        jdbcTemplate.update("UPDATE diagrammer.project_member SET status = 'REMOVED' WHERE project_id = ? AND user_id = ?",
                projectId, removedId);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postBody(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        String path = "/projects/{projectId}/vcs/merge-requests";
        JsonNode result = postBody(owner, path, projectId, Map.of("sourceBranch", "feature",
                "targetBranch", "main", "title", "Review", "reviewerIds", List.of(removedId)), 400);
        assertThat(result.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(result.path("message").asText()).isEqualTo("Each reviewer must be an active project member.");
        assertThat(getJson(owner, path, projectId)).isEmpty();
    }

    @Test
    void conflictsAreReportedBeforeAMissingApproval() throws Exception {
        String owner = username("conflict");
        UUID projectId = createProject(owner);
        String editor = username("conflict-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Base name");
        postBody(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Base"), 201);
        postBody(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postAction(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        renameClassifier(owner, projectId, classifierId, "Source name");
        postBody(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Rename on source"), 201);
        postAction(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        renameClassifier(owner, projectId, classifierId, "Target name");
        postBody(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Rename on target"), 201);
        String requestId = postBody(owner, "/projects/{projectId}/vcs/merge-requests", projectId,
                Map.of("sourceBranch", "feature", "targetBranch", "main", "title", "Conflicting"), 201)
                .path("mergeRequestId").asText();
        JsonNode opened = getJson(owner, DETAIL, projectId, requestId);
        assertThat(opened.path("hasConflicts").asBoolean()).isTrue();
        assertThat(opened.path("approvalRequired").asBoolean()).isTrue();
        UUID mainHeadBefore = mainHead(projectId);

        JsonNode error = postAction(owner, MERGE, 409, projectId, requestId);

        assertThat(error.path("code").asText()).isEqualTo("MERGE_CONFLICTS");
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("state").asText()).isEqualTo("OPEN");
        assertThat(mainHead(projectId)).isEqualTo(mainHeadBefore);
    }

    @Test
    void theOpenerCannotApproveTheirOwnRequest() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode error = postAction(owner, APPROVE, 403, projectId, requestId);

        assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(approvalRows(requestId)).isZero();
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("approvals")).isEmpty();
    }

    @Test
    void aViewerCannotApprove() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode error = postAction(viewer, APPROVE, 403, projectId, requestId);

        assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(approvalRows(requestId)).isZero();
    }

    @Test
    void approvingTwiceKeepsASingleApproval() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);

        postAction(editor, APPROVE, 200, projectId, requestId);
        JsonNode second = postAction(editor, APPROVE, 200, projectId, requestId);

        assertThat(second.path("approvals")).hasSize(1);
        assertThat(approvalRows(requestId)).isEqualTo(1L);
    }

    @Test
    void aRequestThatIsNotOpenCannotBeApproved() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);
        postAction(owner, CLOSE, 200, projectId, requestId);

        JsonNode error = postAction(editor, APPROVE, 409, projectId, requestId);

        assertThat(error.path("code").asText()).isNotBlank();
        assertThat(approvalRows(requestId)).isZero();
    }

    @Test
    void aProjectWithOnlyTheOwnerMergesWithoutApproval() throws Exception {
        String owner = username("solo");
        UUID projectId = createProject(owner);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode opened = getJson(owner, DETAIL, projectId, requestId);
        assertThat(opened.path("approvalRequired").asBoolean()).isFalse();
        assertThat(opened.path("approvals")).isEmpty();

        JsonNode merged = postAction(owner, MERGE, 200, projectId, requestId);
        assertThat(merged.path("state").asText()).isEqualTo("MERGED");
    }

    @Test
    void aViewerAloneDoesNotBlockTheMerge() throws Exception {
        String owner = username("owner-viewer");
        UUID projectId = createProject(owner);
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        String requestId = openFeatureRequest(owner, projectId);

        assertThat(getJson(owner, DETAIL, projectId, requestId).path("approvalRequired").asBoolean()).isFalse();
        assertThat(postAction(owner, MERGE, 200, projectId, requestId).path("state").asText())
                .isEqualTo("MERGED");
    }

    @Test
    void anApprovalFromAReviewerWhoLostTheRoleNoLongerCounts() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String first = username("editor-1");
        bearer(first);
        addMembership(first, projectId, ProjectMemberRole.EDITOR);
        String second = username("editor-2");
        bearer(second);
        addMembership(second, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);
        postAction(first, APPROVE, 200, projectId, requestId);
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("approvalRequired").asBoolean()).isFalse();

        mockMvc.perform(authorizedAs(owner, patch("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(first))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("role", "VIEWER")))))
                .andExpect(status().isOk());
        UUID mainHeadBefore = mainHead(projectId);

        JsonNode detail = getJson(owner, DETAIL, projectId, requestId);
        assertThat(detail.path("approvalRequired").asBoolean()).isTrue();
        assertThat(postAction(owner, MERGE, 409, projectId, requestId).path("code").asText())
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("state").asText()).isEqualTo("OPEN");
        assertThat(mainHead(projectId)).isEqualTo(mainHeadBefore);

        JsonNode approved = postAction(second, APPROVE, 200, projectId, requestId);
        assertThat(approved.path("approvalRequired").asBoolean()).isFalse();
        assertThat(postAction(owner, MERGE, 200, projectId, requestId).path("state").asText())
                .isEqualTo("MERGED");
    }

    private void initializeBranches(String owner, UUID projectId, String... branches) throws Exception {
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        for (String branch : branches) {
            postBody(owner, "/projects/{projectId}/vcs/branches", projectId,
                    Map.of("name", branch, "fromBranch", "main"), 201);
        }
    }

    private Map<String, Object> reviewerPayload(String branch, List<UUID> recipients) {
        return Map.of("sourceBranch", branch, "targetBranch", "main", "title", "Synthetic review",
                "reviewerIds", recipients);
    }

    private List<JsonNode> publicProjectState(String owner, UUID projectId) throws Exception {
        return List.of(getJson(owner, "/projects/{projectId}/vcs/merge-requests", projectId),
                getJson(owner, "/projects/{projectId}/vcs/branches", projectId),
                getJson(owner, "/projects/{projectId}/vcs/status", projectId),
                getJson(owner, "/projects/{projectId}/model", projectId));
    }

    private String openFeatureRequest(String owner, UUID projectId) throws Exception {
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postBody(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postAction(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        applyClassifier(owner, projectId, UUID.randomUUID(), "Approved element");
        postBody(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Feature element"), 201);
        postAction(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        return postBody(owner, "/projects/{projectId}/vcs/merge-requests", projectId,
                Map.of("sourceBranch", "feature", "targetBranch", "main", "title", "Needs a reviewer"), 201)
                .path("mergeRequestId").asText();
    }

    private void renameClassifier(String owner, UUID projectId, UUID classifierId, String newName)
            throws Exception {
        JsonNode model = getJson(owner, "/projects/{projectId}/model", projectId);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.rename");
        envelope.put("projectId", projectId);
        envelope.put("modelId", UUID.fromString(model.path("id").asText()));
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", List.of(Map.of("scope", "classifier", "id", classifierId)));
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("name", newName));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(result).path("status").asText()).isEqualTo("ACCEPTED");
    }

    private UUID mainHead(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT b.head_commit_id FROM diagrammer.vcs_branch b "
                + "JOIN diagrammer.uml_model m ON m.model_id = b.model_id "
                + "WHERE m.project_id = ?::uuid AND b.name = 'main'", UUID.class, projectId.toString());
    }

    private long approvalRows(String requestId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.vcs_merge_request_approval "
                + "WHERE merge_request_id = ?::uuid", Long.class, requestId);
    }

    private String username(String prefix) {
        return "vcs-appr-" + prefix + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Approval " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).path("id").asText());
    }

    private void applyClassifier(String owner, UUID projectId, UUID classifierId, String name) throws Exception {
        JsonNode model = getJson(owner, "/projects/{projectId}/model", projectId);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("projectId", projectId);
        envelope.put("modelId", UUID.fromString(model.path("id").asText()));
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", classifierId, "name", name, "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(result).path("status").asText()).isEqualTo("ACCEPTED");
    }

    private JsonNode getJson(String username, String path, Object... pathVariables) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, get(path, pathVariables)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private JsonNode postAction(String username, String path, int expectedStatus, Object... pathVariables)
            throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, pathVariables)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return read(result);
    }

    private JsonNode postBody(String username, String path, UUID projectId, Map<String, Object> body,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return read(result);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
