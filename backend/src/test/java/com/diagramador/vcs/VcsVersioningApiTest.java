package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.assistant.DiagramContextRenderer;
import com.diagramador.api.ModelResource;
import com.diagramador.command.ModelStateHasher;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.VcsAction;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.ViewStyle;
import com.diagramador.persistence.ViewStyleRepository;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
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

    @Autowired
    private CurrentUserContext currentUserContext;

    @Autowired
    private DiagramContextRenderer diagramContextRenderer;

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
    void aPlainCommitListsItsCommitterAsTheAuthor() throws Exception {
        String owner = username("author-plain");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        applyClassifier(owner, projectId, UUID.randomUUID(), "Authored");
        JsonNode commit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add authored classifier"), 201);

        JsonNode detail = getJson(owner, "/projects/{projectId}/vcs/commits/{commitId}",
                projectId, commit.path("commitId").asText());

        assertThat(detail.path("authors")).singleElement().satisfies(author -> {
            assertThat(author.path("userId").asText()).isEqualTo(commit.path("authorUserId").asText());
            assertThat(author.path("role").asText()).isEqualTo("AUTHOR");
        });
    }

    @Test
    void aMergeCommitListsWhoMergedAndWhoContributedTheMergedCommits() throws Exception {
        String owner = username("author-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        applyClassifier(owner, projectId, UUID.randomUUID(), "Owner work");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Owner commit"), 201);
        String editor = username("author-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        applyClassifier(editor, projectId, UUID.randomUUID(), "Editor work");
        JsonNode editorCommit = postJson(editor, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Editor commit"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Two contributors", 201);
        postActionJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 200,
                projectId, request.path("mergeRequestId").asText());
        UUID mergeHead = branchHead(getJson(owner, "/projects/{projectId}/vcs/branches", projectId), "main");

        JsonNode detail = getJson(owner, "/projects/{projectId}/vcs/commits/{commitId}",
                projectId, mergeHead.toString());

        assertThat(detail.path("authors")).hasSize(2);
        assertThat(detail.path("authors").get(0).path("role").asText()).isEqualTo("MERGED_BY");
        assertThat(detail.path("authors").get(0).path("userId").asText()).isEqualTo(detail.path("authorUserId").asText());
        assertThat(detail.path("authors").get(1).path("role").asText()).isEqualTo("CONTRIBUTOR");
        assertThat(detail.path("authors").get(1).path("userId").asText())
                .isEqualTo(editorCommit.path("authorUserId").asText());
    }

    @Test
    void commitDetailListsTheFieldsThatChangedWithTheirBeforeAndAfterValues() throws Exception {
        String owner = username("field-diff");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Before name");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Add classifier"), 201);
        renameClassifier(owner, projectId, classifierId, "After name");
        JsonNode renameCommit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Rename classifier"), 201);

        JsonNode detail = getJson(owner, "/projects/{projectId}/vcs/commits/{commitId}",
                projectId, renameCommit.path("commitId").asText());

        assertThat(detail.path("details")).singleElement().satisfies(change -> {
            assertThat(change.path("changeKind").asText()).isEqualTo("MODIFIED");
            assertThat(change.path("targetType").asText()).isEqualTo("CLASSIFIER");
            assertThat(change.path("targetName").asText()).isEqualTo("After name");
            assertThat(change.path("fields")).singleElement().satisfies(field -> {
                assertThat(field.path("field").asText()).isEqualTo("name");
                assertThat(field.path("before").asText()).isEqualTo("Before name");
                assertThat(field.path("after").asText()).isEqualTo("After name");
            });
        });
        // The coarse list every existing client reads is unchanged.
        assertThat(detail.path("changes")).anySatisfy(change ->
                assertThat(change.path("changeKind").asText()).isEqualTo("MODIFIED"));
    }

    @Test
    void repoHistoryFiltersByAuthorAndReturnsNewestFirst() throws Exception {
        String owner = username("repo-history-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        UUID firstClassifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, firstClassifierId, "Owner first classifier");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Owner first commit"), 201);

        String editor = username("repo-history-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UserAccount editorAccount = userRepository.findByUsernameIgnoreCase(editor).orElseThrow();
        editorAccount.setDisplayName("History Editor");
        userRepository.saveAndFlush(editorAccount);
        putPermissions(owner, projectId, editorAccount.getId(), List.of("COMMIT"), 200);

        applyClassifier(owner, projectId, UUID.randomUUID(), "Editor committed classifier");
        postJson(editor, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Editor commit"), 201);

        applyClassifier(owner, projectId, UUID.randomUUID(), "Owner latest classifier");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Owner latest commit"), 201);

        JsonNode allHistory = getJson(owner, get("/projects/{projectId}/vcs/repo/history", projectId));
        JsonNode editorHistory = getJson(owner,
                get("/projects/{projectId}/vcs/repo/history", projectId)
                        .param("authorUserId", editorAccount.getId().toString()));
        List<String> messages = new java.util.ArrayList<>();
        allHistory.forEach(commit -> messages.add(commit.path("message").asText()));

        assertThat(messages).containsExactly("Owner latest commit", "Editor commit", "Owner first commit",
                "Initial commit");
        assertThat(editorHistory).hasSize(1);
        assertThat(editorHistory.get(0).path("message").asText()).isEqualTo("Editor commit");
        assertThat(editorHistory.get(0).path("authorUserId").asText()).isEqualTo(editorAccount.getId().toString());
        assertThat(editorHistory.get(0).path("authorDisplayName").asText()).isEqualTo("History Editor");
    }

    @Test
    void repoDiffBetweenCommitsReportsAddedAndRenamedClassifiers() throws Exception {
        String owner = username("repo-diff");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        UUID renamedClassifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, renamedClassifierId, "Before rename");
        JsonNode firstCommit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add classifier to rename"), 201);

        applyClassifier(owner, projectId, UUID.randomUUID(), "Created between commits");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add another classifier"), 201);

        renameClassifier(owner, projectId, renamedClassifierId, "Renamed classifier");
        JsonNode lastCommit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Rename first classifier"), 201);
        JsonNode diff = getJson(owner, get("/projects/{projectId}/vcs/repo/diff", projectId)
                .param("fromCommitId", firstCommit.path("commitId").asText())
                .param("toCommitId", lastCommit.path("commitId").asText()));

        assertThat(diff).anySatisfy(change -> {
            assertThat(change.path("changeKind").asText()).isEqualTo("ADDED");
            assertThat(change.path("targetType").asText()).isEqualTo("CLASSIFIER");
            assertThat(change.path("targetName").asText()).isEqualTo("Created between commits");
        });
        assertThat(diff).anySatisfy(change -> {
            assertThat(change.path("changeKind").asText()).isEqualTo("MODIFIED");
            assertThat(change.path("targetType").asText()).isEqualTo("CLASSIFIER");
            assertThat(change.path("targetName").asText()).isEqualTo("Renamed classifier");
        });
    }

    @Test
    void repoBlameIncludesClassifierHistoryForAnAttributeAndRealAuthors() throws Exception {
        String owner = username("repo-blame-owner");
        UUID projectId = createProject(owner);
        UserAccount ownerAccount = userRepository.findByUsernameIgnoreCase(owner).orElseThrow();
        ownerAccount.setDisplayName("Classifier Author");
        userRepository.saveAndFlush(ownerAccount);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Person");
        JsonNode classifierCommit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Create person classifier"), 201);

        String editor = username("repo-blame-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UserAccount editorAccount = userRepository.findByUsernameIgnoreCase(editor).orElseThrow();
        editorAccount.setDisplayName("Attribute Author");
        userRepository.saveAndFlush(editorAccount);
        putPermissions(owner, projectId, editorAccount.getId(), List.of("COMMIT"), 200);

        UUID attributeId = UUID.randomUUID();
        commandJson(owner, projectId, "attribute.add", List.of(Map.of("scope", "classifier", "id", classifierId)),
                Map.of("id", attributeId, "name", "email", "type", Map.of("primitiveName", "String")), 200);
        JsonNode attributeCommit = postJson(editor, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add email attribute"), 201);
        JsonNode blame = getJson(editor, get("/projects/{projectId}/vcs/repo/blame", projectId)
                .param("targetType", "ATTRIBUTE")
                .param("targetId", attributeId.toString()));

        assertThat(blame).hasSize(2);
        assertThat(blame.get(0).path("commitId").asText()).isEqualTo(attributeCommit.path("commitId").asText());
        assertThat(blame.get(0).path("authorDisplayName").asText()).isEqualTo("Attribute Author");
        assertThat(blame.get(0).path("message").asText()).isEqualTo("Add email attribute");
        assertThat(blame.get(1).path("commitId").asText()).isEqualTo(classifierCommit.path("commitId").asText());
        assertThat(blame.get(1).path("authorDisplayName").asText()).isEqualTo("Classifier Author");
        assertThat(blame.get(1).path("message").asText()).isEqualTo("Create person classifier");
    }

    @Test
    void repoBlameReturnsOnlyTheCommitThatIntroducedAnUnchangedElement() throws Exception {
        String owner = username("repo-blame-stable");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        UUID stableClassifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, stableClassifierId, "Stable classifier");
        JsonNode introduced = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Introduce stable classifier"), 201);
        applyClassifier(owner, projectId, UUID.randomUUID(), "Unrelated classifier");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add unrelated classifier"), 201);

        JsonNode blame = getJson(owner, get("/projects/{projectId}/vcs/repo/blame", projectId)
                .param("targetType", "CLASSIFIER")
                .param("targetId", stableClassifierId.toString()));

        assertThat(blame).hasSize(1);
        assertThat(blame.get(0).path("commitId").asText()).isEqualTo(introduced.path("commitId").asText());
        assertThat(blame.get(0).path("message").asText()).isEqualTo("Introduce stable classifier");
    }

    @Test
    void repoQueriesAllowViewMembersDenyNonMembersAndAllowTheOperator() throws Exception {
        String owner = username("repo-access-owner");
        UUID projectId = createProject(owner);
        JsonNode initial = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Readable classifier");
        JsonNode commit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Readable history"), 201);

        String viewer = username("repo-access-viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        JsonNode viewerHistory = getJson(viewer, get("/projects/{projectId}/vcs/repo/history", projectId));
        JsonNode viewerDiff = getJson(viewer, get("/projects/{projectId}/vcs/repo/diff", projectId)
                .param("fromCommitId", initial.path("headCommitId").asText())
                .param("toCommitId", commit.path("commitId").asText()));
        JsonNode viewerBlame = getJson(viewer, get("/projects/{projectId}/vcs/repo/blame", projectId)
                .param("targetType", "CLASSIFIER")
                .param("targetId", classifierId.toString()));
        assertThat(viewerHistory).hasSize(2);
        assertThat(viewerDiff).anySatisfy(change ->
                assertThat(change.path("targetName").asText()).isEqualTo("Readable classifier"));
        assertThat(viewerBlame).hasSize(1);
        assertError(viewer, get("/projects/{projectId}/vcs/repo/blame", projectId)
                .param("targetType", "ATTRIBUTE").param("targetId", UUID.randomUUID().toString()),
                404, "NOT_FOUND");

        String outsider = username("repo-access-outsider");
        assertError(outsider, get("/projects/{projectId}/vcs/repo/history", projectId), 403, "FORBIDDEN");
        assertError(outsider, get("/projects/{projectId}/vcs/repo/diff", projectId)
                .param("toCommitId", commit.path("commitId").asText()), 403, "FORBIDDEN");
        assertError(outsider, get("/projects/{projectId}/vcs/repo/blame", projectId)
                .param("targetType", "CLASSIFIER").param("targetId", classifierId.toString()), 403, "FORBIDDEN");

        assertThat(getOperatorJson(get("/projects/{projectId}/vcs/repo/history", projectId))).hasSize(2);
        assertThat(getOperatorJson(get("/projects/{projectId}/vcs/repo/diff", projectId)
                .param("toCommitId", commit.path("commitId").asText()))).isNotEmpty();
        assertThat(getOperatorJson(get("/projects/{projectId}/vcs/repo/blame", projectId)
                .param("targetType", "CLASSIFIER").param("targetId", classifierId.toString()))).hasSize(1);
    }

    @Test
    void assistantContextAddsReadableHistoryWithoutTechnicalIdentifiers() throws Exception {
        String owner = username("assistant-history");
        UUID projectId = createProject(owner);
        UserAccount account = userRepository.findByUsernameIgnoreCase(owner).orElseThrow();
        account.setDisplayName("History Author");
        userRepository.saveAndFlush(account);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        applyClassifier(owner, projectId, UUID.randomUUID(), "History class");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add readable history entry"), 201);

        String historyBlock = assistantHistoryBlock(owner, projectId);
        assertThat(historyBlock).contains("History Author", "Add readable history entry");
        assertThat(java.util.regex.Pattern.compile(
                "(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b")
                .matcher(historyBlock).find()).isFalse();
        assertThat(java.util.regex.Pattern.compile("(?i)\\b[0-9a-f]{64}\\b")
                .matcher(historyBlock).find()).isFalse();
    }

    @Test
    void assistantContextRendersASingleCommitInOneLine() throws Exception {
        String owner = username("assistant-one");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        String historyBlock = assistantHistoryBlock(owner, projectId);

        assertThat(historyBlock).contains("un solo commit", "Initial commit");
        assertThat(historyBlock).doesNotContain("\n", "\r");
        assertThat(historyBlock.lines()).hasSize(1);
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
    void mergeRequestOpenedBeforeAnotherMergeIsRejectedWhenItNowConflictsWithTheMovedTarget() throws Exception {
        String owner = username("merge-moved-conflict");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Base name");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add base classifier"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "first", "fromBranch", "main"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "second", "fromBranch", "main"), 201);
        renameOnBranch(owner, projectId, "first", classifierId, "Renamed by first");
        renameOnBranch(owner, projectId, "second", classifierId, "Renamed by second");
        // Both requests are opened while main has not moved, so neither one records a conflict.
        JsonNode firstRequest = openMergeRequest(owner, projectId, "first", "main", "First", 201);
        JsonNode secondRequest = openMergeRequest(owner, projectId, "second", "main", "Second", 201);
        assertThat(firstRequest.path("hasConflicts").asBoolean()).isFalse();
        assertThat(secondRequest.path("hasConflicts").asBoolean()).isFalse();

        postActionJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 200,
                projectId, firstRequest.path("mergeRequestId").asText());
        UUID headAfterFirstMerge = branchHead(getJson(owner, "/projects/{projectId}/vcs/branches", projectId), "main");
        long commitsAfterFirstMerge = vcsCommitCount(projectId);

        JsonNode error = postActionJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge",
                409, projectId, secondRequest.path("mergeRequestId").asText());
        assertThat(error.path("code").asText()).isEqualTo("MERGE_CONFLICTS");
        assertThat(branchHead(getJson(owner, "/projects/{projectId}/vcs/branches", projectId), "main"))
                .isEqualTo(headAfterFirstMerge);
        assertThat(vcsCommitCount(projectId)).isEqualTo(commitsAfterFirstMerge);
        assertThat(getJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}", projectId,
                secondRequest.path("mergeRequestId").asText()).path("state").asText()).isEqualTo("OPEN");
        assertThat(classifierNameOnMain(owner, projectId, classifierId)).isEqualTo("Renamed by first");
    }

    @Test
    void mergeRequestOpenedBeforeAnotherMergeKeepsBothChangesWhenTheyDoNotConflict() throws Exception {
        String owner = username("merge-moved-clean");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID firstClassifier = UUID.randomUUID();
        UUID secondClassifier = UUID.randomUUID();
        applyClassifier(owner, projectId, firstClassifier, "First base");
        applyClassifier(owner, projectId, secondClassifier, "Second base");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add base classifiers"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "first", "fromBranch", "main"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "second", "fromBranch", "main"), 201);
        renameOnBranch(owner, projectId, "first", firstClassifier, "First changed");
        renameOnBranch(owner, projectId, "second", secondClassifier, "Second changed");
        JsonNode firstRequest = openMergeRequest(owner, projectId, "first", "main", "First", 201);
        JsonNode secondRequest = openMergeRequest(owner, projectId, "second", "main", "Second", 201);

        postActionJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 200,
                projectId, firstRequest.path("mergeRequestId").asText());
        UUID headAfterFirstMerge = branchHead(getJson(owner, "/projects/{projectId}/vcs/branches", projectId), "main");
        postActionJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 200,
                projectId, secondRequest.path("mergeRequestId").asText());

        // The second merge commit builds on the moved main, so the first merge stays in its history.
        UUID mergeHead = branchHead(getJson(owner, "/projects/{projectId}/vcs/branches", projectId), "main");
        assertThat(jdbcTemplate.queryForObject("SELECT parent_commit_id::text FROM diagrammer.vcs_commit "
                + "WHERE commit_id = ?::uuid", String.class, mergeHead.toString()))
                .isEqualTo(headAfterFirstMerge.toString());
        assertThat(classifierNameOnMain(owner, projectId, firstClassifier)).isEqualTo("First changed");
        assertThat(classifierNameOnMain(owner, projectId, secondClassifier)).isEqualTo("Second changed");
    }

    @Test
    void mergeRequestDiffListsTheFieldChangesTheCommitsAndWhoContributed() throws Exception {
        String owner = username("pr-diff-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID orderId = UUID.randomUUID();
        applyClassifier(owner, projectId, orderId, "Order");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Add order"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        renameClassifier(owner, projectId, orderId, "SaleOrder");
        JsonNode ownerCommit = postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Rename order"), 201);
        String editor = username("pr-diff-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        applyClassifier(editor, projectId, UUID.randomUUID(), "Invoice");
        JsonNode editorCommit = postJson(editor, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add invoice"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Rename and add", 201);

        JsonNode diff = getJson(owner, "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/diff",
                projectId, request.path("mergeRequestId").asText());

        assertThat(diff.path("changes")).anySatisfy(change -> {
            assertThat(change.path("changeKind").asText()).isEqualTo("MODIFIED");
            assertThat(change.path("targetName").asText()).isEqualTo("SaleOrder");
            assertThat(change.path("fields").get(0).path("before").asText()).isEqualTo("Order");
            assertThat(change.path("fields").get(0).path("after").asText()).isEqualTo("SaleOrder");
        });
        assertThat(diff.path("changes")).anySatisfy(change -> {
            assertThat(change.path("changeKind").asText()).isEqualTo("ADDED");
            assertThat(change.path("targetName").asText()).isEqualTo("Invoice");
        });
        assertThat(diff.path("commits")).extracting(commit -> commit.path("message").asText())
                .containsExactlyInAnyOrder("Rename order", "Add invoice");
        assertThat(diff.path("contributors")).extracting(author -> author.path("userId").asText())
                .containsExactlyInAnyOrder(ownerCommit.path("authorUserId").asText(),
                        editorCommit.path("authorUserId").asText());
        assertThat(diff.path("contributors")).allSatisfy(author ->
                assertThat(author.path("role").asText()).isEqualTo("CONTRIBUTOR"));
    }

    @Test
    void crossBranchAssociationClassCollisionIsReportedAndRejectedWithoutMaterialization() throws Exception {
        String owner = username("ac-merge");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        UUID sourceEndId = UUID.randomUUID();
        UUID targetEndId = UUID.randomUUID();
        UUID associationClassId = UUID.randomUUID();
        applyClassifier(owner, projectId, sourceEndId, "Association source");
        applyClassifier(owner, projectId, targetEndId, "Association target");
        applyClassifier(owner, projectId, associationClassId, "Association class");

        UUID sourceRelationshipId = UUID.randomUUID();
        UUID targetRelationshipId = UUID.randomUUID();
        createAssociation(owner, projectId, sourceRelationshipId, "Enrollment", sourceEndId, targetEndId);
        createAssociation(owner, projectId, targetRelationshipId, "Registration", sourceEndId, targetEndId);
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Create association merge base"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);

        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        setAssociationClass(owner, projectId, sourceRelationshipId, associationClassId);
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Assign class on feature"), 201);
        JsonNode sourceModel = getJson(owner, "/projects/{projectId}/model", projectId);
        assertThat(associationClassFor(sourceModel, sourceRelationshipId)).isEqualTo(associationClassId.toString());
        assertThat(associationClassFor(sourceModel, targetRelationshipId)).isEmpty();

        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        setAssociationClass(owner, projectId, targetRelationshipId, associationClassId);
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Assign class on main"), 201);
        JsonNode targetModel = getJson(owner, "/projects/{projectId}/model", projectId);
        assertThat(associationClassFor(targetModel, sourceRelationshipId)).isEmpty();
        assertThat(associationClassFor(targetModel, targetRelationshipId)).isEqualTo(associationClassId.toString());

        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Duplicate association class", 201);
        assertThat(request.path("hasConflicts").asBoolean()).isTrue();
        assertThat(request.path("conflicts")).hasSize(2);
        assertThat(request.path("conflicts")).anySatisfy(conflict -> {
            assertThat(conflict.path("kind").asText()).isEqualTo("FIELD_CONFLICT");
            assertThat(conflict.path("targetType").asText()).isEqualTo("RELATIONSHIP");
            assertThat(conflict.path("targetId").asText()).isEqualTo(sourceRelationshipId.toString());
            assertThat(conflict.path("targetName").asText()).isEqualTo("Enrollment");
            assertThat(conflict.path("field").asText()).isEqualTo("associationClassId");
        });
        assertThat(request.path("conflicts")).anySatisfy(conflict -> {
            assertThat(conflict.path("kind").asText()).isEqualTo("FIELD_CONFLICT");
            assertThat(conflict.path("targetType").asText()).isEqualTo("RELATIONSHIP");
            assertThat(conflict.path("targetId").asText()).isEqualTo(targetRelationshipId.toString());
            assertThat(conflict.path("targetName").asText()).isEqualTo("Registration");
            assertThat(conflict.path("field").asText()).isEqualTo("associationClassId");
        });

        JsonNode modelBeforeMerge = getJson(owner, "/projects/{projectId}/model", projectId);
        JsonNode branchesBeforeMerge = getJson(owner, "/projects/{projectId}/vcs/branches", projectId);
        JsonNode commitsBeforeMerge = getJson(owner, "/projects/{projectId}/vcs/commits", projectId);
        JsonNode error = postActionJson(owner,
                "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 409,
                projectId, request.path("mergeRequestId").asText());

        assertThat(error.path("code").asText()).isEqualTo("MERGE_CONFLICTS");
        JsonNode modelAfterMerge = getJson(owner, "/projects/{projectId}/model", projectId);
        assertThat(modelAfterMerge.path("revision").asLong()).isEqualTo(modelBeforeMerge.path("revision").asLong());
        assertThat(modelAfterMerge).isEqualTo(modelBeforeMerge);
        assertThat(getJson(owner, "/projects/{projectId}/vcs/branches", projectId)).isEqualTo(branchesBeforeMerge);
        assertThat(getJson(owner, "/projects/{projectId}/vcs/commits", projectId)).isEqualTo(commitsBeforeMerge);
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

    @Test
    void viewerCanReadButCannotCreateBranchCommitOrRunCreationCommand() throws Exception {
        String owner = username("viewer-read-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);

        String viewer = username("viewer-read");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        assertThat(getJson(viewer, "/projects/{projectId}/model", projectId).path("id").asText()).isNotBlank();
        assertThat(permissionActions(getJson(viewer, "/projects/{projectId}/vcs/permissions", projectId),
                userIdFor(viewer))).containsExactly("VIEW");

        JsonNode branchError = postJson(viewer, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "viewer-branch"), 403);
        assertThat(branchError.path("code").asText()).isEqualTo("FORBIDDEN");
        JsonNode commitError = postJson(viewer, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Denied"), 403);
        assertThat(commitError.path("code").asText()).isEqualTo("FORBIDDEN");

        JsonNode commandError = commandJson(viewer, projectId, "classifier.create", List.of(),
                Map.of("id", UUID.randomUUID(), "name", "Denied", "kind", "CLASS"), 403);
        assertThat(commandError.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(commandError.path("message").asText()).doesNotContain("ADD", "CREATE", "VcsAction");
    }

    @Test
    void editorViewGrantDeniesCreationAndListingShowsEffectiveBranchAction() throws Exception {
        String owner = username("view-grant-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("view-grant-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID editorId = userIdFor(editor);

        JsonNode updated = putPermissions(owner, projectId, editorId, List.of("VIEW"), 200);
        assertThat(actions(updated)).containsExactly("VIEW", "BRANCH");
        JsonNode denied = commandJson(editor, projectId, "classifier.create", List.of(),
                Map.of("id", UUID.randomUUID(), "name", "Denied", "kind", "CLASS"), 403);
        assertThat(denied.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(permissionActions(getJson(owner, "/projects/{projectId}/vcs/permissions", projectId), editorId))
                .containsExactly("VIEW", "BRANCH");
        assertThat(grantActions(projectId, editorId)).containsExactly("VIEW");
    }

    @Test
    void commitOnlyEditorCannotDeleteThroughAssistantApplyOrAppendThroughXmiImport() throws Exception {
        String owner = username("cmd-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        UUID classifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, classifierId, "Protected by grants");

        String editor = username("cmd-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID editorId = userIdFor(editor);
        putPermissions(owner, projectId, editorId, List.of("COMMIT"), 200);
        long revisionBefore = getJson(owner, "/projects/{projectId}/model", projectId)
                .path("revision").asLong();

        assertError(editor, post("/projects/{projectId}/assistant/apply", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "baseRevision", revisionBefore,
                        "operations", List.of(Map.of("type", "classifier.delete", "targetId", classifierId,
                                "args", Map.of()))))), 403, "FORBIDDEN");
        assertThat(getJson(owner, "/projects/{projectId}/model", projectId).path("revision").asLong())
                .isEqualTo(revisionBefore);
        assertThat(findById(getJson(owner, "/projects/{projectId}/model", projectId)
                .path("classifiers"), classifierId.toString())).isNotNull();

        byte[] xmi = Files.readAllBytes(Path.of("..", "docs", "fixtures", "xmi", "Prueba3.xml"));
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xmi));
        assertError(editor, post("/projects/{projectId}/xmi/import", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "fileBase64", Base64.getEncoder().encodeToString(xmi),
                        "checksum", checksum,
                        "mode", "AGREGAR",
                        "idempotencyKey", "vcs-action-" + UUID.randomUUID()))), 403, "FORBIDDEN");
        assertThat(getJson(owner, "/projects/{projectId}/model", projectId).path("revision").asLong())
                .isEqualTo(revisionBefore);
        assertThat(findById(getJson(owner, "/projects/{projectId}/model", projectId)
                .path("classifiers"), classifierId.toString())).isNotNull();
    }

    @Test
    void commitOnlyEditorCannotUndoAModelMutation() throws Exception {
        String owner = username("undo-grant-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("undo-grant-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID editorId = userIdFor(editor);
        putPermissions(owner, projectId, editorId, List.of("ADD"), 200);

        UUID classifierId = UUID.randomUUID();
        applyClassifier(editor, projectId, classifierId, "Must survive denied undo");
        long revisionBefore = getJson(owner, "/projects/{projectId}/model", projectId)
                .path("revision").asLong();
        putPermissions(owner, projectId, editorId, List.of("COMMIT"), 200);

        assertError(editor, post("/projects/{projectId}/undo", projectId), 403, "FORBIDDEN");

        JsonNode modelAfter = getJson(owner, "/projects/{projectId}/model", projectId);
        assertThat(modelAfter.path("revision").asLong()).isEqualTo(revisionBefore);
        assertThat(findById(modelAfter.path("classifiers"), classifierId.toString())).isNotNull();
    }

    @Test
    void checkoutRefusesToDeleteViewWithConversationHistoryWithoutChangingAnything() throws Exception {
        String owner = username("view-chat-checkout");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "without-chat"), 201);
        UUID modelId = modelIdFor(projectId);
        UmlModel model = modelRepository.findById(modelId).orElseThrow();
        DiagramView branchOnlyView = viewRepository.saveAndFlush(
                DiagramView.create(model, "Conversation view", "CLASS", false));
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add conversation view"), 201);

        MvcResult sessionResult = mockMvc.perform(authorizedAs(owner,
                post("/projects/{projectId}/views/{viewId}/sessions", projectId, branchOnlyView.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("title", "Branch conversation")))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID sessionId = UUID.fromString(objectMapper.readTree(sessionResult.getResponse().getContentAsByteArray())
                .path("id").asText());

        JsonNode modelBefore = getJson(owner, "/projects/{projectId}/model", projectId);
        String hashBefore = stateHasher.hash(modelRepository.findById(modelId).orElseThrow());
        Map<String, Long> rowsBefore = materializationRowCounts(projectId);
        JsonNode historyBefore = getJson(owner, "/projects/{projectId}/vcs/commits", projectId);

        MvcResult checkoutResult = mockMvc.perform(authorizedAs(owner,
                post("/projects/{projectId}/vcs/branches/{name}/checkout", projectId, "without-chat")))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode error = objectMapper.readTree(checkoutResult.getResponse().getContentAsByteArray());

        assertThat(error.path("code").asText()).isEqualTo("VIEW_CHAT_PRESENT");
        assertThat(error.path("message").asText())
                .contains(branchOnlyView.getId().toString(), "conversation history", "nothing was changed");
        assertThat(stateHasher.hash(modelRepository.findById(modelId).orElseThrow())).isEqualTo(hashBefore);
        assertThat(getJson(owner, "/projects/{projectId}/model", projectId)).isEqualTo(modelBefore);
        assertThat(materializationRowCounts(projectId)).isEqualTo(rowsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.chat_session "
                + "WHERE session_id = ?::uuid AND view_id = ?::uuid", Long.class,
                sessionId.toString(), branchOnlyView.getId().toString())).isEqualTo(1L);
        assertThat(getJson(owner, "/projects/{projectId}/vcs/commits", projectId)).isEqualTo(historyBefore);
        JsonNode status = getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        assertThat(status.path("branch").asText()).isEqualTo("main");
        assertThat(status.path("dirty").asBoolean()).isFalse();
    }

    @Test
    void checkoutStillDeletesAViewWithoutChatOrMemory() throws Exception {
        String owner = username("view-clean-checkout");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "without-view"), 201);
        UUID modelId = modelIdFor(projectId);
        UmlModel model = modelRepository.findById(modelId).orElseThrow();
        DiagramView branchOnlyView = viewRepository.saveAndFlush(
                DiagramView.create(model, "Unreferenced view", "CLASS", false));
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Add unreferenced view"), 201);

        assertThat(chatSessionCount(branchOnlyView.getId())).isZero();
        assertThat(memoryCount(branchOnlyView.getId())).isZero();
        assertThat(messageCount(branchOnlyView.getId())).isZero();
        assertThat(attachmentCount(branchOnlyView.getId())).isZero();

        JsonNode checkout = postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200,
                projectId, "without-view");

        assertThat(checkout.path("branch").asText()).isEqualTo("without-view");
        assertThat(viewRepository.findById(branchOnlyView.getId())).isEmpty();
        assertThat(viewRepository.findByModelId(modelId)).hasSize(1);
    }

    @Test
    void editorViewAndAddGrantsAllowCreationButNotDeletion() throws Exception {
        String owner = username("add-grant-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("add-grant-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID editorId = userIdFor(editor);
        UUID modelId = modelIdFor(projectId);

        putPermissions(owner, projectId, editorId, List.of("VIEW"), 200);
        // Existing rows must retain their attribution when the grant set is expanded.
        jdbcTemplate.update("UPDATE diagrammer.vcs_permission SET granted_by_user_id = ?::uuid "
                + "WHERE model_id = ?::uuid AND user_id = ?::uuid AND action = 'VIEW'::diagrammer.vcs_action",
                editorId.toString(), modelId.toString(), editorId.toString());
        JsonNode updated = putPermissions(owner, projectId, editorId, List.of("VIEW", "ADD"), 200);
        assertThat(actions(updated)).containsExactly("VIEW", "ADD", "BRANCH");
        assertThat(jdbcTemplate.queryForObject("SELECT granted_by_user_id::text FROM diagrammer.vcs_permission "
                + "WHERE model_id = ?::uuid AND user_id = ?::uuid AND action = 'VIEW'::diagrammer.vcs_action",
                String.class, modelId.toString(), editorId.toString())).isEqualTo(editorId.toString());
        assertThat(grantActions(projectId, editorId)).containsExactlyInAnyOrder("VIEW", "ADD");

        UUID protectedClassifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, protectedClassifierId, "Must remain");
        UUID addedClassifierId = UUID.randomUUID();
        JsonNode added = commandJson(editor, projectId, "classifier.create", List.of(),
                Map.of("id", addedClassifierId, "name", "Allowed addition", "kind", "CLASS"), 200);
        assertThat(added.path("status").asText()).isEqualTo("ACCEPTED");
        JsonNode deletion = commandJson(editor, projectId, "classifier.delete",
                List.of(Map.of("scope", "classifier", "id", protectedClassifierId)), Map.of(), 403);
        assertThat(deletion.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(findById(getJson(owner, "/projects/{projectId}/model", projectId)
                .path("classifiers"), protectedClassifierId.toString())).isNotNull();
    }

    @Test
    void emptyPermissionSetRestoresEditorRoleFallback() throws Exception {
        String owner = username("clear-grants-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("clear-grants-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID editorId = userIdFor(editor);
        putPermissions(owner, projectId, editorId, List.of("VIEW"), 200);

        JsonNode cleared = putPermissions(owner, projectId, editorId, List.of(), 200);
        assertThat(actions(cleared)).containsExactly("VIEW", "CREATE", "ADD", "DELETE", "BRANCH");
        assertThat(grantActions(projectId, editorId)).isEmpty();
        UUID classifierId = UUID.randomUUID();
        JsonNode created = commandJson(editor, projectId, "classifier.create", List.of(),
                Map.of("id", classifierId, "name", "Role fallback", "kind", "CLASS"), 200);
        assertThat(created.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(permissionActions(getJson(owner, "/projects/{projectId}/vcs/permissions", projectId), editorId))
                .containsExactly("VIEW", "CREATE", "ADD", "DELETE", "BRANCH");
    }

    @Test
    void viewerGrantAboveRoleCeilingIsRejectedAndCannotEnableCreation() throws Exception {
        String owner = username("viewer-add-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String viewer = username("viewer-add");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        UUID viewerId = userIdFor(viewer);

        JsonNode rejected = putPermissions(owner, projectId, viewerId, List.of("ADD"), 400);
        assertThat(rejected.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(rejected.path("message").asText()).contains("ADD", "VIEWER");
        assertThat(grantActions(projectId, viewerId)).isEmpty();

        // Even a forged row cannot lift a VIEWER above the role ceiling in the command path or matrix.
        jdbcTemplate.update("INSERT INTO diagrammer.vcs_permission "
                + "(model_id, user_id, action, granted_by_user_id) VALUES (?::uuid, ?::uuid, "
                + "'ADD'::diagrammer.vcs_action, ?::uuid)",
                modelIdFor(projectId).toString(), viewerId.toString(), userIdFor(owner).toString());
        assertThat(permissionActions(getJson(owner, "/projects/{projectId}/vcs/permissions", projectId), viewerId))
                .containsExactly("VIEW");
        JsonNode denied = commandJson(viewer, projectId, "classifier.create", List.of(),
                Map.of("id", UUID.randomUUID(), "name", "Still denied", "kind", "CLASS"), 403);
        assertThat(denied.path("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void onlyOwnerCanChangePermissionsAndInvalidActionNamesAreReported() throws Exception {
        String owner = username("manage-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("manage-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID editorId = userIdFor(editor);

        JsonNode forbidden = putPermissions(editor, projectId, editorId, List.of("VIEW"), 403);
        assertThat(forbidden.path("code").asText()).isEqualTo("FORBIDDEN");
        JsonNode invalid = putPermissions(owner, projectId, editorId, List.of("VIEW", "BOGUS_ACTION"), 400);
        assertThat(invalid.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(invalid.path("message").asText()).contains("BOGUS_ACTION");

        JsonNode saved = putPermissions(owner, projectId, editorId, List.of("VIEW"), 200);
        assertThat(actions(saved)).containsExactly("VIEW", "BRANCH");
        assertThat(actions(permissionRow(getJson(owner, "/projects/{projectId}/vcs/permissions", projectId),
                userIdFor(owner)))).containsExactly("VIEW", "CREATE", "ADD", "DELETE", "COMMIT", "BRANCH", "MERGE",
                        "MANAGE_PERMISSIONS");
        assertThat(grantActions(projectId, editorId)).containsExactly("VIEW");
    }

    @Test
    void protectedMainNeedsCommitGrantButUnprotectedMainAllowsEditorCommit() throws Exception {
        String owner = username("protected-commit-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("protected-commit-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);

        applyClassifier(owner, projectId, UUID.randomUUID(), "Protected main change");
        JsonNode denied = postJson(editor, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Denied on protected main"), 403);
        assertThat(denied.path("code").asText()).isEqualTo("FORBIDDEN");

        putPermissions(owner, projectId, userIdFor(editor), List.of("COMMIT"), 200);
        JsonNode committed = postJson(editor, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Granted on protected main"), 201);
        assertThat(committed.path("message").asText()).isEqualTo("Granted on protected main");

        putJson(owner, "/projects/{projectId}/vcs/branches/main", projectId, Map.of("isProtected", false));
        applyClassifier(owner, projectId, UUID.randomUUID(), "Unprotected main change");
        putPermissions(owner, projectId, userIdFor(editor), List.of(), 200);
        JsonNode unprotectedCommit = postJson(editor, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Editor commits to unprotected main"), 201);
        assertThat(unprotectedCommit.path("message").asText()).isEqualTo("Editor commits to unprotected main");
        assertThat(grantActions(projectId, userIdFor(editor))).isEmpty();
    }

    @Test
    void nonMemberCannotBeGrantedOrReadEvenWithAStoredViewRow() throws Exception {
        String owner = username("forged-grant-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String outsider = username("forged-grant-outsider");
        bearer(outsider);
        UUID outsiderId = userIdFor(outsider);
        UUID modelId = modelIdFor(projectId);
        jdbcTemplate.update("INSERT INTO diagrammer.vcs_permission "
                + "(model_id, user_id, action, granted_by_user_id) VALUES (?::uuid, ?::uuid, "
                + "'VIEW'::diagrammer.vcs_action, ?::uuid)",
                modelId.toString(), outsiderId.toString(), userIdFor(owner).toString());

        JsonNode notFound = putPermissions(owner, projectId, outsiderId, List.of("VIEW"), 404);
        assertThat(notFound.path("code").asText()).isEqualTo("NOT_FOUND");
        assertError(outsider, get("/projects/{projectId}/vcs/status", projectId), 403, "FORBIDDEN");
        assertError(outsider, get("/projects/{projectId}/vcs/permissions", projectId), 403, "FORBIDDEN");
        assertThat(grantActions(projectId, outsiderId)).containsExactly("VIEW");
    }

    @Test
    void operatorBypassesVcsPermissionsAndListsOnlyActiveMembersOnAnotherProject() throws Exception {
        String owner = username("operator-vcs-owner");
        UUID projectId = createProject(owner);
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        String editor = username("operator-vcs-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID editorId = userIdFor(editor);

        String removed = username("operator-vcs-removed");
        bearer(removed);
        addMembership(removed, projectId, ProjectMemberRole.VIEWER);
        jdbcTemplate.update("UPDATE diagrammer.project_member SET status = 'REMOVED' "
                + "WHERE project_id = ?::uuid AND user_id = ?::uuid", projectId.toString(), userIdFor(removed).toString());

        JsonNode initialPermissions = getOperatorJson("/projects/{projectId}/vcs/permissions", projectId);
        assertThat(permissionUserIds(initialPermissions))
                .containsExactlyInAnyOrder(userIdFor(owner).toString(), editorId.toString());
        assertThat(actions(permissionRow(initialPermissions, userIdFor(owner))))
                .containsExactly("VIEW", "CREATE", "ADD", "DELETE", "COMMIT", "BRANCH", "MERGE",
                        "MANAGE_PERMISSIONS");
        assertThat(actions(permissionRow(initialPermissions, editorId)))
                .containsExactly("VIEW", "CREATE", "ADD", "DELETE", "BRANCH");

        UUID baseClassifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, baseClassifierId, "Operator commit base");
        JsonNode operatorCommit = postOperatorJson("/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Operator commits protected main"), 201);
        assertThat(operatorCommit.path("message").asText()).isEqualTo("Operator commits protected main");
        JsonNode operatorBranch = postOperatorJson("/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "operator-branch"), 201);
        assertThat(operatorBranch.path("name").asText()).isEqualTo("operator-branch");

        JsonNode grant = putOperatorPermissions(projectId, editorId, List.of("VIEW"), 200);
        assertThat(actions(grant)).containsExactly("VIEW", "BRANCH");
        JsonNode listed = getOperatorJson("/projects/{projectId}/vcs/permissions", projectId);
        assertThat(permissionUserIds(listed))
                .containsExactlyInAnyOrder(userIdFor(owner).toString(), editorId.toString());
        assertThat(actions(permissionRow(listed, editorId))).containsExactly("VIEW", "BRANCH");
        assertThat(grantActions(projectId, editorId)).containsExactly("VIEW");

        postJson(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        UUID featureClassifierId = UUID.randomUUID();
        applyClassifier(owner, projectId, featureClassifierId, "Operator merge feature");
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Feature change"), 201);
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        JsonNode request = openMergeRequest(owner, projectId, "feature", "main", "Operator merges", 201);

        JsonNode operatorStatus = getOperatorJson("/projects/{projectId}/vcs/status", projectId);
        assertThat(operatorStatus.path("branch").asText()).isEqualTo("main");
        JsonNode merged = postOperatorActionJson(
                "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge", 200,
                projectId, request.path("mergeRequestId").asText());
        assertThat(merged.path("state").asText()).isEqualTo("MERGED");
        JsonNode checkout = postOperatorActionJson(
                "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        assertThat(findById(checkout.path("model").path("classifiers"), baseClassifierId.toString())).isNotNull();
        assertThat(findById(checkout.path("model").path("classifiers"), featureClassifierId.toString())).isNotNull();
    }

    private String assistantHistoryBlock(String owner, UUID projectId) throws Exception {
        ModelResource model = objectMapper.treeToValue(
                getJson(owner, "/projects/{projectId}/model", projectId), ModelResource.class);
        UserAccount account = userRepository.findByUsernameIgnoreCase(owner).orElseThrow();
        currentUserContext.set(new CurrentUser(account.getId(), account.getUsername(), account.getDisplayName()));
        try {
            String context = diagramContextRenderer.render(model);
            int historyStart = context.lastIndexOf("Historial del diagrama:");
            assertThat(historyStart).isGreaterThanOrEqualTo(0);
            return context.substring(historyStart);
        } finally {
            currentUserContext.clear();
        }
    }

    private String username(String prefix) {
        return "vcs-" + prefix + "-" + UUID.randomUUID();
    }

    private JsonNode putPermissions(String username, UUID projectId, UUID userId, List<String> actions,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                put("/projects/{projectId}/vcs/permissions/{userId}", projectId, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("actions", actions)))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode putOperatorPermissions(UUID projectId, UUID userId, List<String> actions, int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(withOperator(
                put("/projects/{projectId}/vcs/permissions/{userId}", projectId, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("actions", actions)))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode commandJson(String username, UUID projectId, String type,
            List<Map<String, Object>> targets, Map<String, Object> payload, int expectedStatus) throws Exception {
        JsonNode model = getJson(username, "/projects/{projectId}/model", projectId);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", type);
        envelope.put("projectId", projectId);
        envelope.put("modelId", UUID.fromString(model.path("id").asText()));
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", targets);
        envelope.put("preconditions", List.of());
        envelope.put("payload", payload);
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        MvcResult result = mockMvc.perform(authorizedAs(username,
                post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<String> grantActions(UUID projectId, UUID userId) {
        return jdbcTemplate.query("SELECT action::text FROM diagrammer.vcs_permission "
                        + "WHERE model_id = ?::uuid AND user_id = ?::uuid",
                (resultSet, rowNumber) -> resultSet.getString(1), modelIdFor(projectId).toString(), userId.toString())
                .stream()
                .sorted(java.util.Comparator.comparingInt(action -> VcsAction.valueOf(action).ordinal()))
                .toList();
    }

    private UUID modelIdFor(UUID projectId) {
        return modelRepository.findByProjectId(projectId).orElseThrow().getId();
    }

    private static List<String> actions(JsonNode permission) {
        List<String> actions = new java.util.ArrayList<>();
        permission.path("actions").forEach(action -> actions.add(action.asText()));
        return actions;
    }

    private static JsonNode permissionRow(JsonNode permissions, UUID userId) {
        for (JsonNode permission : permissions) {
            if (userId.toString().equals(permission.path("userId").asText())) {
                return permission;
            }
        }
        throw new AssertionError("Permission row was not returned for user " + userId);
    }

    private static List<String> permissionActions(JsonNode permissions, UUID userId) {
        return actions(permissionRow(permissions, userId));
    }

    private static List<String> permissionUserIds(JsonNode permissions) {
        List<String> userIds = new java.util.ArrayList<>();
        permissions.forEach(permission -> userIds.add(permission.path("userId").asText()));
        return userIds;
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

    private void createAssociation(String owner, UUID projectId, UUID relationshipId, String name,
            UUID sourceId, UUID targetId) throws Exception {
        commandJson(owner, projectId, "relationship.create", List.of(
                Map.of("scope", "classifier", "id", sourceId),
                Map.of("scope", "classifier", "id", targetId)),
                Map.of("id", relationshipId, "kind", "ASSOCIATION", "name", name,
                        "source", Map.of("classifierId", sourceId),
                        "target", Map.of("classifierId", targetId),
                        "waypoints", List.of()), 200);
    }

    private void setAssociationClass(String owner, UUID projectId, UUID relationshipId, UUID associationClassId)
            throws Exception {
        commandJson(owner, projectId, "relationship.update",
                List.of(Map.of("scope", "relationship", "id", relationshipId)),
                Map.of("associationClassId", associationClassId), 200);
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

    /** Checks the branch out, renames the classifier there and commits, leaving the working copy clean. */
    private void renameOnBranch(String owner, UUID projectId, String branch, UUID classifierId, String newName)
            throws Exception {
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, branch);
        renameClassifier(owner, projectId, classifierId, newName);
        postJson(owner, "/projects/{projectId}/vcs/commits", projectId,
                Map.of("message", "Rename on " + branch), 201);
    }

    private String classifierNameOnMain(String owner, UUID projectId, UUID classifierId) throws Exception {
        postJson(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        JsonNode model = getJson(owner, "/projects/{projectId}/model", projectId);
        return findById(model.path("classifiers"), classifierId.toString()).path("name").asText();
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

    private JsonNode getJson(String username, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, request))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode getOperatorJson(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(withOperator(request))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
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

    private JsonNode getOperatorJson(String path, Object... pathVariables) throws Exception {
        MvcResult result = mockMvc.perform(withOperator(get(path, pathVariables)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postOperatorActionJson(String path, int expectedStatus, Object... pathVariables)
            throws Exception {
        MvcResult result = mockMvc.perform(withOperator(post(path, pathVariables)))
                .andExpect(status().is(expectedStatus))
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

    private Map<String, Long> materializationRowCounts(UUID projectId) {
        UUID modelId = modelIdFor(projectId);
        return Map.of(
                "views", jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.diagram_view WHERE model_id = ?::uuid",
                        Long.class, modelId.toString()),
                "sessions", jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.chat_session cs "
                        + "JOIN diagrammer.diagram_view dv ON dv.view_id = cs.view_id "
                        + "WHERE dv.model_id = ?::uuid", Long.class, modelId.toString()),
                "memories", jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.diagram_memory dm "
                        + "JOIN diagrammer.diagram_view dv ON dv.view_id = dm.view_id "
                        + "WHERE dv.model_id = ?::uuid", Long.class, modelId.toString()),
                "messages", jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.diagram_message msg "
                        + "JOIN diagrammer.chat_session cs ON cs.session_id = msg.session_id "
                        + "JOIN diagrammer.diagram_view dv ON dv.view_id = cs.view_id "
                        + "WHERE dv.model_id = ?::uuid", Long.class, modelId.toString()),
                "attachments", jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.chat_attachment ca "
                        + "JOIN diagrammer.chat_session cs ON cs.session_id = ca.session_id "
                        + "JOIN diagrammer.diagram_view dv ON dv.view_id = cs.view_id "
                        + "WHERE dv.model_id = ?::uuid", Long.class, modelId.toString()),
                "commits", vcsCommitCount(projectId),
                "snapshots", vcsSnapshotCount(projectId),
                "commands", jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.model_command "
                        + "WHERE model_id = ?::uuid", Long.class, modelId.toString()),
                "versions", modelVersionCount(projectId));
    }

    private long chatSessionCount(UUID viewId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.chat_session WHERE view_id = ?::uuid",
                Long.class, viewId.toString());
    }

    private long memoryCount(UUID viewId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.diagram_memory WHERE view_id = ?::uuid",
                Long.class, viewId.toString());
    }

    private long messageCount(UUID viewId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.diagram_message msg "
                + "JOIN diagrammer.chat_session cs ON cs.session_id = msg.session_id "
                + "WHERE cs.view_id = ?::uuid", Long.class, viewId.toString());
    }

    private long attachmentCount(UUID viewId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.chat_attachment ca "
                + "JOIN diagrammer.chat_session cs ON cs.session_id = ca.session_id "
                + "WHERE cs.view_id = ?::uuid", Long.class, viewId.toString());
    }

    private static String associationClassFor(JsonNode model, UUID relationshipId) {
        JsonNode relationship = findById(model.path("relationships"), relationshipId.toString());
        assertThat(relationship).isNotNull();
        return relationship.path("associationClassId").asText();
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
