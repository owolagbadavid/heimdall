package dev.tobee.heimdall.repositories.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>
 * When Redis sharding is enabled (via {@code heimdall.redis.shards}), each
 * operation routes the key through a {@link ConsistentHashRing} to select
 * the correct shard. Otherwise the default single-node template is used.
 */
@Component
@Slf4j
public class RedisClient {

    private final StringRedisTemplate defaultTemplate;
    private final ConsistentHashRing<String> shardRing;
    private final Map<String, StringRedisTemplate> shardTemplates;

    public RedisClient(
            StringRedisTemplate defaultTemplate,
            @Autowired(required = false) ConsistentHashRing<String> shardRing,
            @Autowired(required = false) Map<String, StringRedisTemplate> redisShardTemplates
    ) {
        this.defaultTemplate = defaultTemplate;
        this.shardRing = shardRing;
        this.shardTemplates = redisShardTemplates;

        if (shardRing != null && shardTemplates != null) {
            log.info("Redis sharding enabled — {} shards on the hash ring", shardRing.size());
        } else {
            log.info("Redis sharding disabled — using single-node connection");
        }
    }

    /**
     * Resolve the {@link StringRedisTemplate} responsible for the given key.
     */
    private StringRedisTemplate templateFor(String key) {
        if (shardRing == null || shardTemplates == null) {
            return defaultTemplate;
        }
        String shardId = shardRing.getNode(key);
        return shardTemplates.getOrDefault(shardId, defaultTemplate);
    }

    // ── String operations ───────────────────────────────────────────────

    public void set(String key, String value) {
        templateFor(key).opsForValue().set(key, value);
    }

    public void set(String key, String value, Duration ttl) {
        templateFor(key).opsForValue().set(key, value, ttl);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(templateFor(key).opsForValue().get(key));
    }

    // ── Hash operations ─────────────────────────────────────────────────

    public void hashPut(String key, String field, String value) {
        templateFor(key).opsForHash().put(key, field, value);
    }

    public void hashPutAll(String key, Map<String, String> entries) {
        templateFor(key).opsForHash().putAll(key, entries);
    }

    public Optional<String> hashGet(String key, String field) {
        Object value = templateFor(key).opsForHash().get(key, field);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    public List<Object> hashMultiGet(String key, List<String> fields) {
        return templateFor(key).opsForHash().multiGet(key, List.copyOf(fields));
    }

    public Map<Object, Object> hashGetAll(String key) {
        return templateFor(key).opsForHash().entries(key);
    }

    public void hashDelete(String key, String... fields) {
        templateFor(key).opsForHash().delete(key, (Object[]) fields);
    }

    // ── Key operations ──────────────────────────────────────────────────

    public boolean delete(String key) {
        return Boolean.TRUE.equals(templateFor(key).delete(key));
    }

    public void expire(String key, Duration ttl) {
        templateFor(key).expire(key, ttl);
    }

    public Long getTtl(String key) {
        return templateFor(key).getExpire(key);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(templateFor(key).hasKey(key));
    }

    // ── Lua script execution ────────────────────────────────────────────

    public <T> T executeLua(String script, Class<T> resultType, String key, String... args) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>(script, resultType);
        return templateFor(key).execute(redisScript, Collections.singletonList(key), (Object[]) args);
    }
}
