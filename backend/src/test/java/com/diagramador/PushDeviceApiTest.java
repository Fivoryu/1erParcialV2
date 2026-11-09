package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class PushDeviceApiTest extends AuthenticatedApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void acceptsMaximumLengthTokenWithoutIndexFailure() throws Exception {
        String user = "push-" + UUID.randomUUID();
        StringBuilder token = new StringBuilder();
        while (token.length() < 4096) token.append(UUID.randomUUID().toString().replace("-", ""));
        byte[] body = json.writeValueAsBytes(Map.of("token", token.toString()));
        mvc.perform(authorizedAs(user, post("/push/devices").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isNoContent());
    }

    @Test
    void registrationRequiresUserAndPersistsOnlyValidToken() throws Exception {
        String user = "push-" + UUID.randomUUID();
        String token = "synthetic-" + UUID.randomUUID();
        String path = "/push/devices";
        byte[] valid = json.writeValueAsBytes(Map.of("token", token));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        mvc.perform(authorizedAs(user, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("token", "  ")))))
                .andExpect(status().isBadRequest());
        var result = mvc.perform(authorizedAs(user, post(path).contentType(MediaType.APPLICATION_JSON).content(valid)))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        assertThat(jdbc.queryForObject("select count(*) from diagrammer.push_device where user_id = ? and token = ?",
                Long.class, userIdFor(user), token)).isEqualTo(1L);
        mvc.perform(authorizedAs(user, post(path).contentType(MediaType.APPLICATION_JSON).content(valid)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from diagrammer.push_device where user_id = ? and token = ?",
                Long.class, userIdFor(user), token)).isEqualTo(1L);
        String other = "push-" + UUID.randomUUID();
        mvc.perform(authorizedAs(other, post(path).contentType(MediaType.APPLICATION_JSON).content(valid)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from diagrammer.push_device where token = ? and user_id = ?",
                Long.class, token, userIdFor(user))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from diagrammer.push_device where token = ? and user_id = ?",
                Long.class, token, userIdFor(other))).isZero();
    }
}
