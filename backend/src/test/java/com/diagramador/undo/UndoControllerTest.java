package com.diagramador.undo;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UndoControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final String OPERATOR_TOKEN = "operator-test-token";

    private final IdentityService identityService = mock(IdentityService.class);
    private final CurrentUserContext currentUserContext = new CurrentUserContext();
    private final ProjectMemberRepository projectMemberRepository = mock(ProjectMemberRepository.class);
    private final UndoService undoService = mock(UndoService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
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

        when(undoService.undo(eq(PROJECT_ID))).thenAnswer(invocation -> {
            // Mirror the real service: the membership guard lives inside the service.
            CurrentUser user = currentUserContext.optional().orElse(null);
            if (user == null || !user.userId().equals(OWNER_ID)) {
                throw new com.diagramador.api.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "FORBIDDEN", "The user is not a member of the project.", List.of(), null);
            }
            return UndoResult.accepted(new com.diagramador.command.CommandResult(UUID.randomUUID(),
                    com.diagramador.command.CommandStatus.ACCEPTED, 2L, List.of(), null));
        });
        UndoController controller = new UndoController(undoService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthenticationInterceptor(identityService, currentUserContext, OPERATOR_TOKEN))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(undoRequest()).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanUndo() throws Exception {
        mockMvc.perform(undoRequest().header(HttpHeaders.AUTHORIZATION, "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.resultRevision").value(2));
        verify(undoService).undo(eq(PROJECT_ID));
    }

    @Test
    void nonMemberIsForbidden() throws Exception {
        mockMvc.perform(undoRequest().header(HttpHeaders.AUTHORIZATION, "Bearer outsider-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operatorPrincipalCannotReachTheUndoRoute() throws Exception {
        mockMvc.perform(undoRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void redoRouteIsExposedWithTheSameConventions() throws Exception {
        when(undoService.redo(eq(PROJECT_ID)))
                .thenReturn(UndoResult.rejected("NOTHING_TO_REDO", "No hay acciones para rehacer."));

        mockMvc.perform(redoRequest().header(HttpHeaders.AUTHORIZATION, "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.code").value("NOTHING_TO_REDO"));
        verify(undoService).redo(eq(PROJECT_ID));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder undoRequest() {
        return post("/projects/{projectId}/undo", PROJECT_ID);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder redoRequest() {
        return post("/projects/{projectId}/redo", PROJECT_ID);
    }
}
