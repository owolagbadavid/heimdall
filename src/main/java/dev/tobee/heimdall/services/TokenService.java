package dev.tobee.heimdall.services;

import dev.tobee.heimdall.repositories.TokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service for managing rate-limit tokens backed by Redis.
 * Works alongside {@link RuleService} — rules define the limits,
 * this service enforces them via an atomic token-bucket algorithm.
 * <p>
 * The bucket tracks {@code tokens} and {@code last_request} in a Redis hash.
 * On each request, elapsed time since the last request is used to refill tokens
 * proportionally, then one token is consumed — all in a single Lua script.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private final TokenRepository tokenRepository;
    private final RuleService ruleService;

    /**
     * Try to consume a token for the given API + operation + caller key.
     *
     * @param api the API identifier
     * @param op  the operation identifier
     * @param key the caller identifier (user ID, IP, etc.)
     * @return remaining tokens after consumption (≥ 0), or −1 if rate-limited, or −2 if no rule found
     */
    public Mono<Long> tryConsume(String api, String op, String key) {
        return ruleService.findByApiAndOp(api, op)
                .flatMap(rule -> {
                    long maxTokens = rule.getRateLimit();
                    long windowSeconds = rule.getTimeInSeconds();
                    String bucketKey = buildKey(api, op, key);
                    return tokenRepository.consumeToken(bucketKey, maxTokens, windowSeconds);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No rule found for api={} op={}, not allowing request", api, op);
                    return Mono.just(-2L);
                }));
    }

    /**
     * Peek at the current available tokens (after refill) without consuming.
     */
    public Mono<Long> getRemaining(String api, String op, String key) {
        return ruleService.findByApiAndOp(api, op)
                .flatMap(rule -> {
                    long maxTokens = rule.getRateLimit();
                    long windowSeconds = rule.getTimeInSeconds();
                    return tokenRepository.getTokens(buildKey(api, op, key), maxTokens, windowSeconds);
                });
    }

    /**
     * Reset the token bucket by deleting it.
     * The next call to {@link #tryConsume} will recreate it with full tokens.
     */
    public Mono<Void> resetBucket(String api, String op, String key) {
        return tokenRepository.delete(buildKey(api, op, key)).then();
    }

    private String buildKey(String api, String op, String key) {
        return "heimdall:tokens:" + api + ":" + op + ":" + key;
    }
}
