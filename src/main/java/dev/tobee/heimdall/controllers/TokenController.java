package dev.tobee.heimdall.controllers;

import dev.tobee.heimdall.services.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
@Tag(name = "Token (Rate Limiter)", description = "Token-bucket rate-limiter — mirrors the gRPC RateLimitService")
public class TokenController {

    private final TokenService tokenService;

    @PostMapping("/consume")
    @Operation(
            summary = "Try to consume a token",
            description = """
                    Atomically attempts to consume one token from the bucket
                    identified by (api, op, key).
                    Returns remaining tokens (≥ 0), −1 if rate-limited,
                    or −2 if no matching rule exists.
                    Mirrors gRPC RateLimitService.CheckRateLimit.""")
    @ApiResponse(responseCode = "200", description = "Consumption result returned")
    public ResponseEntity<Map<String, Object>> tryConsume(
            @Parameter(description = "API identifier", required = true, example = "payment-api")
            @RequestParam String api,
            @Parameter(description = "Operation identifier", required = true, example = "createOrder")
            @RequestParam String op,
            @Parameter(description = "Caller key (user ID, IP, etc.)", required = true, example = "user-42")
            @RequestParam String key) {

        long result = tokenService.tryConsume(api, op, key);
        boolean allowed = result >= 0;

        return ResponseEntity.ok(Map.of(
                "allowed", allowed,
                "remaining", result,
                "api", api,
                "op", op,
                "key", key
        ));
    }

    @GetMapping("/remaining")
    @Operation(
            summary = "Peek remaining tokens",
            description = "Returns the current available tokens (after refill) without consuming.")
    @ApiResponse(responseCode = "200", description = "Remaining tokens returned")
    @ApiResponse(responseCode = "404", description = "No matching rule found", content = @Content)
    public ResponseEntity<Map<String, Object>> getRemaining(
            @Parameter(description = "API identifier", required = true, example = "payment-api")
            @RequestParam String api,
            @Parameter(description = "Operation identifier", required = true, example = "createOrder")
            @RequestParam String op,
            @Parameter(description = "Caller key", required = true, example = "user-42")
            @RequestParam String key) {

        Optional<Long> remaining = tokenService.getRemaining(api, op, key);

        if (remaining.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "No rule found for api=" + api + " op=" + op));
        }

        return ResponseEntity.ok(Map.of(
                "remaining", remaining.get(),
                "api", api,
                "op", op,
                "key", key
        ));
    }

    @DeleteMapping("/reset")
    @Operation(
            summary = "Reset a token bucket",
            description = "Deletes the bucket. The next consume call recreates it with full tokens.")
    @ApiResponse(responseCode = "204", description = "Bucket reset successfully")
    public ResponseEntity<Void> resetBucket(
            @Parameter(description = "API identifier", required = true, example = "payment-api")
            @RequestParam String api,
            @Parameter(description = "Operation identifier", required = true, example = "createOrder")
            @RequestParam String op,
            @Parameter(description = "Caller key", required = true, example = "user-42")
            @RequestParam String key) {

        tokenService.resetBucket(api, op, key);
        return ResponseEntity.noContent().build();
    }
}
