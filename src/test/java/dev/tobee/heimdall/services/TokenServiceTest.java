package dev.tobee.heimdall.services;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.TokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock TokenRepository tokenRepository;
    @Mock RuleService ruleService;
    @InjectMocks TokenService tokenService;

    private Rule rule;

    @BeforeEach
    void setUp() {
        rule = new Rule();
        rule.setId("rule-1");
        rule.setApi("payments");
        rule.setOp("createOrder");
        rule.setRateLimit(10);
        rule.setTimeInSeconds(60);
    }

    @Test
    void tryConsume_ruleFound_allowed_returnsRemainingTokens() {
        String expectedKey = "heimdall:tokens:payments:createOrder:user-1";
        when(ruleService.findByApiAndOp("payments", "createOrder")).thenReturn(Mono.just(rule));
        when(tokenRepository.consumeToken(expectedKey, 10L, 60L)).thenReturn(Mono.just(9L));

        StepVerifier.create(tokenService.tryConsume("payments", "createOrder", "user-1"))
                .expectNext(9L)
                .verifyComplete();
    }

    @Test
    void tryConsume_rateLimited_returnsNegativeOne() {
        when(ruleService.findByApiAndOp("payments", "createOrder")).thenReturn(Mono.just(rule));
        when(tokenRepository.consumeToken(any(), eq(10L), eq(60L))).thenReturn(Mono.just(-1L));

        StepVerifier.create(tokenService.tryConsume("payments", "createOrder", "user-1"))
                .expectNext(-1L)
                .verifyComplete();
    }

    @Test
    void tryConsume_noRuleFound_returnsNegativeTwo_withoutCallingRepository() {
        when(ruleService.findByApiAndOp("payments", "createOrder")).thenReturn(Mono.empty());

        StepVerifier.create(tokenService.tryConsume("payments", "createOrder", "user-1"))
                .expectNext(-2L)
                .verifyComplete();

        verify(tokenRepository, never()).consumeToken(any(), anyLong(), anyLong());
    }

    @Test
    void tryConsume_passesRuleLimitsToRepository() {
        rule.setRateLimit(50);
        rule.setTimeInSeconds(30);
        when(ruleService.findByApiAndOp("payments", "createOrder")).thenReturn(Mono.just(rule));
        when(tokenRepository.consumeToken(any(), eq(50L), eq(30L))).thenReturn(Mono.just(49L));

        StepVerifier.create(tokenService.tryConsume("payments", "createOrder", "user-1"))
                .expectNext(49L)
                .verifyComplete();

        verify(tokenRepository).consumeToken(any(), eq(50L), eq(30L));
    }

    @Test
    void tryConsume_bucketKey_includesAllThreeParts() {
        // Different api / op / caller combos must produce different bucket keys
        when(ruleService.findByApiAndOp(any(), any())).thenReturn(Mono.just(rule));
        when(tokenRepository.consumeToken(any(), anyLong(), anyLong())).thenReturn(Mono.just(9L));

        tokenService.tryConsume("api-A", "op-X", "user-1").block();
        tokenService.tryConsume("api-B", "op-X", "user-1").block();
        tokenService.tryConsume("api-A", "op-Y", "user-1").block();
        tokenService.tryConsume("api-A", "op-X", "user-2").block();

        verify(tokenRepository).consumeToken(eq("heimdall:tokens:api-A:op-X:user-1"), anyLong(), anyLong());
        verify(tokenRepository).consumeToken(eq("heimdall:tokens:api-B:op-X:user-1"), anyLong(), anyLong());
        verify(tokenRepository).consumeToken(eq("heimdall:tokens:api-A:op-Y:user-1"), anyLong(), anyLong());
        verify(tokenRepository).consumeToken(eq("heimdall:tokens:api-A:op-X:user-2"), anyLong(), anyLong());
    }

    @Test
    void getRemaining_delegatesWithCorrectKeyAndLimits() {
        String expectedKey = "heimdall:tokens:payments:createOrder:user-1";
        when(ruleService.findByApiAndOp("payments", "createOrder")).thenReturn(Mono.just(rule));
        when(tokenRepository.getTokens(expectedKey, 10L, 60L)).thenReturn(Mono.just(7L));

        StepVerifier.create(tokenService.getRemaining("payments", "createOrder", "user-1"))
                .expectNext(7L)
                .verifyComplete();
    }

    @Test
    void getRemaining_noRule_returnsEmpty() {
        when(ruleService.findByApiAndOp(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(tokenService.getRemaining("payments", "createOrder", "user-1"))
                .verifyComplete();

        verify(tokenRepository, never()).getTokens(any(), anyLong(), anyLong());
    }

    @Test
    void resetBucket_deletesCorrectKey() {
        String expectedKey = "heimdall:tokens:payments:createOrder:user-1";
        when(tokenRepository.delete(expectedKey)).thenReturn(Mono.just(true));

        StepVerifier.create(tokenService.resetBucket("payments", "createOrder", "user-1"))
                .verifyComplete();

        verify(tokenRepository).delete(expectedKey);
    }
}
