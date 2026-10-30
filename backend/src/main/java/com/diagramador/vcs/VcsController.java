package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.api.ModelResource;
import com.diagramador.api.ProjectApiService;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/projects/{projectId}/vcs", produces = MediaType.APPLICATION_JSON_VALUE)
public class VcsController {

    private final VcsAccessService accessService;
    private final VcsBranchService branchService;
    private final VcsCommitService commitService;
    private final VcsRepoQueryService repoQueryService;
    private final VcsMergeRequestService mergeRequestService;
    private final VcsPermissionService permissionService;
    private final ProjectApiService projectApiService;
    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final UserAccountRepository userRepository;
    private final CurrentUserContext currentUserContext;

    public VcsController(VcsAccessService accessService, VcsBranchService branchService,
            VcsCommitService commitService, VcsRepoQueryService repoQueryService,
            VcsMergeRequestService mergeRequestService, VcsPermissionService permissionService,
            ProjectApiService projectApiService,
            ProjectRepository projectRepository, UmlModelRepository modelRepository,
            UserAccountRepository userRepository, CurrentUserContext currentUserContext) {
        this.accessService = accessService;
        this.branchService = branchService;
        this.commitService = commitService;
        this.repoQueryService = repoQueryService;
        this.mergeRequestService = mergeRequestService;
        this.permissionService = permissionService;
        this.projectApiService = projectApiService;
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.userRepository = userRepository;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/status")
    public VcsResources.Status status(@PathVariable UUID projectId) {
        UmlModel model = readableModel(projectId);
        branchService.ensureMain(model, author());
        return commitService.status(model);
    }

    @GetMapping("/branches")
    public List<VcsResources.Branch> branches(@PathVariable UUID projectId) {
        UmlModel model = readableModel(projectId);
        branchService.ensureMain(model, author());
        return branchService.list(model);
    }

    @PostMapping(path = "/branches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VcsResources.Branch> createBranch(@PathVariable UUID projectId,
            @RequestBody(required = false) BranchRequest request) {
        UmlModel model = requiredModel(projectId);
        if (request == null) {
            throw invalidPayload("Branch request is required.");
        }
        VcsResources.Branch created = branchService.create(model, request.name(), request.fromBranch(), author());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/branches/{name}/checkout")
    public VcsResources.Checkout checkout(@PathVariable UUID projectId, @PathVariable String name) {
        UmlModel model = requiredModel(projectId);
        branchService.checkout(model, name, author());
        ModelResource resource = projectApiService.getModel(projectId);
        return new VcsResources.Checkout(name.trim().toLowerCase(Locale.ROOT), resource);
    }

    @PutMapping(path = "/branches/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VcsResources.Branch setProtection(@PathVariable UUID projectId, @PathVariable String name,
            @RequestBody(required = false) ProtectionRequest request) {
        UmlModel model = requiredModel(projectId);
        if (request == null || request.isProtected() == null) {
            throw invalidPayload("isProtected is required.");
        }
        return branchService.setProtection(model, name, request.isProtected(), author());
    }

    @GetMapping("/commits")
    public List<VcsResources.Commit> history(@PathVariable UUID projectId,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UmlModel model = readableModel(projectId);
        branchService.ensureMain(model, author());
        return commitService.history(model, branch, limit);
    }

    @GetMapping("/commits/{commitId}")
    public VcsResources.CommitDetail detail(@PathVariable UUID projectId, @PathVariable UUID commitId) {
        UmlModel model = readableModel(projectId);
        return commitService.detail(model, commitId);
    }

    @GetMapping("/repo/history")
    public List<VcsResources.Commit> repoHistory(@PathVariable UUID projectId,
            @RequestParam(name = "branchName", required = false) String branchName,
            @RequestParam(name = "authorUserId", required = false) UUID authorUserId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return repoQueryService.history(readableModel(projectId), branchName, authorUserId, limit);
    }

    @GetMapping("/repo/diff")
    public List<VcsResources.Change> repoDiff(@PathVariable UUID projectId,
            @RequestParam(name = "fromCommitId", required = false) UUID fromCommitId,
            @RequestParam(name = "toCommitId") UUID toCommitId) {
        return repoQueryService.diff(readableModel(projectId), fromCommitId, toCommitId);
    }

    @GetMapping("/repo/compare")
    public VcsResources.CommitComparison repoCompare(@PathVariable UUID projectId,
            @RequestParam(name = "fromCommitId", required = false) UUID fromCommitId,
            @RequestParam(name = "toCommitId", required = false) UUID toCommitId) {
        return repoQueryService.compare(readableModel(projectId), fromCommitId, toCommitId);
    }

    @GetMapping("/repo/blame")
    public List<VcsResources.Commit> repoBlame(@PathVariable UUID projectId,
            @RequestParam(name = "targetType") String targetType,
            @RequestParam(name = "targetId") UUID targetId,
            @RequestParam(name = "branchName", required = false) String branchName) {
        return repoQueryService.blame(readableModel(projectId), targetType, targetId, branchName);
    }

    @GetMapping("/merge-requests")
    public List<VcsResources.MergeRequest> mergeRequests(@PathVariable UUID projectId) {
        return mergeRequestService.list(readableModel(projectId));
    }

    @PostMapping(path = "/merge-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VcsResources.MergeRequest> openMergeRequest(@PathVariable UUID projectId,
            @RequestBody(required = false) MergeRequestBody request) {
        UmlModel model = requiredModel(projectId);
        if (request == null) {
            throw invalidPayload("Merge request body is required.");
        }
        VcsResources.MergeRequest created = mergeRequestService.open(model, request.sourceBranch(),
                request.targetBranch(), request.title(), author());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/merge-requests/{mergeRequestId}")
    public VcsResources.MergeRequest mergeRequestDetail(@PathVariable UUID projectId,
            @PathVariable UUID mergeRequestId) {
        return mergeRequestService.detail(readableModel(projectId), mergeRequestId);
    }

    @GetMapping("/merge-requests/{mergeRequestId}/diff")
    public VcsResources.MergeRequestDiff mergeRequestDiff(@PathVariable UUID projectId,
            @PathVariable UUID mergeRequestId) {
        return mergeRequestService.diff(readableModel(projectId), mergeRequestId);
    }

    @PostMapping("/merge-requests/{mergeRequestId}/approve")
    public VcsResources.MergeRequest approveMergeRequest(@PathVariable UUID projectId,
            @PathVariable UUID mergeRequestId) {
        return mergeRequestService.approve(requiredModel(projectId), mergeRequestId, author());
    }

    @PostMapping("/merge-requests/{mergeRequestId}/merge")
    public VcsResources.MergeRequest merge(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId) {
        return mergeRequestService.merge(requiredModel(projectId), mergeRequestId, author());
    }

    @PostMapping("/merge-requests/{mergeRequestId}/close")
    public VcsResources.MergeRequest closeMergeRequest(@PathVariable UUID projectId,
            @PathVariable UUID mergeRequestId) {
        return mergeRequestService.close(requiredModel(projectId), mergeRequestId, author());
    }

    @PostMapping(path = "/commits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VcsResources.Commit> commit(@PathVariable UUID projectId,
            @RequestBody(required = false) CommitRequest request) {
        UmlModel model = requiredModel(projectId);
        if (request == null) {
            throw invalidPayload("Commit request is required.");
        }
        VcsResources.Commit commit = commitService.commit(model, request.message(), author());
        return ResponseEntity.status(HttpStatus.CREATED).body(commit);
    }

    @PostMapping("/commits/{commitId}/restore")
    public ResponseEntity<VcsResources.Commit> restoreCommit(@PathVariable UUID projectId,
            @PathVariable UUID commitId, @RequestBody(required = false) RestoreRequest request) {
        UmlModel model = requiredModel(projectId);
        VcsResources.Commit commit = commitService.restore(model, commitId,
                request == null ? null : request.message(), author());
        return ResponseEntity.status(HttpStatus.CREATED).body(commit);
    }

    @GetMapping("/permissions")
    public List<VcsResources.Permission> permissions(@PathVariable UUID projectId) {
        UmlModel model = readableModel(projectId);
        return permissionService.list(model, author());
    }

    @PutMapping(path = "/permissions/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VcsResources.Permission replacePermissions(@PathVariable UUID projectId, @PathVariable UUID userId,
            @RequestBody(required = false) PermissionsRequest request) {
        UmlModel model = requiredModel(projectId);
        accessService.requireAction(projectId, com.diagramador.domain.VcsAction.MANAGE_PERMISSIONS);
        // [] clears grant rows and restores the project-role fallback; it does not deny all actions.
        return permissionService.replace(model, userId, request == null ? null : request.actions(), author());
    }

    private UmlModel readableModel(UUID projectId) {
        UmlModel model = requiredModel(projectId);
        accessService.requireRead(projectId);
        return model;
    }

    private UmlModel requiredModel(UUID projectId) {
        projectRepository.findById(projectId).orElseThrow(() -> notFound("No se encontró el proyecto."));
        return modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> notFound("No se encontró el modelo."));
    }

    private UserAccount author() {
        if (currentUserContext.isOperator()) {
            return null;
        }
        UUID userId = currentUserContext.require().userId();
        return userRepository.findById(userId).orElseThrow(() -> notFound("Authenticated user was not found."));
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }

    public record BranchRequest(String name, String fromBranch) {
    }

    public record ProtectionRequest(Boolean isProtected) {
    }

    public record CommitRequest(String message) {
    }

    public record RestoreRequest(String message) {
    }

    public record PermissionsRequest(List<String> actions) {
    }

    public record MergeRequestBody(String sourceBranch, String targetBranch, String title) {
    }
}
