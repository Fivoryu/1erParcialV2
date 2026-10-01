package com.diagramador;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
abstract class AuthenticatedApiTest extends AbstractPostgresIntegrationTest {

    private static final String TEST_PASSWORD = "integration-password";

    @Autowired
    private MockMvc authenticationMockMvc;

    @Autowired
    private ObjectMapper authenticationObjectMapper;

    private final Map<String, String> tokensByUsername = new HashMap<>();
    private final String defaultUsername = "test-" + UUID.randomUUID();

    protected synchronized String bearer(String username) throws Exception {
        String existingToken = tokensByUsername.get(username);
        if (existingToken != null) {
            return existingToken;
        }

        byte[] registration = authenticationObjectMapper.writeValueAsBytes(Map.of(
                "username", username,
                "password", TEST_PASSWORD,
                "displayName", "Integration Test"));
        authenticationMockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration))
                .andExpect(status().isCreated());

        byte[] login = authenticationObjectMapper.writeValueAsBytes(Map.of(
                "username", username,
                "password", TEST_PASSWORD));
        MvcResult loginResult = authenticationMockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(login))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = authenticationObjectMapper.readTree(loginResult.getResponse().getContentAsByteArray());
        String token = response.path("token").asText();
        if (token.isBlank()) {
            throw new AssertionError("Login response did not include a bearer token");
        }
        tokensByUsername.put(username, token);
        return token;
    }

    protected MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) throws Exception {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(defaultUsername));
    }
}
