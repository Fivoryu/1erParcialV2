package com.diagramador.api;

import com.diagramador.collaboration.ChatProposalService;
import com.diagramador.collaboration.ChatProposalService.ProposalResource;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Chat suggestions: the assistant proposes a plan, an editor accepts or rejects it (S6). */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatProposalController {

    private final ChatProposalService proposalService;

    public ChatProposalController(ChatProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @GetMapping("/projects/{projectId}/sessions/{sessionId}/proposals")
    public List<ProposalResource> list(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId) {
        return proposalService.list(projectId, sessionId);
    }

    @PostMapping(path = "/projects/{projectId}/sessions/{sessionId}/proposals",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProposalResource> propose(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId, @RequestBody ProposeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(proposalService.propose(projectId, sessionId, request == null ? null : request.utterance()));
    }

    @PostMapping("/projects/{projectId}/sessions/{sessionId}/proposals/{proposalId}/accept")
    public ProposalResource accept(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId, @PathVariable("proposalId") UUID proposalId) {
        return proposalService.accept(projectId, sessionId, proposalId);
    }

    @PostMapping("/projects/{projectId}/sessions/{sessionId}/proposals/{proposalId}/reject")
    public ProposalResource reject(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId, @PathVariable("proposalId") UUID proposalId) {
        return proposalService.reject(projectId, sessionId, proposalId);
    }

    public record ProposeRequest(String utterance) {
    }
}
