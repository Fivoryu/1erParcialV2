package com.diagramador.assistant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ApiExceptionHandler;
import com.diagramador.api.ModelResource;
import com.diagramador.api.ProjectApiService;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AssistantPlanControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final String OPERATOR_TOKEN = "operator-test-token";

    private final IdentityService identityService = mock(IdentityService.class);
    private final CurrentUserContext currentUserContext = new CurrentUserContext();
    private final ProjectMemberRepository projectMemberRepository = mock(ProjectMemberRepository.class);
    private final ProjectApiService projectApiService = mock(ProjectApiService.class);
    private final AtomicBoolean providerDisabled = new AtomicBoolean();
    private MockMvc mockMvc;
    private byte[] requestBody;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        requestBody = objectMapper.writeValueAsBytes(java.util.Map.of("utterance", "Explicame el modelo",
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

        ModelResource model = new ModelResource(UUID.randomUUID(), PROJECT_ID, 7L, UUID.randomUUID(), List.of(),
                List.of(), List.of());
        when(projectApiService.getModel(PROJECT_ID)).thenReturn(model);
        ProjectAuthorizationService authorizationService =
                new ProjectAuthorizationService(projectMemberRepository, currentUserContext);
        AiAssistantProvider provider = (context, utterance) -> {
            if (providerDisabled.get()) {
                throw new IllegalStateException("AI provider disabled");
            }
            return "{\"intent\":\"explain\",\"operations\":[],\"question\":null,"
                    + "\"explanation\":\"Te explico el modelo.\"}";
        };
        AssistantPlanService planService = new AssistantPlanService(projectApiService, authorizationService, provider,
                new AssistantPlanValidator(), objectMapper);
        AssistantPlanController controller = new AssistantPlanController(planService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthenticationInterceptor(identityService, currentUserContext, OPERATOR_TOKEN))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(planRequest())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanRequestAPlan() throws Exception {
        mockMvc.perform(planRequest().header(HttpHeaders.AUTHORIZATION, "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("EXPLAIN"))
                .andExpect(jsonPath("$.explanation").value("Te explico el modelo."));
    }

    @Test
    void nonMemberIsForbidden() throws Exception {
        mockMvc.perform(planRequest().header(HttpHeaders.AUTHORIZATION, "Bearer outsider-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operatorPrincipalCannotReachThePlanRoute() throws Exception {
        mockMvc.perform(planRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void disabledProviderReturnsHttp503WithDiagnostic() throws Exception {
        providerDisabled.set(true);

        mockMvc.perform(planRequest().header(HttpHeaders.AUTHORIZATION, "Bearer owner-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.intent").value("REJECT"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("PROVIDER_DISABLED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder planRequest() {
        return post("/projects/{projectId}/assistant/plan", PROJECT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody);
    }
}
