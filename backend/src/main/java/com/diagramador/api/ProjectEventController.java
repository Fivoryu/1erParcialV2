package com.diagramador.api;

import com.diagramador.collaboration.PresencePublisher;
import com.diagramador.collaboration.PresenceRegistry;
import com.diagramador.collaboration.ProjectEventBus;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ProjectEventController {

    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectEventBus projectEventBus;
    private final PresenceRegistry presenceRegistry;
    private final PresencePublisher presencePublisher;
    private final CurrentUserContext currentUserContext;

    public ProjectEventController(ProjectAuthorizationService projectAuthorizationService,
            ProjectEventBus projectEventBus, PresenceRegistry presenceRegistry,
            PresencePublisher presencePublisher, CurrentUserContext currentUserContext) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectEventBus = projectEventBus;
        this.presenceRegistry = presenceRegistry;
        this.presencePublisher = presencePublisher;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping(path = "/projects/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") UUID id) {
        projectAuthorizationService.requireMember(id);
        SseEmitter emitter = projectEventBus.subscribe(id);
        UUID sessionId = UUID.randomUUID();
        AtomicBoolean ended = new AtomicBoolean();
        emitter.onCompletion(() -> endSession(id, sessionId, ended));
        emitter.onTimeout(() -> endSession(id, sessionId, ended));
        emitter.onError(error -> endSession(id, sessionId, ended));

        CurrentUser user = currentUserContext.require();
        presenceRegistry.join(id, sessionId, user.userId(), user.displayName());
        presencePublisher.publishSnapshot(id);
        return emitter;
    }

    private void endSession(UUID projectId, UUID sessionId, AtomicBoolean ended) {
        // Multiple terminal callbacks can describe one ending; publish only once per subscription.
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        presenceRegistry.leave(projectId, sessionId);
        presencePublisher.publishSnapshot(projectId);
    }
}
