package dev.tobee.heimdall.services;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.TokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

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
     * <p>
     * In a single atomic Redis call the script will:
     * <ol>
     *   <li>Check if a bucket exists; if not, create it with full tokens.</li>
     *   <li>Refill tokens based on time elapsed since the last request.</li>
     *   <li>Deduct one token if available; otherwise reject.</li>
     *   <li>Update the bucket (tokens + last_request) for the next request.</li>
     * </ol>
     *
     * @param api the API identifier
     * @param op  the operation identifier
     * @param key the caller identifier (user ID, IP, etc.)
     * @return remaining tokens after consumption (≥ 0), or −1 if rate-limited, or −2 if no rule found
     */
    public long tryConsume(String api, String op, String key) {
        Optional<Rule> rule = ruleService.findByApiAndOp(api, op);
        if (rule.isEmpty()) {
            log.warn("No rule found for api={} op={}, not allowing request", api, op);
            return -2;
        }

        long maxTokens = Long.parseLong(rule.get().getRateLimit());
        long windowSeconds = Long.parseLong(rule.get().getTimeInSeconds());
        String bucketKey = buildKey(api, op, key);

        return tokenRepository.consumeToken(bucketKey, maxTokens, windowSeconds);
    }

    /**
     * Peek at the current available tokens (after refill) without consuming.
     */
    public Optional<Long> getRemaining(String api, String op, String key) {
        Optional<Rule> rule = ruleService.findByApiAndOp(api, op);
        if (rule.isEmpty()) {
            return Optional.empty();
        }
        long maxTokens = Long.parseLong(rule.get().getRateLimit());
        long windowSeconds = Long.parseLong(rule.get().getTimeInSeconds());
        return tokenRepository.getTokens(buildKey(api, op, key), maxTokens, windowSeconds);
    }

    /**
     * Reset the token bucket by deleting it.
     * The next call to {@link #tryConsume} will recreate it with full tokens.
     */
    public void resetBucket(String api, String op, String key) {
        tokenRepository.delete(buildKey(api, op, key));
    }

    private String buildKey(String api, String op, String key) {
        return "heimdall:tokens:" + api + ":" + op + ":" + key;
    }
}
