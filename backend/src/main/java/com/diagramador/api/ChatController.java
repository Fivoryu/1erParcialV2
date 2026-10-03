package com.diagramador.api;

import com.diagramador.api.ChatResources.CreateMessageRequest;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.collaboration.ChatService;
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

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
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
}
