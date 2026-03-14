package dev.tobee.heimdall.repositories;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.redis.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Redis-backed cache for {@link Rule} entities, keyed by {@code api:op}.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class RuleCacheRepository {

    private static final String PREFIX = "heimdall:rules:";

    private final RedisClient redisClient;
    private final ObjectMapper objectMapper;

    /**
     * Get a cached rule by api + op.
     */
    public Mono<Rule> get(String api, String op) {
        return redisClient.get(key(api, op))
                .flatMap(this::deserialize);
    }

    /**
     * Put a rule into the cache.
     */
    public Mono<Void> put(Rule rule) {
        return Mono.justOrEmpty(serialize(rule))
                .flatMap(json -> redisClient.set(key(rule.getApi(), rule.getOp()), json));
    }

    /**
     * Evict a rule from the cache.
     */
    public Mono<Boolean> evict(String api, String op) {
        return redisClient.delete(key(api, op));
    }

    private String key(String api, String op) {
        return PREFIX + api + ":" + op;
    }

    private String serialize(Rule rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (JacksonException e) {
            log.error("Failed to serialize rule {}", rule.getId(), e);
            return null;
        }
    }

    private Mono<Rule> deserialize(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, Rule.class));
        } catch (JacksonException e) {
            log.error("Failed to deserialize rule from cache", e);
            return Mono.empty();
        }
    }
}
