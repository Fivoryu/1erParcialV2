package com.diagramador.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.diagramador.identity.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/** Regression probe incorporated from the independent verifier. */
class MobileAgentOperationAuditTest extends MobileAgentOperationControllerTest {
    @Test void authenticatedInvalidIntentProducesMetadataAuditWithoutDispatch() throws Exception {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174099");
        var user = new CurrentUser(id, "fixture", "Fixture");
        when(identities.resolve("synthetic")).thenReturn(user);
        when(users.optional()).thenReturn(Optional.of(user));
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(post("/projects/" + id + "/mobile/backends/" + id + "/agent/operations")
                    .header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"operationId\":\"" + id + "\",\"contractHash\":\"" + "a".repeat(64)
                            + "\",\"method\":\"PATCH\",\"path\":\"/items\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(service);
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("mobile_agent_operation") || message.startsWith("mobile_gateway"))
                    .toList()).as("one authenticated invalid-envelope rejection audit").hasSize(1);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
