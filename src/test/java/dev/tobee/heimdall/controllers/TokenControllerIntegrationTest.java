package dev.tobee.heimdall.controllers;

import dev.tobee.heimdall.AbstractWebIntegrationTest;
import dev.tobee.heimdall.repositories.RuleCacheRepository;
import dev.tobee.heimdall.repositories.RuleRepository;
import dev.tobee.heimdall.repositories.TokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;


class TokenControllerIntegrationTest extends AbstractWebIntegrationTest {

    @Autowired RuleCacheRepository ruleCacheRepository;
    @Autowired RuleRepository ruleRepository;
    @Autowired TokenRepository tokenRepository;

    // Unique api/op per test class run so rules don't bleed between test classes
    private static final String API = "tc-test-api";
    private static final String OP  = "tc-test-op";
    private static final String KEY = "user-99";

    @BeforeEach
    void setup() {
        ruleRepository.deleteAll().block();
        // Evict the Redis rule cache so no stale rule survives between tests
        ruleCacheRepository.evict(API, OP).block();
        // Delete the token bucket so each test starts fresh
        tokenRepository.delete("heimdall:tokens:" + API + ":" + OP + ":" + KEY).block();
        tokenRepository.delete("heimdall:tokens:" + API + ":" + OP + ":user-A").block();
        tokenRepository.delete("heimdall:tokens:" + API + ":" + OP + ":user-B").block();
    }

    @Test
    void consume_noMatchingRule_allowedFalse_remainingNegativeTwo() {
        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.allowed").isEqualTo(false)
                .jsonPath("$.remaining").isEqualTo(-2);
    }

    @Test
    void consume_ruleExists_firstRequest_allowed() {
        createRule(API, OP, 10, 60);

        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.allowed").isEqualTo(true)
                .jsonPath("$.remaining").isEqualTo(9)   // 10 - 1
                .jsonPath("$.api").isEqualTo(API)
                .jsonPath("$.op").isEqualTo(OP)
                .jsonPath("$.key").isEqualTo(KEY);
    }

    @Test
    void consume_exhaustedBucket_deniedWithNegativeOne() {
        createRule(API, OP, 2, 60);

        // Consume all tokens
        consumeOnce();
        consumeOnce();

        // Third call must be denied
        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.allowed").isEqualTo(false)
                .jsonPath("$.remaining").isEqualTo(-1);
    }

    @Test
    void consume_differentCallerKeys_trackedIndependently() {
        createRule(API, OP, 1, 60);

        // user-A: first call allowed
        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API).queryParam("op", OP)
                        .queryParam("key", "user-A").build())
                .exchange()
                .expectBody().jsonPath("$.allowed").isEqualTo(true);

        // user-B: also allowed (separate bucket)
        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API).queryParam("op", OP)
                        .queryParam("key", "user-B").build())
                .exchange()
                .expectBody().jsonPath("$.allowed").isEqualTo(true);

        // user-A: second call denied (its own bucket exhausted)
        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API).queryParam("op", OP)
                        .queryParam("key", "user-A").build())
                .exchange()
                .expectBody().jsonPath("$.allowed").isEqualTo(false);
    }

    @Test
    void remaining_noRule_returns404() {
        webTestClient.get()
                .uri(b -> b.path("/api/v1/tokens/remaining")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void remaining_unusedBucket_returnsMaxTokens() {
        createRule(API, OP, 5, 60);

        webTestClient.get()
                .uri(b -> b.path("/api/v1/tokens/remaining")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.remaining").isEqualTo(5);
    }

    @Test
    void remaining_afterConsume_reflectsDecrementedCount() {
        createRule(API, OP, 5, 60);
        consumeOnce(); // 4 left

        webTestClient.get()
                .uri(b -> b.path("/api/v1/tokens/remaining")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.remaining").isEqualTo(4);
    }

    @Test
    void reset_existingBucket_returns204_andNextConsumeStartsFresh() {
        createRule(API, OP, 3, 60);
        consumeOnce(); // 2 left
        consumeOnce(); // 1 left

        webTestClient.delete()
                .uri(b -> b.path("/api/v1/tokens/reset")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange()
                .expectStatus().isNoContent();

        // After reset, next consume should return max - 1 = 2
        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API).queryParam("op", OP)
                        .queryParam("key", KEY).build())
                .exchange()
                .expectBody().jsonPath("$.remaining").isEqualTo(2);
    }

    private void createRule(String api, String op, int rateLimit, int windowSecs) {
        webTestClient.post().uri("/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"test","api":"%s","op":"%s",
                         "timeInSeconds":%d,"rateLimit":%d}"""
                        .formatted(api, op, windowSecs, rateLimit))
                .exchange()
                .expectStatus().isCreated();
    }

    private void consumeOnce() {
        webTestClient.post()
                .uri(b -> b.path("/api/v1/tokens/consume")
                        .queryParam("api", API)
                        .queryParam("op", OP)
                        .queryParam("key", KEY)
                        .build())
                .exchange();
    }
}
