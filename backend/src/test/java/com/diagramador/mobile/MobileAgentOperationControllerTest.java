package com.diagramador.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ApiException;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.IdentityService;
import com.diagramador.mobile.agent.MobileAgentOperationController;
import com.diagramador.mobile.agent.MobileAgentOperationResources.Receipt;
import com.diagramador.mobile.agent.MobileAgentOperationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MobileAgentOperationController.class)
class MobileAgentOperationControllerTest {
    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174099");
    private static final String ROOT = "/projects/" + ID + "/mobile/backends/" + ID + "/agent/operations";
    private static final String HASH = "a".repeat(64);
    @Autowired MockMvc mvc;
    @MockBean MobileAgentOperationService service;
    @MockBean IdentityService identities;
    @MockBean CurrentUserContext users;

    private void authenticated() { when(identities.resolve("synthetic")).thenReturn(new CurrentUser(ID, "fixture", "Fixture")); }
    private String request(String method, String token) {
        return "{\"operationId\":\"" + ID + "\",\"contractHash\":\"" + HASH + "\",\"method\":\"" + method
                + "\",\"path\":\"/items/" + ID + "\",\"confirmationToken\":" + token + "}";
    }

    @Test void additiveSubmitAndReceiptLookupUseTheStructuredContract() throws Exception {
        authenticated();
        var preview = new Receipt(ID, HASH, "DELETE", "/items/" + ID, null, "awaiting_confirmation", "b".repeat(64), null, null, null);
        when(service.submit(eq(ID), eq(ID), any())).thenReturn(preview);
        when(service.lookup(ID, ID, ID, HASH)).thenReturn(preview);
        mvc.perform(post(ROOT).header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON)
                .content(request("DELETE", "null"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("awaiting_confirmation"))
                .andExpect(jsonPath("$.confirmationToken").value("b".repeat(64)));
        mvc.perform(get(ROOT + "/" + ID).param("contractHash", HASH).header("Authorization", "Bearer synthetic"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(ID.toString()));
        verify(service).lookup(ID, ID, ID, HASH);
    }

    @Test void unauthenticatedAndExpiredRequestsNeverReachTheAgentService() throws Exception {
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(request("DELETE", "null")))
                .andExpect(status().isUnauthorized());
        when(identities.resolve("expired")).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Expired", List.of(), null));
        mvc.perform(get(ROOT + "/" + ID).param("contractHash", HASH).header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void invalidEnvelopeAndOversizedContentNeverReachTheAgentService() throws Exception {
        authenticated();
        mvc.perform(post(ROOT).header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON)
                .content(request("PATCH", "null"))).andExpect(status().isBadRequest());
        String oversized = "{\"content\":\"" + "x".repeat(66_000) + "\"}";
        mvc.perform(post(ROOT).header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON)
                .content(oversized)).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(service);
    }

    @Test void chunkedOversizedContentIsRejectedBeforeParsing() throws Exception {
        authenticated();
        String oversized = "{\"content\":\"" + "x".repeat(66_000) + "\"}";
        mvc.perform(post(ROOT).header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON)
                .content(oversized).with(request -> {
                    return new org.springframework.mock.web.MockHttpServletRequest(request.getServletContext()) {
                        { setMethod(request.getMethod()); setRequestURI(request.getRequestURI());
                            setContent(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            setContentType(MediaType.APPLICATION_JSON_VALUE); addHeader("Authorization", "Bearer synthetic"); }
                        @Override public int getContentLength() { return -1; }
                        @Override public long getContentLengthLong() { return -1; }
                    };
                })).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(service);
    }
}
