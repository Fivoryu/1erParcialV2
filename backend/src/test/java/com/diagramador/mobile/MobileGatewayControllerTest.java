package com.diagramador.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.mobile.MobileGatewayResources.BackendResource;
import com.diagramador.mobile.MobileGatewayResources.ContractResource;
import com.diagramador.mobile.MobileGatewayResources.OperationResource;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MobileGatewayController.class)
class MobileGatewayControllerTest {
    private static final UUID PROJECT = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID DEPLOYMENT = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
    private static final UUID RUN = UUID.fromString("123e4567-e89b-12d3-a456-426614174002");
    private static final String HASH = "a".repeat(64);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private MobileGatewayService gateway;
    @MockBean private IdentityService identityService;
    @MockBean private CurrentUserContext currentUserContext;

    @Test
    void memberCanListAndReadTheFrozenContract() throws Exception {
        authenticated();
        when(gateway.list(PROJECT)).thenReturn(List.of(new BackendResource(DEPLOYMENT, "orders", "UP", RUN, HASH)));
        when(gateway.contract(PROJECT, DEPLOYMENT)).thenReturn(new ContractResource(DEPLOYMENT, RUN, HASH,
                mapper.readTree("{\"openapi\":\"3.0.3\",\"paths\":{}}")));

        mvc.perform(authorized(get("/projects/{projectId}/mobile/backends", PROJECT))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deploymentId").value(DEPLOYMENT.toString()))
                .andExpect(jsonPath("$[0].contractHash").value(HASH));
        mvc.perform(authorized(get("/projects/{projectId}/mobile/backends/{deploymentId}/contract", PROJECT, DEPLOYMENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(RUN.toString()))
                .andExpect(jsonPath("$.openApi.openapi").value("3.0.3"));
    }

    @Test
    void createEditDeleteAndReadUseOneConfirmedOperationEnvelope() throws Exception {
        authenticated();
        when(gateway.forward(eq(PROJECT), eq(DEPLOYMENT), any())).thenReturn(new OperationResource(200,
                mapper.readTree("{\"id\":\"123e4567-e89b-12d3-a456-426614174003\"}")));
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            String body = "GET".equals(method) || "DELETE".equals(method) ? "null" : "{\"name\":\"safe\"}";
            mvc.perform(authorized(post("/projects/{projectId}/mobile/backends/{deploymentId}/operations", PROJECT, DEPLOYMENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"contractHash\":\"" + HASH + "\",\"method\":\"" + method
                            + "\",\"path\":\"/orders\",\"body\":" + body + ",\"confirmed\":true}")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200));
        }
        verify(gateway, org.mockito.Mockito.times(4)).forward(eq(PROJECT), eq(DEPLOYMENT), any());
    }

    @Test
    void rejectsUnconfirmedAndUnsupportedRequestsAtThePublicBoundary() throws Exception {
        authenticated();
        mvc.perform(authorized(post("/projects/{projectId}/mobile/backends/{deploymentId}/operations", PROJECT, DEPLOYMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractHash\":\"short\",\"method\":\"PATCH\",\"path\":\"http://evil\",\"confirmed\":false}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedGatewayRequestNeverReachesTheController() throws Exception {
        mvc.perform(get("/projects/{projectId}/mobile/backends", PROJECT)).andExpect(status().isUnauthorized());
    }

    @Test
    void auditsAuthenticatedPreServiceRejectionsWithoutLoggingPayloads() throws Exception {
        CurrentUser user = new CurrentUser(UUID.fromString("123e4567-e89b-12d3-a456-426614174005"),
                "mobile-user", "Mobile User");
        when(identityService.resolve("test-token")).thenReturn(user);
        when(currentUserContext.optional()).thenReturn(java.util.Optional.of(user));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(MobileGatewayRequestBodyAdvice.class);
        var originalLevel = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(appender);
        try {
            String sensitive = "private-record-marker";
            mvc.perform(authorized(post("/projects/{projectId}/mobile/backends/{deploymentId}/operations", PROJECT, DEPLOYMENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"contractHash\":\"short\",\"method\":\"PATCH\",\"path\":\"http://evil\","
                            + "\"body\":{\"name\":\"" + sensitive + "\"},\"confirmed\":false}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"))
                    .andExpect(jsonPath("$.message").value("Request body is invalid."))
                    .andExpect(jsonPath("$.details").isArray())
                    .andDo(result -> {
                        var exception = (org.springframework.web.bind.MethodArgumentNotValidException)
                                result.getResolvedException();
                        var expected = new com.diagramador.api.ApiExceptionHandler().handleInvalidBody(exception);
                        org.assertj.core.api.Assertions.assertThat(mapper.readTree(result.getResponse().getContentAsString()))
                                .isEqualTo(mapper.valueToTree(expected.getBody()));
                    });
            String oversized = "{\"contractHash\":\"" + HASH + "\",\"method\":\"POST\",\"path\":\"/api/notes\","
                    + "\"body\":{\"name\":\"" + sensitive + "x".repeat(65_536) + "\"},\"confirmed\":true}";
            mvc.perform(authorized(post("/projects/{projectId}/mobile/backends/{deploymentId}/operations", PROJECT, DEPLOYMENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_LENGTH, oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .content(oversized)))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .json("{\"code\":\"OPERATION_BODY_TOO_LARGE\"}", true));
            mvc.perform(authorized(post("/projects/{projectId}/mobile/backends/{deploymentId}/operations", PROJECT, DEPLOYMENT)
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                    .content(oversized).with(request -> {
                        request.removeHeader(HttpHeaders.CONTENT_LENGTH);
                        return request;
                    })))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .json("{\"code\":\"OPERATION_BODY_TOO_LARGE\"}", true));
            var events = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("mobile_gateway ")).toList();
            org.assertj.core.api.Assertions.assertThat(events).hasSize(3);
            org.assertj.core.api.Assertions.assertThat(events.get(0)).contains("status=400");
            org.assertj.core.api.Assertions.assertThat(events.get(1)).contains("status=413");
            org.assertj.core.api.Assertions.assertThat(events.get(2)).contains("status=413");
            for (String event : events) {
                org.assertj.core.api.Assertions.assertThat(event).contains("userId=" + user.userId(),
                        "projectId=" + PROJECT, "deploymentId=" + DEPLOYMENT, "method=POST")
                        .doesNotContain("test-token", sensitive, "http://evil", HASH, "short", "PATCH");
            }
            verify(gateway, org.mockito.Mockito.never()).forward(any(), any(), any());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }
    private void authenticated() {
        when(identityService.resolve("test-token")).thenReturn(new CurrentUser(UUID.randomUUID(), "mobile-user", "Mobile User"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer test-token");
    }
}
