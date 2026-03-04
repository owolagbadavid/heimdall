package dev.tobee.heimdall.repositories.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper around {@link StringRedisTemplate} providing common Redis
 * operations used by both token-bucket and rule-cache repositories.
 */
@Component
@RequiredArgsConstructor
public class RedisClient {

    private final StringRedisTemplate redisTemplate;

    // ── String operations ───────────────────────────────────────────────

    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    // ── Hash operations ─────────────────────────────────────────────────

    public void hashPut(String key, String field, String value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    public void hashPutAll(String key, Map<String, String> entries) {
        redisTemplate.opsForHash().putAll(key, entries);
    }

    public Optional<String> hashGet(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    public List<Object> hashMultiGet(String key, List<String> fields) {
        return redisTemplate.opsForHash().multiGet(key, List.copyOf(fields));
    }

    public Map<Object, Object> hashGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    public void hashDelete(String key, String... fields) {
        redisTemplate.opsForHash().delete(key, (Object[]) fields);
    }

    // ── Key operations ──────────────────────────────────────────────────

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    public void expire(String key, Duration ttl) {
        redisTemplate.expire(key, ttl);
    }

    public Long getTtl(String key) {
        return redisTemplate.getExpire(key);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // ── Lua script execution ────────────────────────────────────────────

    public <T> T executeLua(String script, Class<T> resultType, String key, String... args) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>(script, resultType);
        return redisTemplate.execute(redisScript, Collections.singletonList(key), (Object[]) args);
    }
}
