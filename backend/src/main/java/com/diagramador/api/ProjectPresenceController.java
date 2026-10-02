package com.diagramador.api;

import com.diagramador.collaboration.PresencePublisher;
import com.diagramador.collaboration.PresenceRegistry;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectPresenceController {

    private final ProjectAuthorizationService projectAuthorizationService;
    private final CurrentUserContext currentUserContext;
    private final PresenceRegistry presenceRegistry;
    private final PresencePublisher presencePublisher;

    public ProjectPresenceController(ProjectAuthorizationService projectAuthorizationService,
            CurrentUserContext currentUserContext, PresenceRegistry presenceRegistry,
            PresencePublisher presencePublisher) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.currentUserContext = currentUserContext;
        this.presenceRegistry = presenceRegistry;
        this.presencePublisher = presencePublisher;
    }

    @PostMapping(path = "/projects/{id}/presence", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportTouch(@PathVariable("id") UUID id, @RequestBody PresenceRequest request) {
        projectAuthorizationService.requireMember(id);
        UUID userId = currentUserContext.require().userId();
        if (!presenceRegistry.touchUser(id, userId, request.targetElementId())) {
            // Presence is ephemeral and a stray report must never materialize it.
            return;
        }
        presencePublisher.publishSnapshot(id);
    }

    public record PresenceRequest(UUID targetElementId) {
    }
}
