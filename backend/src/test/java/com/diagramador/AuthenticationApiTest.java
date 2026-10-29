package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class AuthenticationApiTest extends AuthenticatedApiTest {

    private static final String PASSWORD = "secure-password-123";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    AuthenticationApiTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @Test
    void registrationReturnsOnlyThePublicUserFieldsAndLoginReturnsAToken() throws Exception {
        String username = uniqueUsername();
        JsonNode registered = register(username, PASSWORD);

        assertThat(registered.path("id").asText()).isNotBlank();
        assertThat(registered.path("username").asText()).isEqualTo(username);
        assertThat(registered.path("displayName").asText()).isEqualTo("Test User");
        assertThat(registered.has("passwordHash")).isFalse();
        assertThat(registered.has("externalSubject")).isFalse();

        JsonNode login = login(username, PASSWORD);
        assertThat(login.path("token").asText()).isNotBlank();
        assertThat(login.path("expiresAt").asText()).isNotBlank();
        assertThat(login.path("user").path("id").asText()).isEqualTo(registered.path("id").asText());
        assertThat(login.toString()).doesNotContain("passwordHash", "externalSubject");
    }

    @Test
    void meRequiresATokenAndReturnsTheAuthenticatedUser() throws Exception {
        String username = uniqueUsername();
        JsonNode registered = register(username, PASSWORD);
        String token = login(username, PASSWORD).path("token").asText();

        JsonNode currentUser = objectMapper.readTree(mockMvc.perform(get("/auth/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(currentUser.path("id").asText()).isEqualTo(registered.path("id").asText());
        assertThat(currentUser.path("username").asText()).isEqualTo(username);
        assertThat(currentUser.path("displayName").asText()).isEqualTo("Test User");

        JsonNode error = error(mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized()).andReturn());
        assertThat(error.path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void logoutRevokesTheBearerToken() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD);
        String token = login(username, PASSWORD).path("token").asText();

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        JsonNode error = error(mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andReturn());
        assertThat(error.path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD);

        JsonNode error = error(mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(username, PASSWORD)))
                .andExpect(status().isConflict())
                .andReturn());
        assertThat(error.path("code").asText()).isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void badPasswordReturnsTheGenericCredentialError() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD);

        JsonNode error = error(mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", username, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn());
        assertThat(error.path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(error.path("message").asText()).isEqualTo("El usuario o la contraseña son incorrectos.");
    }

    @Test
    void projectsAreProtectedWhenNoTokenIsProvided() throws Exception {
        JsonNode error = error(mockMvc.perform(get("/projects"))
                .andExpect(status().isUnauthorized())
                .andReturn());
        assertThat(error.path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(error.has("details")).isTrue();
        assertThat(error.has("operationId")).isTrue();
    }

    @Test
    void projectAccessRequiresAnActiveMembership() throws Exception {
        String owner = uniqueUsername();
        register(owner, PASSWORD);
        String ownerToken = login(owner, PASSWORD).path("token").asText();
        JsonNode project = objectMapper.readTree(mockMvc.perform(post("/projects")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Private " + UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());

        String other = uniqueUsername();
        register(other, PASSWORD);
        String otherToken = login(other, PASSWORD).path("token").asText();
        JsonNode list = objectMapper.readTree(mockMvc.perform(get("/projects")
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(list).isEmpty();

        JsonNode error = error(mockMvc.perform(get("/projects/{id}", project.path("id").asText())
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andReturn());
        assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(error.path("message").asText()).isEqualTo("No tienes acceso a este proyecto.");
    }

    private JsonNode register(String username, String password) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(username, password)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode login(String username, String password) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private byte[] registrationBody(String username, String password) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);
        request.put("displayName", "Test User");
        request.put("email", username + "@example.test");
        return objectMapper.writeValueAsBytes(request);
    }

    private JsonNode error(MvcResult result) throws Exception {
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.has("code")).isTrue();
        assertThat(error.has("message")).isTrue();
        assertThat(error.path("details").isArray()).isTrue();
        assertThat(error.has("operationId")).isTrue();
        return error;
    }

    private static String uniqueUsername() {
        return "api-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
