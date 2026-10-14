package com.diagramador.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import com.diagramador.mobile.agent.MobileAgentOperationResources;
import com.diagramador.mobile.agent.MobileAgentOperationResources.Request;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MobileAgentOperationTest {
    @Test void confirmationAndObjectFieldOrderDoNotChangeIntent() throws Exception {
        var json = new ObjectMapper();
        var id = UUID.randomUUID();
        var a = new Request(id, "a".repeat(64), "POST", "/items", json.readTree("{\"b\":2,\"a\":1}"), null);
        var b = new Request(id, a.contractHash(), a.method(), a.path(), json.readTree("{\"a\":1,\"b\":2}"), "confirmation");
        assertThat(MobileAgentOperationResources.canonical(json, a))
                .isEqualTo(MobileAgentOperationResources.canonical(json, b));
    }
}
