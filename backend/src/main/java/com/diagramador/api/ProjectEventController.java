package com.diagramador.api;

import com.diagramador.collaboration.ProjectEventBus;
import com.diagramador.identity.ProjectAuthorizationService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ProjectEventController {

    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectEventBus projectEventBus;

    public ProjectEventController(ProjectAuthorizationService projectAuthorizationService,
            ProjectEventBus projectEventBus) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectEventBus = projectEventBus;
    }

    @GetMapping(path = "/projects/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") UUID id) {
        projectAuthorizationService.requireMember(id);
        return projectEventBus.subscribe(id);
    }
}
