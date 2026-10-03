package com.diagramador.api;

import com.diagramador.collaboration.PresencePublisher;
import com.diagramador.collaboration.PresenceRegistry;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.List;
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
        boolean hasPartialCursor = request.cursorXPresent() != request.cursorYPresent()
                || (request.cursorX() != null) != (request.cursorY() != null);
        if (hasPartialCursor) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "Cursor coordinates must be provided together.", List.of(), null);
        }
        boolean hasCursor = request.cursorX() != null && request.cursorY() != null;
        boolean hasTarget = !hasCursor || request.targetElementIdPresent();
        boolean cursorUpdated = hasCursor && presenceRegistry.moveCursor(
                id, userId, request.cursorX(), request.cursorY());
        boolean targetUpdated = hasTarget && presenceRegistry.touchUser(id, userId, request.targetElementId());
        if (!cursorUpdated && !targetUpdated) {
            // No live session is a no-op that creates nothing.
            return;
        }
        if (hasTarget) {
            presencePublisher.publishSnapshot(id);
        } else {
            presencePublisher.publishCursor(id, userId, request.cursorX(), request.cursorY());
        }
    }

    public static final class PresenceRequest {

        private UUID targetElementId;
        private Double cursorX;
        private Double cursorY;
        private boolean targetElementIdPresent;
        private boolean cursorXPresent;
        private boolean cursorYPresent;

        public UUID targetElementId() {
            return targetElementId;
        }

        public Double cursorX() {
            return cursorX;
        }

        public Double cursorY() {
            return cursorY;
        }

        public boolean targetElementIdPresent() {
            return targetElementIdPresent;
        }

        public boolean cursorXPresent() {
            return cursorXPresent;
        }

        public boolean cursorYPresent() {
            return cursorYPresent;
        }

        @JsonSetter("targetElementId")
        public void setTargetElementId(UUID targetElementId) {
            this.targetElementIdPresent = true;
            this.targetElementId = targetElementId;
        }

        @JsonSetter("cursorX")
        public void setCursorX(Double cursorX) {
            this.cursorXPresent = true;
            this.cursorX = cursorX;
        }

        @JsonSetter("cursorY")
        public void setCursorY(Double cursorY) {
            this.cursorYPresent = true;
            this.cursorY = cursorY;
        }
    }
}
