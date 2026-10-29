package com.diagramador.api;

import static com.diagramador.api.EditingLeaseResources.EditingLeaseEnvelope;
import static com.diagramador.api.EditingLeaseResources.EditingLeaseRequest;
import static com.diagramador.api.EditingLeaseResources.EditingLeaseView;

import com.diagramador.collaboration.EditingLease;
import com.diagramador.collaboration.EditingLeaseService;
import com.diagramador.domain.TargetType;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.UmlModelRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EditingLeaseController {

    private final ProjectAuthorizationService projectAuthorizationService;
    private final UmlModelRepository modelRepository;
    private final CurrentUserContext currentUserContext;
    private final EditingLeaseService editingLeaseService;

    public EditingLeaseController(ProjectAuthorizationService projectAuthorizationService,
            UmlModelRepository modelRepository, CurrentUserContext currentUserContext,
            EditingLeaseService editingLeaseService) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.modelRepository = modelRepository;
        this.currentUserContext = currentUserContext;
        this.editingLeaseService = editingLeaseService;
    }

    @PostMapping("/projects/{projectId}/editing-lease")
    public ResponseEntity<EditingLeaseEnvelope> acquire(@PathVariable("projectId") UUID projectId,
            @RequestBody(required = false) EditingLeaseRequest request) {
        Caller caller = requireEditor(projectId);
        EditingLease lease = editingLeaseService.acquire(caller.modelId(), caller.user().userId(),
                caller.user().displayName(), targetType(request), targetId(request));
        return ResponseEntity.ok(envelope(lease));
    }

    @PostMapping("/projects/{projectId}/editing-lease/renew")
    public ResponseEntity<EditingLeaseEnvelope> renew(@PathVariable("projectId") UUID projectId) {
        Caller caller = requireEditor(projectId);
        EditingLease lease = editingLeaseService.renew(caller.modelId(), caller.user().userId())
                .orElseThrow(() -> renewalConflict(caller.modelId()));
        return ResponseEntity.ok(envelope(lease));
    }

    @DeleteMapping("/projects/{projectId}/editing-lease")
    public ResponseEntity<EditingLeaseEnvelope> release(@PathVariable("projectId") UUID projectId) {
        Caller caller = requireEditor(projectId);
        editingLeaseService.release(caller.modelId(), caller.user().userId());
        return ResponseEntity.ok(envelope(editingLeaseService.current(caller.modelId()).orElse(null)));
    }

    @PostMapping("/projects/{projectId}/editing-lease/reclaim")
    public ResponseEntity<EditingLeaseEnvelope> reclaim(@PathVariable("projectId") UUID projectId,
            @RequestBody(required = false) EditingLeaseRequest request) {
        Caller caller = requireEditor(projectId);
        EditingLease lease = editingLeaseService.reclaim(caller.modelId(), caller.user().userId(),
                caller.user().displayName(), targetType(request), targetId(request));
        return ResponseEntity.ok(envelope(lease));
    }

    @GetMapping("/projects/{projectId}/editing-lease")
    public ResponseEntity<EditingLeaseEnvelope> current(@PathVariable("projectId") UUID projectId) {
        Caller caller = requireEditor(projectId);
        return ResponseEntity.ok(envelope(editingLeaseService.current(caller.modelId()).orElse(null)));
    }

    private Caller requireEditor(UUID projectId) {
        projectAuthorizationService.requireEditor(projectId);
        UUID modelId = modelRepository.findByProjectId(projectId)
                .map(model -> model.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "No se encontró el modelo.", List.of(), null));
        CurrentUser user = currentUserContext.require();
        return new Caller(modelId, user);
    }

    private ApiException renewalConflict(UUID modelId) {
        List<Map<String, Object>> details = editingLeaseService.blocker(modelId)
                .<List<Map<String, Object>>>map(conflict -> List.of(conflict.toDetails()))
                .orElseGet(List::of);
        return new ApiException(HttpStatus.CONFLICT, "EXPORT_BLOCKED_EDITING",
                "Editing lease could not be renewed.", details, null);
    }

    private static TargetType targetType(EditingLeaseRequest request) {
        String targetType = request == null ? null : request.targetType();
        if (targetType == null) {
            return null;
        }
        try {
            return TargetType.valueOf(targetType);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "Target type is invalid.", List.of(), null);
        }
    }

    private static UUID targetId(EditingLeaseRequest request) {
        return request == null ? null : request.targetId();
    }

    private static EditingLeaseEnvelope envelope(EditingLease lease) {
        return new EditingLeaseEnvelope(lease == null ? null : new EditingLeaseView(lease.modelId(),
                lease.editorUserId(), lease.editorDisplayName(),
                lease.targetType() == null ? null : lease.targetType().name(), lease.targetId(),
                lease.acquiredAt(), lease.expiresAt()));
    }

    private record Caller(UUID modelId, CurrentUser user) {
    }
}
