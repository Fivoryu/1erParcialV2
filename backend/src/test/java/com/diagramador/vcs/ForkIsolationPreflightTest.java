package com.diagramador.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "T4_ISOLATED_HOME", matches = ".+")
class ForkIsolationPreflightTest {
    @Test
    void surefireUsesIsolatedHomeAndDockerConfig() {
        String expected = System.getenv("T4_ISOLATED_HOME");
        assertNotNull(expected);
        assertEquals(expected, System.getenv("HOME"));
        assertEquals(expected, System.getProperty("user.home"));
        assertEquals(expected, System.getenv("DOCKER_CONFIG"));
        assertTrue(Files.isDirectory(Path.of(expected)));
        assertTrue(ProcessHandle.current().parent().isPresent());
    }
}
