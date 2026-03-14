package dev.tobee.heimdall.repositories.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around {@link ReactiveStringRedisTemplate} providing common Redis
 * operations used by both token-bucket and rule-cache repositories.
 * <p>
 * When Redis sharding is enabled (via {@code heimdall.redis.shards}), each
 * operation routes the key through a {@link ConsistentHashRing} to select
 * the correct shard. Otherwise the default single-node template is used.
 */
@Component
@Slf4j
public class RedisClient {

    private final ReactiveStringRedisTemplate defaultTemplate;
    private final ConsistentHashRing<String> shardRing;
    private final Map<String, ReactiveStringRedisTemplate> shardTemplates;

    public RedisClient(
            ReactiveStringRedisTemplate defaultTemplate,
            @Autowired(required = false) ConsistentHashRing<String> shardRing,
            @Autowired(required = false) Map<String, ReactiveStringRedisTemplate> redisShardTemplates
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
     * Resolve the {@link ReactiveStringRedisTemplate} responsible for the given key.
     */
    private ReactiveStringRedisTemplate templateFor(String key) {
        if (shardRing == null || shardTemplates == null) {
            return defaultTemplate;
        }
        String shardId = shardRing.getNode(key);
        return shardTemplates.getOrDefault(shardId, defaultTemplate);
    }

    // ── String operations ───────────────────────────────────────────────

    public Mono<Void> set(String key, String value) {
        return templateFor(key).opsForValue().set(key, value).then();
    }

    public Mono<Void> set(String key, String value, Duration ttl) {
        return templateFor(key).opsForValue().set(key, value, ttl).then();
    }

    public Mono<String> get(String key) {
        return templateFor(key).opsForValue().get(key);
    }

    // ── Hash operations ─────────────────────────────────────────────────

    public Mono<Void> hashPut(String key, String field, String value) {
        return templateFor(key).opsForHash().put(key, field, value).then();
    }

    public Mono<Void> hashPutAll(String key, Map<String, String> entries) {
        return templateFor(key).<String, String>opsForHash().putAll(key, entries).then();
    }

    public Mono<Object> hashGet(String key, String field) {
        return templateFor(key).opsForHash().get(key, field);
    }

    public Mono<List<Object>> hashMultiGet(String key, List<String> fields) {
        return templateFor(key).opsForHash().multiGet(key, List.copyOf(fields));
    }

    public Mono<Map<Object, Object>> hashGetAll(String key) {
        return templateFor(key).opsForHash().entries(key).collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public Mono<Long> hashDelete(String key, String... fields) {
        return templateFor(key).opsForHash().remove(key, (Object[]) fields);
    }

    // ── Key operations ──────────────────────────────────────────────────

    public Mono<Boolean> delete(String key) {
        return templateFor(key).delete(key).map(count -> count > 0);
    }

    public Mono<Boolean> expire(String key, Duration ttl) {
        return templateFor(key).expire(key, ttl);
    }

    public Mono<Long> getTtl(String key) {
        return templateFor(key).getExpire(key).map(Duration::getSeconds);
    }

    public Mono<Boolean> hasKey(String key) {
        return templateFor(key).hasKey(key);
    }

    // ── Lua script execution ────────────────────────────────────────────

    public <T> Mono<T> executeLua(String script, Class<T> resultType, String key, String... args) {
        RedisScript<T> redisScript = RedisScript.of(script, resultType);
        return templateFor(key)
                .execute(redisScript, Collections.singletonList(key), List.of((Object[]) args))
                .next();
    }
}
