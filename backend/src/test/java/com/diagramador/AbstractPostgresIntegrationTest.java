package com.diagramador;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts one PostgreSQL 16 container for every integration test that extends this class.
 *
 * <p>The image tag is pinned to a locally available image so the build works offline, and the
 * datasource is wired through {@code @DynamicPropertySource} instead of {@code @ServiceConnection}.
 */
abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    // The suite shares one container across many Spring contexts (one Hikari pool
                    // each); the default max_connections=100 exhausted with ~30 contexts.
                    .withCommand("postgres -c max_connections=300");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Small per-context pools: contexts are cached per unique configuration, so the sum stays
        // far below the container's max_connections.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }
}
