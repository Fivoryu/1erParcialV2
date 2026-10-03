package com.diagramador.api;

import static com.diagramador.api.GenerationResources.ArtifactDownload;
import static com.diagramador.api.GenerationResources.CreateGenerationRequest;
import static com.diagramador.api.GenerationResources.CreateGenerationTargetRequest;
import static com.diagramador.api.GenerationResources.GenerationProfileResource;
import static com.diagramador.api.GenerationResources.GenerationRunResource;
import static com.diagramador.api.GenerationResources.GenerationTargetResource;
import static com.diagramador.api.GenerationResources.OperationOutcomeResource;

import com.diagramador.collaboration.EditingLeaseConflict;
import com.diagramador.collaboration.EditingLeaseService;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.GenerationTarget;
import com.diagramador.domain.UmlModel;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.GenerationDiagnostic;
import com.diagramador.generation.GenerationIdentityService;
import com.diagramador.generation.GenerationIdentityService.GenerationRunStart;
import com.diagramador.generation.GenerationIdentityService.TargetConfiguration;
import com.diagramador.generation.GenerationVerificationService;
import com.diagramador.generation.GenerationVerificationService.VerificationSummary;
import com.diagramador.generation.InputSnapshot;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.GenerationProfileRepository;
import com.diagramador.persistence.GenerationRunRepository;
import com.diagramador.persistence.GenerationTargetRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TemplateVersionRepository;
import com.diagramador.persistence.UmlModelRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationApiService {

    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final GenerationTargetRepository targetRepository;
    private final ModelVersionRepository versionRepository;
    private final GenerationProfileRepository profileRepository;
    private final TemplateVersionRepository templateRepository;
    private final GenerationRunRepository runRepository;
    private final GenerationIdentityService identityService;
    private final GenerationArtifactService artifactService;
    private final GenerationVerificationService verificationService;
    private final InputSnapshotFactory snapshotFactory;
    private final TransactionTemplate transactionTemplate;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final EditingLeaseService editingLeaseService;

    public GenerationApiService(ProjectRepository projectRepository, UmlModelRepository modelRepository,
            GenerationTargetRepository targetRepository, ModelVersionRepository versionRepository,
            GenerationProfileRepository profileRepository, TemplateVersionRepository templateRepository,
            GenerationRunRepository runRepository, GenerationIdentityService identityService,
            GenerationArtifactService artifactService, GenerationVerificationService verificationService,
            InputSnapshotFactory snapshotFactory, TransactionTemplate transactionTemplate,
            ProjectAuthorizationService projectAuthorizationService, EditingLeaseService editingLeaseService) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.targetRepository = targetRepository;
        this.versionRepository = versionRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
        this.runRepository = runRepository;
        this.identityService = identityService;
        this.artifactService = artifactService;
        this.verificationService = verificationService;
        this.snapshotFactory = snapshotFactory;
        this.transactionTemplate = transactionTemplate;
        this.projectAuthorizationService = projectAuthorizationService;
        this.editingLeaseService = editingLeaseService;
    }

    public List<GenerationTargetResource> listTargets(UUID projectId) {
        UmlModel model = requireMemberModel(projectId);
        return identityService.listTargets(model.getId()).stream()
                .map(target -> targetResource(target, model.getId())).toList();
    }

    public List<GenerationProfileResource> listProfiles(UUID projectId) {
        requireMemberModel(projectId);
        var profile = identityService.provisionV1Profile(projectId);
        var template = identityService.provisionV1Template(profile.getId());
        return List.of(new GenerationProfileResource(profile.getId(), profile.getProfileKey(), profile.getName(),
                profile.getRevision(), template.getId(), template.getRevision()));
    }

    public OperationOutcomeResource createTarget(UUID projectId, CreateGenerationTargetRequest request) {
        UmlModel model = requireEditorModel(projectId);
        TargetConfiguration configuration = new TargetConfiguration(request.name(), request.databaseEngine(),
                request.databaseName(), request.databaseSchema(), request.connectionConfigRef());
        try {
            GenerationTarget target = identityService.createTarget(model.getId(), request.targetKey(), configuration);
            return outcome("ACCEPTED", "TARGET_CREATED", "Generation target was created.", null,
                    targetResource(target, model.getId()));
        } catch (IllegalArgumentException | IllegalStateException rejection) {
            String message = safeMessage(rejection);
            String code = message.contains("target_key '") ? "TARGET_KEY_ALREADY_EXISTS"
                    : message.contains("connection_config_ref") || message.contains("credential")
                            ? "UNSAFE_TARGET_CONFIGURATION"
                            : "TARGET_REJECTED";
            return outcome("REJECTED", code, message, null, null);
        }
    }

    public OperationOutcomeResource createGeneration(UUID projectId, CreateGenerationRequest request) {
        requireEditorProject(projectId);
        OperationOutcomeResource identityRejection = validateGenerationRequest(projectId, request);
        if (identityRejection != null) {
            return identityRejection;
        }

        try {
            editingLeaseService.requireExportAllowed(request.modelId());
        } catch (ApiException exception) {
            if (!"EXPORT_BLOCKED_EDITING".equals(exception.code())) {
                throw exception;
            }
            EditingLeaseConflict conflict = exception.details().stream().findFirst()
                    .map(EditingLeaseConflict::fromDetails)
                    .orElseThrow(() -> new IllegalStateException(
                            "The blocking lease returned no conflict details."));
            return outcome("REJECTED", "EXPORT_BLOCKED_EDITING", exception.getMessage(),
                    request.idempotencyKey(), conflict);
        }

        GenerationRunStart start;
        try {
            start = identityService.startRun(request.targetId(), request.versionId(), request.profileId(),
                    request.templateId());
        } catch (IllegalArgumentException | IllegalStateException rejection) {
            return outcome("REJECTED", "GENERATION_REJECTED", safeMessage(rejection), request.idempotencyKey(), null);
        }

        GenerationRun run = start.run();
        UUID runId = run.getId();
        boolean hasBlockingDiagnostics = start.diagnostics().stream().anyMatch(GenerationDiagnostic::blocking);
        if (run.getState() == GenerationState.PENDING && !hasBlockingDiagnostics) {
            InputSnapshot snapshot = transactionTemplate.execute(status -> snapshotFactory.create(
                    versionRepository.findById(request.versionId()).orElseThrow()));
            artifactService.generate(runId, snapshot);
        }

        GenerationRunResource resource = runResource(runId, start.diagnostics());
        if (resource.state() == GenerationState.FAILED) {
            return outcome("REJECTED", "GENERATION_BLOCKED",
                    "Generation was rejected because blocking diagnostics were found.", request.idempotencyKey(),
                    resource);
        }
        String message = resource.state() == GenerationState.SUCCEEDED
                ? "Generation completed."
                : "Generation was accepted and is not yet complete.";
        return outcome("ACCEPTED", "GENERATION_ACCEPTED", message, request.idempotencyKey(), resource);
    }

    public OperationOutcomeResource getGeneration(UUID projectId, UUID runId) {
        UUID modelId = requireMemberModel(projectId).getId();
        boolean belongsToProject = transactionTemplate.execute(status -> runRepository.findById(runId)
                .map(run -> run.getModel().getId().equals(modelId)).orElse(false));
        if (!belongsToProject) {
            return runNotFound();
        }
        List<GenerationDiagnostic> diagnostics = identityService.readDiagnostics(runId);
        return outcome("ACCEPTED", "GENERATION_FOUND", "Generation run was found.", null,
                runResource(runId, diagnostics));
    }

    @Transactional(readOnly = true)
    public Object downloadArtifacts(UUID projectId, UUID runId) {
        UmlModel model = requireMemberModel(projectId);
        GenerationRun run = runRepository.findById(runId).orElse(null);
        if (run == null || !run.getModel().getId().equals(model.getId())) {
            return runNotFound();
        }
        editingLeaseService.requireExportAllowed(model.getId());
        if (run.getState() != GenerationState.SUCCEEDED || run.getArtifactSetHash() == null
                || run.getArtifactZipHash() == null) {
            return outcome("REJECTED", "RUN_NOT_COMPLETE",
                    "Generation run is not complete; no archive is available.", null, null);
        }
        Optional<GeneratedArtifactBundle> complete = artifactService.downloadComplete(runId,
                snapshotFactory.create(run.getVersion()));
        if (complete.isEmpty()) {
            return outcome("REJECTED", "RUN_NOT_COMPLETE",
                    "Generation run is not complete; its persisted artifact manifest is incomplete.", null, null);
        }
        return new ArtifactDownload(complete.get().zipBytes(), "generation-" + runId + ".zip");
    }

    @Transactional
    public OperationOutcomeResource verifyGeneration(UUID projectId, UUID runId) {
        UmlModel model = requireEditorModel(projectId);
        GenerationRun run = runRepository.findById(runId).orElse(null);
        if (run == null || !run.getModel().getId().equals(model.getId())) {
            return runNotFound();
        }
        VerificationSummary summary = verificationService.verify(runId, snapshotFactory.create(run.getVersion()));
        return outcome("ACCEPTED", "VERIFICATION_RECORDED", "Verification results were recorded for this run.",
                null, summary);
    }

    private OperationOutcomeResource validateGenerationRequest(UUID projectId, CreateGenerationRequest request) {
        return transactionTemplate.execute(status -> {
            UmlModel model = requireModel(projectId);
            if (!model.getId().equals(request.modelId())) {
                return outcome("REJECTED", "MODEL_NOT_IN_PROJECT",
                        "The requested model does not belong to this project.", request.idempotencyKey(), null);
            }
            if (targetRepository.findById(request.targetId())
                    .map(target -> !target.getModel().getId().equals(model.getId())).orElse(false)) {
                return outcome("REJECTED", "TARGET_NOT_IN_MODEL",
                        "The requested generation target does not belong to this model.", request.idempotencyKey(),
                        null);
            }
            if (versionRepository.findById(request.versionId())
                    .map(version -> !version.getModel().getId().equals(model.getId())).orElse(false)) {
                return outcome("REJECTED", "VERSION_NOT_IN_MODEL",
                        "The requested model version does not belong to this model.", request.idempotencyKey(), null);
            }
            if (profileRepository.findById(request.profileId())
                    .map(profile -> profile.getProject() == null
                            || !profile.getProject().getId().equals(projectId)).orElse(false)) {
                return outcome("REJECTED", "PROFILE_NOT_IN_PROJECT",
                        "The requested generation profile does not belong to this project.",
                        request.idempotencyKey(), null);
            }
            if (templateRepository.findById(request.templateId())
                    .map(template -> !template.getProfile().getId().equals(request.profileId())).orElse(false)) {
                return outcome("REJECTED", "TEMPLATE_NOT_IN_PROFILE",
                        "The requested template version does not belong to the selected profile.",
                        request.idempotencyKey(), null);
            }
            return null;
        });
    }

    private GenerationRunResource runResource(UUID runId, List<GenerationDiagnostic> diagnostics) {
        return transactionTemplate.execute(status -> {
            GenerationRun run = runRepository.findById(runId).orElseThrow();
            VerificationSummary summary = verificationService.readSummary(runId);
            return new GenerationRunResource(run.getId(), run.getModel().getId(), run.getVersion().getId(),
                    run.getVersion().getRevision(), run.getTarget().getId(), run.getTarget().getTargetKey(),
                    run.getTargetConfigRevision(), run.getProfile().getId(), run.getProfile().getProfileKey(),
                    run.getProfile().getRevision(), run.getTemplate().getId(), run.getTemplate().getRevision(),
                    run.getState(), run.getInputSnapshotHash(), run.getArtifactSetHash(), run.getArtifactZipHash(),
                    run.getArtifactZipUri(), run.getStartedAt(), run.getFinishedAt(), diagnostics, summary);
        });
    }

    private static GenerationTargetResource targetResource(GenerationTarget target, UUID modelId) {
        return new GenerationTargetResource(target.getId(), modelId, target.getTargetKey(),
                target.getName(), target.getDatabaseEngine(), target.getDatabaseName(), target.getDatabaseSchema(),
                target.getConnectionConfigRef(), target.getConfigurationRevision(), target.getCreatedAt(),
                target.getUpdatedAt());
    }

    private UmlModel requireModel(UUID projectId) {
        requireProject(projectId);
        return findModel(projectId);
    }

    private UmlModel requireMemberModel(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireMember(projectId);
        return findModel(projectId);
    }

    private UmlModel requireEditorModel(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireEditor(projectId);
        return findModel(projectId);
    }

    private void requireEditorProject(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireEditor(projectId);
    }

    private UmlModel findModel(UUID projectId) {
        return modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> notFound("Model was not found."));
    }

    private void requireProject(UUID projectId) {
        projectRepository.findById(projectId).orElseThrow(() -> notFound("Project was not found."));
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }

    private static OperationOutcomeResource runNotFound() {
        return outcome("REJECTED", "RUN_NOT_FOUND", "Generation run was not found.", null, null);
    }

    private static OperationOutcomeResource outcome(String outcome, String code, String message,
            String idempotencyKey, Object result) {
        return new OperationOutcomeResource(outcome, code, message, idempotencyKey, result);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return Objects.requireNonNullElse(message, "Generation request was rejected.");
    }
}
