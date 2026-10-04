package com.diagramador.assistant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ApiExceptionHandler;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.identity.AuthenticationInterceptor;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.IdentityService;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ProjectMemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AssistantApplyControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final String OPERATOR_TOKEN = "operator-test-token";

    private final IdentityService identityService = mock(IdentityService.class);
    private final CurrentUserContext currentUserContext = new CurrentUserContext();
    private final ProjectMemberRepository projectMemberRepository = mock(ProjectMemberRepository.class);
    private final AssistantApplyService assistantApplyService = mock(AssistantApplyService.class);
    private MockMvc mockMvc;
    private byte[] requestBody;
    private AssistantApplyResponse response;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> operation = new java.util.HashMap<>();
        operation.put("type", "classifier.create");
        operation.put("targetId", null);
        operation.put("args", Map.of("name", "Order"));
        requestBody = objectMapper.writeValueAsBytes(Map.of(
                "operations", List.of(operation),
                "baseRevision", 7L));
        when(identityService.resolve("owner-token"))
                .thenReturn(new CurrentUser(OWNER_ID, "owner", "Owner"));
        when(identityService.resolve("outsider-token"))
                .thenReturn(new CurrentUser(OUTSIDER_ID, "outsider", "Outsider"));

        ProjectMember ownerMembership = mock(ProjectMember.class);
        when(ownerMembership.getStatus()).thenReturn("ACTIVE");
        when(ownerMembership.getRole()).thenReturn(ProjectMemberRole.OWNER);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, OWNER_ID))
                .thenReturn(Optional.of(ownerMembership));
        when(projectMemberRepository.findByProjectIdAndUserId(eq(PROJECT_ID), eq(OUTSIDER_ID)))
                .thenReturn(Optional.empty());

        response = new AssistantApplyResponse(List.of(new AssistantApplyResponse.Step(0, "classifier.create", null,
                "ACCEPTED", UUID.randomUUID(), 8L, List.of())), 8L);
        when(assistantApplyService.apply(eq(PROJECT_ID), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    // Mirror the real service: the membership guard lives inside the service.
                    com.diagramador.identity.CurrentUser user = currentUserContext.optional().orElse(null);
                    if (user == null || !user.userId().equals(OWNER_ID)) {
                        throw new com.diagramador.api.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "The user is not a member of the project.", List.of(), null);
                    }
                    return response;
                });
        AssistantApplyController controller = new AssistantApplyController(assistantApplyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthenticationInterceptor(identityService, currentUserContext, OPERATOR_TOKEN))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(applyRequest()).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanApplyAPlan() throws Exception {
        mockMvc.perform(applyRequest().header(HttpHeaders.AUTHORIZATION, "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.steps[0].resultRevision").value(8))
                .andExpect(jsonPath("$.finalRevision").value(8));
        verify(assistantApplyService).apply(eq(PROJECT_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonMemberIsForbidden() throws Exception {
        mockMvc.perform(applyRequest().header(HttpHeaders.AUTHORIZATION, "Bearer outsider-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operatorPrincipalCannotReachTheApplyRoute() throws Exception {
        mockMvc.perform(applyRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder applyRequest() {
        return post("/projects/{projectId}/assistant/apply", PROJECT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody);
    }
}
