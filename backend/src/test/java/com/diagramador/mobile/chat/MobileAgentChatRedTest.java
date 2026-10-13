package com.diagramador.mobile.chat;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.diagramador.api.ApiExceptionHandler;
import com.diagramador.domain.*;
import com.diagramador.identity.*;
import com.diagramador.generation.*;
import com.diagramador.persistence.DeploymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MobileAgentChatRedTest {
    UUID project=UUID.randomUUID(), deployment=UUID.randomUUID(), account=UUID.randomUUID(), chat=UUID.randomUUID(), message=UUID.randomUUID();
    String hash="a".repeat(64);
    CurrentUserContext users=new CurrentUserContext();
    AgentConversationRepository conversations=mock(AgentConversationRepository.class);
    AgentMessageRepository messages=mock(AgentMessageRepository.class);
    DeploymentRepository deployments=mock(DeploymentRepository.class);
    ProjectAuthorizationService authorization=mock(ProjectAuthorizationService.class);
    GenerationArtifactService artifacts=mock(GenerationArtifactService.class);
    InputSnapshotFactory snapshots=mock(InputSnapshotFactory.class);
    EntityManager entityManager=mock(EntityManager.class);
    MockMvc mvc;
    AgentConversation owned;
    String base;
    @BeforeEach void setup() {
        var target=mock(Deployment.class, RETURNS_DEEP_STUBS);
        when(target.getRun().getModel().getProject().getId()).thenReturn(project);
        when(target.getRun().getArtifactSetHash()).thenReturn(hash);
        when(deployments.findById(deployment)).thenReturn(Optional.of(target));
        var query=mock(jakarta.persistence.Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        owned=new AgentConversation(chat, account, project, deployment, hash, "Chat");
        when(conversations.findById(chat)).thenReturn(Optional.of(owned));
        when(conversations.lockById(chat)).thenReturn(Optional.of(owned));
        when(messages.findById(any())).thenReturn(Optional.empty());
        when(messages.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var service=new MobileAgentChatService(conversations,messages,users,authorization,deployments,artifacts,snapshots,new ObjectMapper(),entityManager);
        mvc=MockMvcBuilders.standaloneSetup(new MobileAgentChatController(service))
                .setControllerAdvice(new MobileAgentChatBodyAdvice(),new ApiExceptionHandler()).build();
        base="/projects/"+project+"/mobile/backends/"+deployment+"/chats";
        users.set(new CurrentUser(account,"fixture","Fixture"));
    }
    @Test void privateHistoryRequiresIdentity() throws Exception {
        users.clear(); mvc.perform(get(base)).andExpect(status().isUnauthorized()); verifyNoInteractions(conversations,messages);
    }
    @Test void stableConversationCreationHasPublicEndpoint() throws Exception {
        String request="{\"id\":\""+chat+"\",\"title\":\"Chat\",\"contractHash\":\""+hash+"\"}";
        for(int i=0;i<2;i++) mvc.perform(post(base).contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(chat.toString()));
        verify(conversations,never()).saveAndFlush(any());
    }
    @Test void foreignCollaboratorCannotReadOrAppend() throws Exception {
        users.set(new CurrentUser(UUID.randomUUID(),"collaborator","Collaborator"));
        mvc.perform(get(base+"/"+chat+"/messages")).andExpect(status().isNotFound());
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(payload("hello"))).andExpect(status().isNotFound());
        verifyNoInteractions(messages);
    }
    @Test void appendRetryReturnsOriginalAndDifferentPayloadConflicts() throws Exception {
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(payload("hello")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1));
        var original=new AgentMessage(owned,new MobileAgentChatResources.MessageRequest(message,"user","hello",null,null));
        original.sequence=1;
        when(messages.findById(message)).thenReturn(Optional.of(original));
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(payload("hello")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1));
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(payload("different")))
                .andExpect(status().isConflict());
        verify(messages,times(1)).saveAndFlush(any());
    }
    @Test void foreignDeploymentAndRemovedMembershipRejectBeforeHistory() throws Exception {
        when(deployments.findById(deployment)).thenReturn(Optional.empty());
        mvc.perform(get(base)).andExpect(status().isNotFound());
        verifyNoInteractions(conversations,messages);
        doThrow(new com.diagramador.api.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"FORBIDDEN","Denied",List.of(),null))
                .when(authorization).requireMember(project);
        mvc.perform(get(base)).andExpect(status().isForbidden());
    }
    @Test void historyIsBoundedAndOrderedWithoutUpRequirement() throws Exception {
        when(messages.findByConversationIdAndSequenceGreaterThanOrderBySequenceAsc(eq(chat),eq(0L),any())).thenReturn(List.of());
        mvc.perform(get(base+"/"+chat+"/messages")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get(base+"/"+chat+"/messages?limit=101")).andExpect(status().isBadRequest());
        mvc.perform(get(base+"/"+chat+"/messages?afterSequence=-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(artifacts);
    }
    @Test void rejectsInvalidRolesAndOversizedBody() throws Exception {
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(payload("hello").replace("user","system")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(payload("x".repeat(65537))))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(messages);
    }
    @Test void conversationChangedPayloadConflictsAndForeignCreationIsHidden() throws Exception {
        String request="{\"id\":\""+chat+"\",\"title\":\"Changed\",\"contractHash\":\""+hash+"\"}";
        mvc.perform(post(base).contentType("application/json").content(request)).andExpect(status().isConflict());
        users.set(new CurrentUser(UUID.randomUUID(),"other","Other"));
        mvc.perform(post(base).contentType("application/json").content(request)).andExpect(status().isNotFound());
        verify(conversations,never()).saveAndFlush(any());
    }
    @Test void newConversationRequiresMatchingAvailableFrozenArtifact() throws Exception {
        UUID fresh=UUID.randomUUID();
        when(conversations.findById(fresh)).thenReturn(Optional.empty());
        when(conversations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var bundle=mock(GenerationArtifactBundleBuilder.GeneratedArtifactBundle.class);
        when(bundle.files()).thenReturn(Map.of(GenerationArtifactBundleBuilder.OPENAPI_PATH,"{\"openapi\":\"3.0.3\",\"paths\":{}}"));
        when(artifacts.downloadComplete(any(),any())).thenReturn(Optional.of(bundle));
        String request="{\"id\":\""+fresh+"\",\"title\":\"New chat\",\"contractHash\":\""+hash+"\"}";
        mvc.perform(post(base).contentType("application/json").content(request.replace(hash,"b".repeat(64))))
                .andExpect(status().isConflict());
        verify(conversations,never()).saveAndFlush(any());
        mvc.perform(post(base).contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contractHash").value(hash));
        when(artifacts.downloadComplete(any(),any())).thenReturn(Optional.empty());
        mvc.perform(post(base).contentType("application/json").content(request)).andExpect(status().isConflict());
        verify(conversations,times(1)).saveAndFlush(any());
    }
    @Test void chunkedBodyAndMetadataAreBoundedWithoutPersistence() throws Exception {
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(payload("x".repeat(65537)))
                .with(request -> { request.removeHeader("Content-Length"); return request; })).andExpect(status().isPayloadTooLarge());
        String request=payload("hello").replace("}",",\"metadata\":{\"value\":\""+"x".repeat(4097)+"\"}}");
        mvc.perform(post(base+"/"+chat+"/messages").contentType("application/json").content(request)).andExpect(status().isBadRequest());
        verifyNoInteractions(messages);
    }
    private String payload(String content) { return "{\"id\":\""+message+"\",\"role\":\"user\",\"content\":\""+content+"\"}"; }
}
