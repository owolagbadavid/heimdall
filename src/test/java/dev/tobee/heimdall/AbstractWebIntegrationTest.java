package dev.tobee.heimdall;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Extends AbstractIntegrationTest with a WebTestClient bound to the running
 * server port. Only HTTP controller tests should extend this class; gRPC and
 * repository tests extend AbstractIntegrationTest directly to avoid triggering
 * a spring-grpc-test reflection issue on the WebTestClient class name.
 */
public abstract class AbstractWebIntegrationTest extends AbstractIntegrationTest {

    protected WebTestClient webTestClient;

    @BeforeEach
    void initWebTestClient() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + serverPort)
                .build();
    }
}
