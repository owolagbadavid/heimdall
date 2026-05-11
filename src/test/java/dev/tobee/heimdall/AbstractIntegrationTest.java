package dev.tobee.heimdall;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for all integration tests.
 *
 * Starts PostgreSQL and Redis containers once (static block) and wires their
 * ports into the Spring Environment via @DynamicPropertySource before the
 * context is created. Spring's test-context cache reuses the same application
 * context for all subclasses that share the same @SpringBootTest configuration,
 * so containers start exactly once per test-suite run.
 *
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> "r2dbc:postgresql://"
                + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName());
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("REDIS_HOST", REDIS::getHost);
        registry.add("REDIS_PORT", () -> String.valueOf(REDIS.getMappedPort(6379)));
        registry.add("REDIS_PASSWORD", () -> "");
        registry.add("REDIS_USERNAME", () -> "");
        registry.add("SQL_INIT_MODE", () -> "always");
        registry.add("GRPC_PORT", () -> "9091");
    }

    @LocalServerPort
    protected int serverPort;
}
