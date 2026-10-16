package com.diagramador.help;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ApiExceptionHandler;
import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.identity.AuthenticationInterceptor;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HelpGuideControllerTest {

    private static final String BEARER_TOKEN = "guide-user-token";
    private final IdentityService identityService = mock(IdentityService.class);
    private MockMvc mockMvc;
    private byte[] requestBody;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        requestBody = objectMapper.writeValueAsBytes(new HelpGuideRequest("How do I generate a backend?",
                List.of(new HelpGuideRequest.Topic("generation", "Generation", "Create and run a backend.")),
                new HelpGuideRequest.EditorState("generation", List.of("xmi", "generation"))));
        when(identityService.resolve(BEARER_TOKEN)).thenReturn(
                new CurrentUser(UUID.fromString("10000000-0000-0000-0000-000000000001"), "guide-user", "Guide User"));
        ChatAssistantProvider provider = new ChatAssistantProvider() {
            @Override
            public String chat(List<Turn> turns) {
                return "{\"schemaVersion\":1,\"answer\":\"Open Generation.\","
                        + "\"suggestedSlugs\":[\"generation\"]}";
            }

            @Override
            public String systemPrompt() {
                return "unused";
            }
        };
        HelpGuideService service = new HelpGuideService(provider, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new HelpGuideController(service))
                .addInterceptors(new AuthenticationInterceptor(identityService, new CurrentUserContext(), "operator-token"))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void requiresTheExistingBearerAuthentication() throws Exception {
        mockMvc.perform(guideRequest())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestReturnsTheVersionedGuideResponse() throws Exception {
        mockMvc.perform(guideRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.answer").value("Open Generation."))
                .andExpect(jsonPath("$.suggestedSlugs[0]").value("generation"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder guideRequest() {
        return post("/help/guide")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody);
    }
}
