package com.diagramador.mobile.chat;
import static com.diagramador.mobile.chat.MobileAgentChatResources.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping(value="/projects/{projectId}/mobile/backends/{deploymentId}/chats", produces="application/json")
public class MobileAgentChatController {
    private final MobileAgentChatService service;
    public MobileAgentChatController(MobileAgentChatService service) { this.service = service; }
    @PostMapping(consumes="application/json")
    public ConversationResource create(@PathVariable UUID projectId, @PathVariable UUID deploymentId, @Valid @RequestBody ConversationRequest request) {
        return service.create(projectId, deploymentId, request);
    }
    @GetMapping
    public PageResource<ConversationResource> list(@PathVariable UUID projectId, @PathVariable UUID deploymentId,
            @RequestParam(required=false) UUID cursor, @RequestParam(defaultValue="50") int limit) {
        return service.list(projectId, deploymentId, cursor, limit);
    }
    @GetMapping("/{id}/messages")
    public PageResource<MessageResource> history(@PathVariable UUID projectId, @PathVariable UUID deploymentId, @PathVariable UUID id,
            @RequestParam(defaultValue="0") long afterSequence, @RequestParam(defaultValue="50") int limit) {
        return service.history(projectId, deploymentId, id, afterSequence, limit);
    }
    @PostMapping(path="/{id}/messages", consumes="application/json")
    public MessageResource append(@PathVariable UUID projectId, @PathVariable UUID deploymentId, @PathVariable UUID id,
            @Valid @RequestBody MessageRequest request) { return service.append(projectId, deploymentId, id, request); }
}
