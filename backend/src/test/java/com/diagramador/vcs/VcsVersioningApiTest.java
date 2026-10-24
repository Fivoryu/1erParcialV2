package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.command.ModelStateHasher;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.domain.UserAccount;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.ViewStyle;
import com.diagramador.persistence.ViewStyleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class VcsVersioningApiTest extends AuthenticatedApiTest {

    private static final String OPERATOR_TOKEN = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private UmlModelRepository modelRepository;

    @Autowired
    private DiagramViewRepository viewRepository;

    @Autowired
    private UmlRelationshipRepository relationshipRepository;

    @Autowired
    private ViewStyleRepository viewStyleRepository;

    @Autowired
    private ModelStateHasher stateHasher;

    @DynamicPropertySource
    static void operatorProperties(DynamicPropertyRegistry registry) {
        registry.add("diagrammer.operator.token", () -> OPERATOR_TOKEN);
    }

    @Test
    void freshProjectLazilyGetsProtectedMainAndInitialCommit() throws Exception {
        String owner = username("fresh");
        UUID projectId = createProject(owner);

        JsonNode status = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        assertThat(status.path("branch").asText()).isEqualTo("main");
        assertThat(status.path("headCommitId").asText()).isNotBlank();
        assertThat(status.path("dirty").asBoolean()).isFalse();
        assertThat(vcsCommitCount(projectId)).isEqualTo(1L);

        JsonNode branches = getJson(owner, "/projects/{projectId}/vcs/branches", projectId);
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).path("name").asText()).isEqualTo("main");
        assertThat(branches.get(0).path("isProtected").asBoolean()).isTrue();
        assertThat(branches.get(0).path("headCreatedAt").isNull()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT kind FROM diagrammer.vcs_commit WHERE commit_id = ?::uuid",
                String.class, status.path("headCommitId").asText())).isEqualTo("INITIAL");
    }

    @Test
    void autosaveChangesWorkingCopyWithoutCreatingACommit() throws Exception {
        String owner = username("autosave");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        long before = vcsCommitCount(projectId);

        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Autosaved");

        JsonNode status = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        assertThat(status.path("dirty").asBoolean()).isTrue();
        assertThat(vcsCommitCount(projectId)).isEqualTo(before);
    }

    @Test
    void routingOnlyStyleChangeIsDirtyAndCommitsWithTheModelStateHash() throws Exception {
        String owner = username("style-hash");
        UUID projectId = createProject(owner);
        UUID modelId = UUID.fromString(getJson(owner, "/projects/{projectId}/model", projectId)
                .path("id").asText());
        UmlModel model = modelRepository.findById(modelId).orElseThrow();
        DiagramView view = viewRepository.saveAndFlush(
                DiagramView.create(model, "Style hash view", "CLASS", true));
        UmlRelationship relationship = relationshipRepository.saveAndFlush(
                UmlRelationship.create(model, RelationshipKind.ASSOCIATION, "Style hash relationship"));
        viewStyleRepository.saveAndFlush(ViewStyle.create(view, relationship.getId(), "STRAIGHT",
                0, 2, false, null));

        assertThat(getJson(owner, "/projects/{projectId}/vcs/status", projectId).path("dirty").asBoolean())
                .isFalse();
        setRelationshipRouting(owner, projectId, view.getId(), relationship.getId(), "ORTHOGONAL");
        assertThat(viewStyleRepository.findByViewIdAndTargetId(view.getId(), relationship.getId())
                .orElseThrow().getRouting()).isEqualTo("ORTHOGONAL");

        JsonNode dirtyStatus = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        assertThat(dirtyStatus.path("dirty").asBoolean()).isTrue();
        JsonNode commit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Change relationship routing"), 201);
        String modelHash = stateHasher.hash(modelRepository.findById(modelId).orElseThrow());
        String versionHash = jdbcTemplate.queryForObject("SELECT state_hash FROM diagrammer.model_version "
                + "WHERE model_id = ?::uuid ORDER BY revision DESC LIMIT 1", String.class, modelId.toString());

        assertThat(commit.path("stateHash").asText()).isEqualTo(modelHash);
        assertThat(versionHash).isEqualTo(modelHash);
        assertThat(getJson(owner, "/projects/{projectId}/vcs/status", projectId).path("dirty").asBoolean())
                .isFalse();
    }

    @Test
    void commitRejectsEmptyMessageAndCleanWorkingCopyWithoutPersistingRows() throws Exception {
        String owner = username("clean");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        long commitsBefore = vcsCommitCount(projectId);
        long snapshotsBefore = vcsSnapshotCount(projectId);

        JsonNode emptyMessage = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", ""), 400);
        assertThat(emptyMessage.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        JsonNode clean = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "No changes"), 409);
        assertThat(clean.path("code").asText()).isEqualTo("WORKING_COPY_CLEAN");
        assertThat(vcsCommitCount(projectId)).isEqualTo(commitsBefore);
        assertThat(vcsSnapshotCount(projectId)).isEqualTo(snapshotsBefore);
    }

    @Test
    void explicitCommitHeadsHistoryWithAuthenticatedAuthorAndPreviousParent() throws Exception {
        String owner = username("author");
        UUID projectId = createProject(owner);
        JsonNode initial = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID previousHead = UUID.fromString(initial.path("headCommitId").asText());
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Committed classifier");

        JsonNode created = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add classifier"), 201);
        JsonNode history = getJson(owner, "/projects/{projectId}/vcs/commits", projectId);
        UserAccount authenticatedAuthor = userRepository.findByUsernameIgnoreCase(owner).orElseThrow();

        assertThat(history.get(0).path("commitId").asText()).isEqualTo(created.path("commitId").asText());
        assertThat(history.get(0).path("message").asText()).isEqualTo("Add classifier");
        assertThat(history.get(0).path("authorUserId").asText()).isEqualTo(authenticatedAuthor.getId().toString());
        assertThat(history.get(0).path("authorDisplayName").asText()).isEqualTo(authenticatedAuthor.getDisplayName());
        assertThat(history.get(0).path("parentCommitId").asText()).isEqualTo(previousHead.toString());
        assertThat(history.get(0).path("createdAt").isNull()).isFalse();
        assertThat(vcsCommitCount(projectId)).isEqualTo(2L);
    }

    @Test
    void commitDetailReportsCreatedClassifierAsAdded() throws Exception {
        String owner = username("diff");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Added in diff");
        JsonNode commit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add diff target"), 201);

        JsonNode detail = getJson(owner, "/projects/{projectId}/vcs/commits/{commitId}",
                projectId, commit.path("commitId").asText());
        assertThat(detail.path("changes")).anySatisfy(change -> {
            assertThat(change.path("changeKind").asText()).isEqualTo("ADDED");
            assertThat(change.path("targetType").asText()).isEqualTo("CLASSIFIER");
            assertThat(change.path("targetId").asText()).isEqualTo(classifierId.toString());
            assertThat(change.path("targetName").asText()).isEqualTo("Added in diff");
        });
    }

    @Test
    void dirtyCheckoutIsRejectedWithoutChangingTheModelOrActiveBranch() throws Exception {
        String owner = username("dirty-checkout");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId, Map.of("name", "feature"), 201);
        applyClassifier(owner, projectId, UUID.randomUUID(), "Uncommitted");
        JsonNode before = getJson(owner, "/projects/{projectId}/model", projectId);

        JsonNode error = postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 409,
                projectId, "feature");

        assertThat(error.path("code").asText()).isEqualTo("WORKING_COPY_DIRTY");
        assertThat(getJson(owner, "/projects/{projectId}/model", projectId)).isEqualTo(before);
        JsonNode status = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        assertThat(status.path("branch").asText()).isEqualTo("main");
        assertThat(status.path("dirty").asBoolean()).isTrue();
    }

    @Test
    void checkoutRestoresFeatureSnapshotAndPreservesClassifierIdentity() throws Exception {
        String owner = username("restore");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId, Map.of("name", "feature"), 201);
        long versionsBeforeCheckout = modelVersionCount(projectId);
        JsonNode checkoutFeature = postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200,
                projectId, "feature");
        assertThat(checkoutFeature.path("branch").asText()).isEqualTo("feature");
        assertThat(checkoutFeature.path("model").path("revision").asLong()).isEqualTo(2L);
        assertThat(modelVersionCount(projectId)).isEqualTo(versionsBeforeCheckout + 1L);

        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Feature only");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Feature classifier"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        assertThat(findById(getJson(owner, "/projects/{projectId}/model", projectId)
                .path("classifiers"), classifierId.toString())).isNull();

        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        JsonNode restored = findById(getJson(owner, "/projects/{projectId}/model", projectId)
                .path("classifiers"), classifierId.toString());
        assertThat(restored).isNotNull();
        assertThat(restored.path("id").asText()).isEqualTo(classifierId.toString());
        assertThat(restored.path("name").asText()).isEqualTo("Feature only");
    }

    @Test
    void mainAndCaseInsensitiveDuplicateBranchNamesAreRejected() throws Exception {
        String owner = username("duplicates");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        JsonNode mainError = postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "main"), 409);
        assertThat(mainError.path("code").asText()).isEqualTo("BRANCH_NAME_TAKEN");
        JsonNode created = postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "  Feature-X  "), 201);
        assertThat(created.path("name").asText()).isEqualTo("feature-x");
        JsonNode duplicate = postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "FEATURE-X"), 409);
        assertThat(duplicate.path("code").asText()).isEqualTo("BRANCH_NAME_TAKEN");
    }

    @Test
    void nonMemberIsForbiddenAndOperatorCanReadCommitAndCreateBranches() throws Exception {
        String owner = username("access-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        applyClassifier(owner, projectId, UUID.randomUUID(), "Owner change");
        String outsider = username("outsider");
        bearer(outsider);

        assertError(outsider, get("/projects/{projectId}/vcs/status", projectId), 403, "FORBIDDEN");
        assertError(outsider, post("/projects/{projectId}/vcs/commits", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("message", "Denied"))), 403, "FORBIDDEN");
        assertError(outsider, post("/projects/{projectId}/vcs/branches", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "denied"))), 403, "FORBIDDEN");

        JsonNode operatorStatus = getOperatorJson("/projects/{projectId}/vcs/status", projectId);
        assertThat(operatorStatus.path("dirty").asBoolean()).isTrue();
        JsonNode operatorCommit = postOperatorJson("/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Operator commit"), 201);
        assertThat(operatorCommit.path("message").asText()).isEqualTo("Operator commit");
        JsonNode operatorBranch = postOperatorJson("/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "operator-feature"), 201);
        assertThat(operatorBranch.path("name").asText()).isEqualTo("operator-feature");
    }

    @Test
    void onlyOwnerCanChangeBranchProtection() throws Exception {
        String owner = username("protection-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);

        assertError(editor, put("/projects/{projectId}/vcs/branches/main", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("isProtected", false))), 403, "FORBIDDEN");
        JsonNode changed = putJson(owner, "/projects/{projectId}/vcs/branches/main", projectId,
                Map.of("isProtected", false));
        assertThat(changed.path("name").asText()).isEqualTo("main");
        assertThat(changed.path("isProtected").asBoolean()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT is_protected FROM diagrammer.vcs_branch "
                + "WHERE model_id = (SELECT model_id FROM diagrammer.uml_model WHERE project_id = ?::uuid) "
                + "AND name = 'main'", Boolean.class, projectId.toString())).isFalse();
    }

    @Test
    void mergeRequestStoresItsInitialBaseAndIsAvailableFromListAndDetail() throws Exception {
        String owner = username("merge-base");
        UUID projectId = createProject(owner);
        UUID initialHead = UUID.fromString(getJson(owner, "/projects/{projectId}/vcs/status", projectId)
                .path("headCommitId").asText());
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);

        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        UUID sourceElement = UUID.randomUUID();
        applyClassifier(owner, projectId, sourceElement, "Source element");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Source branch commit"), 201);

        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        applyClassifier(owner, projectId, UUID.randomUUID(), "Target element");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Target branch commit"), 201);

        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Combine branches", 201);
        String requestId = request.path("mergeRequestId").asText();
        assertThat(request.path("mergeBaseCommitId").asText()).isEqualTo(initialHead.toString());
        assertThat(request.path("hasConflicts").asBoolean()).isFalse();
        assertThat(request.path("createdAt").isNull()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT merge_base_commit_id::text "
                + "FROM diagrammer.vcs_merge_request WHERE merge_request_id = ?::uuid",
                String.class, requestId)).isEqualTo(initialHead.toString());

        JsonNode list = getJson(owner, "/projects/{projectId}/vcs/merge-requests", projectId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("mergeRequestId").asText()).isEqualTo(requestId);
        JsonNode detail = getJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}",
                projectId, requestId);
        assertThat(detail).isEqualTo(request);
    }

    @Test
    void fastForwardMergeCreatesTwoParentCommitAndSourceElementAppearsOnMain() throws Exception {
        String owner = username("merge-fast-forward");
        UUID projectId = createProject(owner);
        UUID initialHead = UUID.fromString(getJson(owner, "/projects/{projectId}/vcs/status", projectId)
                .path("headCommitId").asText());
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Fast-forwarded");
        JsonNode sourceCommit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add source element"), 201);
        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Fast-forward", 201);
        assertThat(request.path("hasConflicts").asBoolean()).isFalse();

        JsonNode merged = postActionJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge",
                200, projectId, request.path("mergeRequestId").asText());
        assertThat(merged.path("state").asText()).isEqualTo("MERGED");
        JsonNode branches = getJson(owner, "/projects/{projectId}/vcs/branches", projectId);
        UUID mergeHead = branchHead(branches, "main");
        assertThat(mergeHead).isNotEqualTo(initialHead);
        assertThat(jdbcTemplate.queryForObject("SELECT kind FROM diagrammer.vcs_commit WHERE commit_id = ?::uuid",
                String.class, mergeHead.toString())).isEqualTo("MERGE");
        assertThat(jdbcTemplate.queryForObject("SELECT parent_commit_id::text FROM diagrammer.vcs_commit "
                + "WHERE commit_id = ?::uuid", String.class, mergeHead.toString())).isEqualTo(initialHead.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT second_parent_commit_id::text FROM diagrammer.vcs_commit "
                + "WHERE commit_id = ?::uuid", String.class, mergeHead.toString()))
                .isEqualTo(sourceCommit.path("commitId").asText());

        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        JsonNode model = getJson(owner, "/projects/{projectId}/model", projectId);
        assertThat(findById(model.path("classifiers"), classifierId.toString())).isNotNull();
    }

    @Test
    void conflictingMergeIsRejectedWithoutMovingTheTargetHead() throws Exception {
        String owner = username("merge-conflict");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Base name");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add base classifier"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);

        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        renameClassifier(owner, projectId, classifierId, "Source name");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Rename on source"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        renameClassifier(owner, projectId, classifierId, "Target name");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Rename on target"), 201);

        UUID targetHeadBefore = branchHead(getJson(owner, "/projects/{projectId}/vcs/branches", projectId), "main");
        long commitsBeforeMerge = vcsCommitCount(projectId);
        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Conflicting rename", 201);
        assertThat(request.path("hasConflicts").asBoolean()).isTrue();
        assertThat(request.path("conflicts")).anySatisfy(conflict -> {
            assertThat(conflict.path("kind").asText()).isEqualTo("FIELD_CONFLICT");
            assertThat(conflict.path("field").asText()).isEqualTo("name");
        });

        JsonNode error = postActionJson(owner,
                "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 409,
                projectId, request.path("mergeRequestId").asText());
        assertThat(error.path("code").asText()).isEqualTo("MERGE_CONFLICTS");
        assertThat(branchHead(getJson(owner, "/projects/{projectId}/vcs/branches", projectId), "main"))
                .isEqualTo(targetHeadBefore);
        assertThat(vcsCommitCount(projectId)).isEqualTo(commitsBeforeMerge);
        assertThat(getJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}", projectId,
                request.path("mergeRequestId").asText()).path("state").asText()).isEqualTo("OPEN");
    }

    @Test
    void editorCannotMergeButOwnerCanAndActiveTargetIsMaterialized() throws Exception {
        String owner = username("merge-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Visible after merge");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Feature element"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Active target", 201);
        String requestId = request.path("mergeRequestId").asText();
        long versionsBefore = modelVersionCount(projectId);
        long revisionBefore = getJson(owner, "/projects/{projectId}/model", projectId).path("revision").asLong();

        String editor = username("merge-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        JsonNode forbidden = postActionJson(editor,
                "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 403, projectId, requestId);
        assertThat(forbidden.path("code").asText()).isEqualTo("FORBIDDEN");

        JsonNode merged = postActionJson(owner,
                "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 200, projectId, requestId);
        assertThat(merged.path("state").asText()).isEqualTo("MERGED");
        JsonNode model = getJson(owner, "/projects/{projectId}/model", projectId);
        assertThat(findById(model.path("classifiers"), classifierId.toString())).isNotNull();
        assertThat(model.path("revision").asLong()).isEqualTo(revisionBefore + 1);
        assertThat(modelVersionCount(projectId)).isEqualTo(versionsBefore + 1);
        JsonNode status = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        assertThat(status.path("branch").asText()).isEqualTo("main");
        assertThat(status.path("dirty").asBoolean()).isFalse();
    }

    @Test
    void duplicateOpenRequestIsRejectedAndClosedRequestCannotBeMerged() throws Exception {
        String owner = username("merge-close");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Close me", 201);
        JsonNode duplicate = postJson(owner, "/projects/{projectId}/vcs/merge-requests", projectId,
                Map.of("sourceBranch", "feature", "targetBranch", "main", "title", "Duplicate"), 409);
        assertThat(duplicate.path("code").asText()).isEqualTo("MERGE_REQUEST_EXISTS");

        String requestId = request.path("mergeRequestId").asText();
        JsonNode closed = postActionJson(owner,
                "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/close", 200, projectId, requestId);
        assertThat(closed.path("state").asText()).isEqualTo("CLOSED");
        JsonNode mergeError = postActionJson(owner,
                "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 400, projectId, requestId);
        assertThat(mergeError.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM diagrammer.vcs_merge_request "
                + "WHERE merge_request_id = ?::uuid", String.class, requestId)).isEqualTo("CLOSED");
    }

    private String username(String prefix) {
        return "vcs-" + prefix + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "VCS " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
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
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("status").asText()).isEqualTo("ACCEPTED");
    }

    private void setRelationshipRouting(String owner, UUID projectId, UUID viewId, UUID relationshipId,
            String routing) throws Exception {
        JsonNode model = getJson(owner, "/projects/{projectId}/model", projectId);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "style.set");
        envelope.put("projectId", projectId);
        envelope.put("modelId", UUID.fromString(model.path("id").asText()));
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("viewId", viewId, "targetId", relationshipId, "routing", routing));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("status").asText()).isEqualTo("ACCEPTED");
    }

    private void renameClassifier(String owner, UUID projectId, UUID classifierId, String newName) throws Exception {
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
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("status").asText()).isEqualTo("ACCEPTED");
    }

    private JsonNode openMergeRequest(String username, UUID projectId, String sourceBranch,
            String targetBranch, String title, int expectedStatus) throws Exception {
        return postJson(username, "/projects/{projectId}/vcs/merge-requests", projectId,
                Map.of("sourceBranch", sourceBranch, "targetBranch", targetBranch, "title", title),
                expectedStatus);
    }

    private JsonNode postActionJson(String username, String path, int expectedStatus,
            Object... pathVariables) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, pathVariables)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static UUID branchHead(JsonNode branches, String branchName) {
        for (JsonNode branch : branches) {
            if (branchName.equals(branch.path("name").asText())) {
                return UUID.fromString(branch.path("headCommitId").asText());
            }
        }
        throw new AssertionError("Branch was not returned: " + branchName);
    }

    private JsonNode getJson(String username, String path, Object... pathVariables) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, get(path, pathVariables)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postJson(String username, String path, UUID projectId, Map<String, Object> body,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postJson(String username, String path, int expectedStatus,
            UUID projectId, String branchName) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, projectId, branchName)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode putJson(String username, String path, UUID projectId, Map<String, Object> body)
            throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, put(path, projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postJson(String username, String path, UUID projectId, String branchName,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, projectId, branchName)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postJson(String username, String path, int expectedStatus, UUID projectId, String branchName,
            Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, projectId, branchName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postOperatorJson(String path, UUID projectId, Map<String, Object> body, int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(withOperator(post(path, projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode getOperatorJson(String path, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(withOperator(get(path, projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertError(String username, MockHttpServletRequestBuilder request, int expectedStatus,
            String expectedCode) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, request))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("code").asText()).isEqualTo(expectedCode);
    }

    private long vcsCommitCount(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.vcs_commit c "
                + "JOIN diagrammer.uml_model m ON m.model_id = c.model_id WHERE m.project_id = ?::uuid",
                Long.class, projectId.toString());
    }

    private long modelVersionCount(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.model_version v "
                + "JOIN diagrammer.uml_model m ON m.model_id = v.model_id WHERE m.project_id = ?::uuid",
                Long.class, projectId.toString());
    }

    private long vcsSnapshotCount(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.vcs_snapshot s "
                + "JOIN diagrammer.uml_model m ON m.model_id = s.model_id WHERE m.project_id = ?::uuid",
                Long.class, projectId.toString());
    }

    private static JsonNode findById(JsonNode array, String id) {
        for (JsonNode candidate : array) {
            if (id.equals(candidate.path("id").asText())) {
                return candidate;
            }
        }
        return null;
    }

    private MockHttpServletRequestBuilder withOperator(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN);
    }
}
