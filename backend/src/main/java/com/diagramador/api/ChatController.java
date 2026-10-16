package com.diagramador.api;

import com.diagramador.api.ChatResources.ChatSessionResource;
import com.diagramador.api.ChatResources.CreateMessageRequest;
import com.diagramador.api.ChatResources.CreateSessionRequest;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.collaboration.ChatAiService;
import com.diagramador.collaboration.ChatAttachmentService;
import com.diagramador.collaboration.ChatService;
import com.diagramador.context.ContextBudgetService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private final ChatService chatService;
    private final ChatAiService chatAiService;
    private final ContextBudgetService contextBudgetService;
    private final ChatAttachmentService chatAttachmentService;

    public ChatController(ChatService chatService, ChatAiService chatAiService,
            ContextBudgetService contextBudgetService, ChatAttachmentService chatAttachmentService) {
        this.chatService = chatService;
        this.chatAiService = chatAiService;
        this.contextBudgetService = contextBudgetService;
        this.chatAttachmentService = chatAttachmentService;
    }

    @GetMapping("/projects/{id}/messages")
    public List<MessageResource> history(@PathVariable("id") UUID projectId) {
        return chatService.history(projectId);
    }

    @PostMapping(path = "/projects/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResource> postMessage(@PathVariable("id") UUID projectId,
            @RequestBody CreateMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatService.post(projectId, request == null ? null : request.body()));
    }

    // ---- Diagram-scoped sessions (PD-02/L33) ----

    @GetMapping("/projects/{projectId}/views/{viewId}/sessions")
    public List<ChatSessionResource> sessions(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId) {
        return chatService.sessions(projectId, viewId);
    }

    @PostMapping(path = "/projects/{projectId}/views/{viewId}/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatSessionResource> createSession(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId, @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatService.createSession(projectId, viewId, request == null ? null : request.title()));
    }

    @GetMapping("/projects/{projectId}/sessions/{sessionId}/messages")
    public List<MessageResource> sessionHistory(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId) {
        return chatService.sessionHistory(projectId, sessionId);
    }

    @PostMapping(path = "/projects/{projectId}/sessions/{sessionId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResource> postSessionMessage(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId, @RequestBody CreateMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatService.postToSession(projectId, sessionId,
                        request == null ? null : request.body(),
                        request == null ? null : request.attachmentIds()));
    }

    // ---- Image attachments (T8/L49) ----

    @PostMapping(path = "/projects/{projectId}/sessions/{sessionId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatAttachmentService.AttachmentResource> uploadAttachment(
            @PathVariable("projectId") UUID projectId, @PathVariable("sessionId") UUID sessionId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        // CA-31: magic bytes and size are validated here, before any inference.
        return ResponseEntity.status(HttpStatus.CREATED).body(
                chatAttachmentService.upload(projectId, sessionId, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/projects/{projectId}/sessions/{sessionId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId, @PathVariable("attachmentId") UUID attachmentId) {
        ChatAttachmentService.AttachmentBytes attachment =
                chatAttachmentService.bytesFor(projectId, sessionId, attachmentId);
        return ResponseEntity.ok()
                .header("Content-Type", attachment.contentType())
                .header("Content-Disposition", "inline; filename=\"" + attachment.filename() + "\"")
                .body(attachment.data());
    }

    /**
     * PD-06 (L34): physical per-session history delete, OWNER only. The response reports the
     * effect; the session survives empty and reusable.
     */
    @DeleteMapping("/projects/{projectId}/sessions/{sessionId}")
    public Map<String, Long> deleteSessionHistory(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId) {
        return chatService.deleteSessionHistory(projectId, sessionId);
    }

    // ---- AI asks (T3/L38) ----

    @PostMapping(path = "/projects/{projectId}/sessions/{sessionId}/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatAiService.AskAccepted> ask(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId, @RequestBody AskRequest request) {
        if (request == null || request.messageId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "A messageId is required.", List.of(), null);
        }
        // 202 Accepted: the answer is not in this response; it arrives later as a message.
        return ResponseEntity.accepted().body(chatAiService.ask(projectId, sessionId, request.messageId()));
    }

    @GetMapping("/projects/{projectId}/sessions/{sessionId}/ai-request")
    public ChatAiService.PendingResource pendingAiRequest(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId) {
        return chatAiService.pendingState(projectId, sessionId);
    }

    // ---- Context budget (T6/L45) ----

    @GetMapping("/projects/{projectId}/sessions/{sessionId}/meter")
    public ContextBudgetService.ContextReport meter(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId) {
        return contextBudgetService.meter(projectId, sessionId);
    }

    @PostMapping("/projects/{projectId}/sessions/{sessionId}/compact")
    public ContextBudgetService.CompactionOutcome compact(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId) {
        return contextBudgetService.compact(projectId, sessionId);
    }

    @DeleteMapping("/projects/{projectId}/sessions/{sessionId}/ai-request")
    public Map<String, String> cancelAiRequest(@PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId) {
        chatAiService.cancel(projectId, sessionId);
        return Map.of("state", "CANCELLED");
    }

    public record AskRequest(UUID messageId) {
    }
}
