package dev.tobee.heimdall.services;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.RuleCacheRepository;
import dev.tobee.heimdall.repositories.RuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

    @Mock RuleRepository ruleRepository;
    @Mock RuleCacheRepository ruleCacheRepository;
    @InjectMocks RuleService ruleService;

    private Rule rule;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ruleService, "batchSize", 100);

        rule = new Rule();
        rule.setId("id-1");
        rule.setName("payments limit");
        rule.setApi("payments");
        rule.setOp("createOrder");
        rule.setRateLimit(100);
        rule.setTimeInSeconds(60);
    }

    @Test
    void findByApiAndOp_cacheHit_neverCallsDb() {
        when(ruleCacheRepository.get("payments", "createOrder")).thenReturn(Mono.just(rule));

        StepVerifier.create(ruleService.findByApiAndOp("payments", "createOrder"))
                .expectNext(rule)
                .verifyComplete();

        verify(ruleRepository, never()).findByApiAndOp(any(), any());
    }

    @Test
    void findByApiAndOp_cacheMiss_fallsBackToDb_andPopulatesCache() {
        when(ruleCacheRepository.get("payments", "createOrder")).thenReturn(Mono.empty());
        when(ruleRepository.findByApiAndOp("payments", "createOrder")).thenReturn(Mono.just(rule));
        when(ruleCacheRepository.put(rule)).thenReturn(Mono.empty());

        StepVerifier.create(ruleService.findByApiAndOp("payments", "createOrder"))
                .expectNext(rule)
                .verifyComplete();

        verify(ruleCacheRepository).put(rule);
    }

    @Test
    void findByApiAndOp_dbMiss_returnsEmpty() {
        when(ruleCacheRepository.get(any(), any())).thenReturn(Mono.empty());
        when(ruleRepository.findByApiAndOp(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(ruleService.findByApiAndOp("payments", "createOrder"))
                .verifyComplete();
    }

    @Test
    void save_persistsToDb_andUpdatesCache() {
        when(ruleRepository.save(rule)).thenReturn(Mono.just(rule));
        when(ruleCacheRepository.put(rule)).thenReturn(Mono.empty());

        StepVerifier.create(ruleService.save(rule))
                .expectNext(rule)
                .verifyComplete();

        verify(ruleRepository).save(rule);
        verify(ruleCacheRepository).put(rule);
    }

    @Test
    void update_existingId_updatesFieldsAndCaches() {
        Rule incoming = new Rule();
        incoming.setName("new name");
        incoming.setApi("payments");
        incoming.setOp("createOrder");
        incoming.setRateLimit(200);
        incoming.setTimeInSeconds(120);

        when(ruleRepository.findById("id-1")).thenReturn(Mono.just(rule));
        when(ruleRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(ruleCacheRepository.put(any())).thenReturn(Mono.empty());

        StepVerifier.create(ruleService.update("id-1", incoming))
                .assertNext(updated -> {
                    assertThat(updated.getRateLimit()).isEqualTo(200);
                    assertThat(updated.getTimeInSeconds()).isEqualTo(120);
                    assertThat(updated.getName()).isEqualTo("new name");
                    assertThat(updated.getId()).isEqualTo("id-1");
                })
                .verifyComplete();
    }

    @Test
    void update_nonExistentId_returnsEmpty() {
        when(ruleRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(ruleService.update("missing", rule))
                .verifyComplete();

        verify(ruleRepository, never()).save(any());
    }

    @Test
    void delete_existingRule_evictsCacheAndReturnsTrue() {
        when(ruleRepository.existsById("id-1")).thenReturn(Mono.just(true));
        when(ruleRepository.findById("id-1")).thenReturn(Mono.just(rule));
        when(ruleCacheRepository.evict("payments", "createOrder")).thenReturn(Mono.just(true));
        when(ruleRepository.deleteById("id-1")).thenReturn(Mono.empty());

        StepVerifier.create(ruleService.delete("id-1"))
                .expectNext(true)
                .verifyComplete();

        verify(ruleCacheRepository).evict("payments", "createOrder");
        verify(ruleRepository).deleteById((String) "id-1");
    }

    @Test
    void delete_nonExistentRule_returnsFalseWithoutDbDelete() {
        when(ruleRepository.existsById("missing")).thenReturn(Mono.just(false));

        StepVerifier.create(ruleService.delete("missing"))
                .expectNext(false)
                .verifyComplete();

        verify(ruleRepository, never()).deleteById((String) any());
        verify(ruleCacheRepository, never()).evict(any(), any());
    }

    @Test
    void getPagedRules_firstPage_singleResult_noNextToken() {
        // fetchSize = limit + 1 = 2; only 1 returned → no more pages
        when(ruleRepository.findAllByOrderByIdAsc(2)).thenReturn(Flux.just(rule));

        StepVerifier.create(ruleService.getPagedRules(1, null))
                .assertNext(page -> {
                    assertThat(page.items()).hasSize(1);
                    assertThat(page.nextToken()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void getPagedRules_moreResultsAvailable_returnsNextToken() {
        Rule rule2 = new Rule();
        rule2.setId("id-2");

        // fetchSize = 2; 2 returned → has more
        when(ruleRepository.findAllByOrderByIdAsc(2)).thenReturn(Flux.just(rule, rule2));

        StepVerifier.create(ruleService.getPagedRules(1, null))
                .assertNext(page -> {
                    assertThat(page.items()).hasSize(1);
                    assertThat(page.nextToken()).isEqualTo("id-1");
                })
                .verifyComplete();
    }

    @Test
    void getPagedRules_withCursor_usesKeysetQuery() {
        when(ruleRepository.findByIdGreaterThanOrderByIdAsc("id-1", 2))
                .thenReturn(Flux.empty());

        StepVerifier.create(ruleService.getPagedRules(1, "id-1"))
                .assertNext(page -> assertThat(page.items()).isEmpty())
                .verifyComplete();

        verify(ruleRepository).findByIdGreaterThanOrderByIdAsc("id-1", 2);
        verify(ruleRepository, never()).findAllByOrderByIdAsc(anyInt());
    }

    @Test
    void getPagedRules_limitCappedAt100() {
        when(ruleRepository.findAllByOrderByIdAsc(101)).thenReturn(Flux.empty());

        StepVerifier.create(ruleService.getPagedRules(9999, null))
                .expectNextCount(1)
                .verifyComplete();

        // fetchSize must be 101 (cap 100 + 1 lookahead)
        verify(ruleRepository).findAllByOrderByIdAsc(101);
    }

    @Test
    void getPagedRules_limitMinimumIsOne() {
        when(ruleRepository.findAllByOrderByIdAsc(2)).thenReturn(Flux.empty());

        StepVerifier.create(ruleService.getPagedRules(0, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(ruleRepository).findAllByOrderByIdAsc(2);
    }

    @Test
    void refreshCache_putsAllRulesIntoCache() {
        Rule rule2 = new Rule();
        rule2.setId("id-2");
        rule2.setApi("payments");
        rule2.setOp("refund");

        when(ruleRepository.findAll()).thenReturn(Flux.just(rule, rule2));
        when(ruleCacheRepository.put(any())).thenReturn(Mono.empty());

        StepVerifier.create(ruleService.refreshCache())
                .verifyComplete();

        verify(ruleCacheRepository).put(rule);
        verify(ruleCacheRepository).put(rule2);
    }
}
