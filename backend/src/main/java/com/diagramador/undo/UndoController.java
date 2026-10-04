package com.diagramador.undo;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** IA4-04 endpoints; the operator is excluded by the interceptor whitelist, as with commands. */
@RestController
@RequestMapping(path = "/projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class UndoController {

    private final UndoService undoService;

    public UndoController(UndoService undoService) {
        this.undoService = undoService;
    }

    @PostMapping(path = "/{projectId}/undo")
    public ResponseEntity<UndoResult> undo(@PathVariable("projectId") UUID projectId) {
        return ResponseEntity.ok(undoService.undo(projectId));
    }

    @PostMapping(path = "/{projectId}/redo")
    public ResponseEntity<UndoResult> redo(@PathVariable("projectId") UUID projectId) {
        return ResponseEntity.ok(undoService.redo(projectId));
    }
}
