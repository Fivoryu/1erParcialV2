package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class EditorCorsPreflightTest extends AbstractPostgresIntegrationTest {

    private static final String EDITOR_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowsPatchAndDeletePreflightsFromTheEditorOrigin() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        assertPreflightAllows(projectId, memberId, "PATCH");
        assertPreflightAllows(projectId, memberId, "DELETE");
    }

    private void assertPreflightAllows(UUID projectId, UUID memberId, String requestedMethod) throws Exception {
        MvcResult result = mockMvc.perform(options("/projects/{projectId}/members/{memberId}", projectId, memberId)
                .header(HttpHeaders.ORIGIN, EDITOR_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, requestedMethod))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isIn(200, 204);
        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(EDITOR_ORIGIN);
        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .contains("PATCH", "DELETE")
                .doesNotContain("PUT");
    }
}
