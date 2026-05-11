package dev.tobee.heimdall.repositories;

import dev.tobee.heimdall.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the Redis Lua scripts in TokenRepository.
 * Each test uses a UUID-prefixed key so tests never share bucket state.
 */
class TokenRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TokenRepository tokenRepository;

    private String key() {
        return "test:bucket:" + UUID.randomUUID();
    }

    @Test
    void consumeToken_firstCall_createsBucketAndReturnsMaxMinusOne() {
        Long remaining = tokenRepository.consumeToken(key(), 10L, 60L).block();
        assertThat(remaining).isEqualTo(9L);
    }

    @Test
    void consumeToken_subsequentCalls_decrementsTokens() {
        String k = key();
        Long first  = tokenRepository.consumeToken(k, 5L, 60L).block(); // 4
        Long second = tokenRepository.consumeToken(k, 5L, 60L).block(); // 3
        Long third  = tokenRepository.consumeToken(k, 5L, 60L).block(); // 2

        assertThat(first).isEqualTo(4L);
        assertThat(second).isEqualTo(3L);
        assertThat(third).isEqualTo(2L);
    }

    @Test
    void consumeToken_exhaustedBucket_returnsNegativeOne() {
        String k = key();
        // maxTokens = 2: first → 1, second → 0, third → -1
        tokenRepository.consumeToken(k, 2L, 60L).block();
        tokenRepository.consumeToken(k, 2L, 60L).block();
        Long result = tokenRepository.consumeToken(k, 2L, 60L).block();

        assertThat(result).isEqualTo(-1L);
    }

    @Test
    void consumeToken_singleTokenBucket_firstAllowed_secondDenied() {
        String k = key();
        Long allowed = tokenRepository.consumeToken(k, 1L, 60L).block(); // 0
        Long denied  = tokenRepository.consumeToken(k, 1L, 60L).block(); // -1

        assertThat(allowed).isEqualTo(0L);
        assertThat(denied).isEqualTo(-1L);
    }

    @Test
    void consumeToken_zeroWindowSecs_returnsNegativeOne() {
        // window_secs = 0 would cause division-by-zero; script must guard it
        Long result = tokenRepository.consumeToken(key(), 10L, 0L).block();
        assertThat(result).isEqualTo(-1L);
    }

    @Test
    void consumeToken_rateLimited_ttlStillPositive() {
        // Exhaust a 2-token bucket, then verify the key still has a TTL
        // (EXPIRE must be called even on the denied path)
        String k = key();
        tokenRepository.consumeToken(k, 2L, 10L).block();
        tokenRepository.consumeToken(k, 2L, 10L).block();
        tokenRepository.consumeToken(k, 2L, 10L).block(); // rate-limited

        Long ttl = tokenRepository.getTtl(k).block();
        assertThat(ttl).isPositive();
    }

    @Test
    void getTokens_nonExistentBucket_returnsMaxTokens() {
        Long remaining = tokenRepository.getTokens(key(), 10L, 60L).block();
        assertThat(remaining).isEqualTo(10L);
    }

    @Test
    void getTokens_doesNotConsumeToken() {
        String k = key();
        tokenRepository.consumeToken(k, 10L, 60L).block(); // remaining = 9

        Long peeked  = tokenRepository.getTokens(k, 10L, 60L).block();
        Long peeked2 = tokenRepository.getTokens(k, 10L, 60L).block();

        assertThat(peeked).isEqualTo(9L);
        assertThat(peeked2).isEqualTo(9L); // unchanged — peek must not consume
    }

    @Test
    void getTokens_zeroWindowSecs_returnsMaxTokens() {
        Long result = tokenRepository.getTokens(key(), 5L, 0L).block();
        assertThat(result).isEqualTo(5L);
    }

    @Test
    void consumeToken_bucketHasTtlAfterCreation() {
        String k = key();
        tokenRepository.consumeToken(k, 10L, 30L).block();

        Long ttl = tokenRepository.getTtl(k).block();
        // TTL is set to window * 2 = 60s; allow some margin
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void delete_existingBucket_returnsTrue() {
        String k = key();
        tokenRepository.consumeToken(k, 5L, 60L).block();

        Boolean deleted = tokenRepository.delete(k).block();
        assertThat(deleted).isTrue();
    }

    @Test
    void delete_nonExistentKey_returnsFalse() {
        Boolean deleted = tokenRepository.delete("heimdall:tokens:no:such:key").block();
        assertThat(deleted).isFalse();
    }

    @Test
    void delete_thenConsume_recreatesBucketWithFullTokens() {
        String k = key();
        tokenRepository.consumeToken(k, 3L, 60L).block(); // 2 left
        tokenRepository.consumeToken(k, 3L, 60L).block(); // 1 left
        tokenRepository.delete(k).block();

        // After reset, bucket is recreated from scratch → max - 1
        Long after = tokenRepository.consumeToken(k, 3L, 60L).block();
        assertThat(after).isEqualTo(2L);
    }
}
