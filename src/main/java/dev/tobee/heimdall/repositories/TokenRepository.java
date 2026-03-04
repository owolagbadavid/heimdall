package dev.tobee.heimdall.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed repository for token bucket rate limiting.
 * <p>
 * Each bucket is stored as a Redis Hash with two fields:
 * <ul>
 *   <li>{@code tokens} – current number of tokens (fractional, stored as double)</li>
 *   <li>{@code last_request} – epoch timestamp in seconds (with microsecond precision) of the last request</li>
 * </ul>
 * All operations are atomic via Lua scripts executed on the Redis server.
 */
@Repository
@RequiredArgsConstructor
public class TokenRepository {

    private final StringRedisTemplate redisTemplate;

    /**
     * Atomic token-bucket consume script.
     * <p>
     * On every call it:
     * <ol>
     *   <li>Reads the bucket hash (tokens + last_request).</li>
     *   <li>If the bucket doesn't exist → creates it with {@code max_tokens − 1} and returns the remaining count.</li>
     *   <li>If it exists → refills tokens based on elapsed time since last_request (capped at max_tokens),
     *       then tries to consume one token.</li>
     * </ol>
     * Returns remaining tokens (≥ 0) on success, or −1 when rate-limited.
     */
    private static final String CONSUME_SCRIPT = """
            local key         = KEYS[1]
            local max_tokens  = tonumber(ARGV[1])
            local window_secs = tonumber(ARGV[2])

            -- server-side time for consistency (seconds + microseconds → double)
            local t   = redis.call('TIME')
            local now = tonumber(t[1]) + (tonumber(t[2]) / 1000000)

            local bucket       = redis.call('HMGET', key, 'tokens', 'last_request')
            local tokens       = tonumber(bucket[1])
            local last_request = tonumber(bucket[2])

            if tokens == nil then
                -- first request: init bucket, consume one token
                tokens = max_tokens - 1
                redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_request', tostring(now))
                redis.call('EXPIRE', key, window_secs * 2)
                return math.floor(tokens)
            end

            -- refill based on elapsed time
            local elapsed     = now - last_request
            local refill_rate = max_tokens / window_secs
            tokens = math.min(max_tokens, tokens + elapsed * refill_rate)

            if tokens < 1 then
                -- not enough tokens – update timestamp so partial refill isn't lost
                redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_request', tostring(now))
                return -1
            end

            tokens = tokens - 1
            redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_request', tostring(now))
            redis.call('EXPIRE', key, window_secs * 2)
            return math.floor(tokens)
            """;

    /**
     * Atomic peek script – returns current tokens after refill, without consuming.
     */
    private static final String PEEK_SCRIPT = """
            local key         = KEYS[1]
            local max_tokens  = tonumber(ARGV[1])
            local window_secs = tonumber(ARGV[2])

            local t   = redis.call('TIME')
            local now = tonumber(t[1]) + (tonumber(t[2]) / 1000000)

            local bucket       = redis.call('HMGET', key, 'tokens', 'last_request')
            local tokens       = tonumber(bucket[1])
            local last_request = tonumber(bucket[2])

            if tokens == nil then
                return max_tokens
            end

            local elapsed     = now - last_request
            local refill_rate = max_tokens / window_secs
            tokens = math.min(max_tokens, tokens + elapsed * refill_rate)
            return math.floor(tokens)
            """;

    /**
     * Atomically try to consume one token from the bucket.
     * If the bucket does not exist it is created with full tokens and one is consumed immediately.
     * Refills tokens proportionally to the time elapsed since the last request.
     *
     * @param key        the bucket key
     * @param maxTokens  maximum tokens (from rule)
     * @param windowSecs time window in seconds over which tokens fully refill
     * @return remaining tokens after consumption (≥ 0), or −1 if rate-limited
     */
    public long consumeToken(String key, long maxTokens, long windowSecs) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(CONSUME_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(key),
                String.valueOf(maxTokens), String.valueOf(windowSecs));
        return result != null ? result : -1;
    }

    /**
     * Peek at the current available tokens (after refill) without consuming.
     */
    public Optional<Long> getTokens(String key, long maxTokens, long windowSecs) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(PEEK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(key),
                String.valueOf(maxTokens), String.valueOf(windowSecs));
        return Optional.ofNullable(result);
    }

    /**
     * Read the raw bucket fields (tokens, last_request) without refill.
     */
    public Optional<List<String>> getRawBucket(String key) {
        List<Object> values = redisTemplate.opsForHash().multiGet(key, List.of("tokens", "last_request"));
        if (values.getFirst() == null) {
            return Optional.empty();
        }
        return Optional.of(values.stream().map(Object::toString).toList());
    }

    /**
     * Get remaining TTL for a bucket key (seconds).
     */
    public Long getTtl(String key) {
        return redisTemplate.getExpire(key);
    }

    /**
     * Delete a token bucket.
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }
}
