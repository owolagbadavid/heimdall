package dev.tobee.heimdall.services;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.RuleCacheRepository;
import dev.tobee.heimdall.repositories.RuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RuleCacheRepository ruleCacheRepository;

    @Value("${heimdall.cache.refresh-batch-size:100}")
    private int batchSize;

    @Transactional(readOnly = true)
    public Flux<Rule> findAll() {
        return ruleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Mono<Rule> findById(String id) {
        return ruleRepository.findById(id);
    }

    /**
     * Looks up a rule by api + op. Checks the Redis cache first;
     * falls back to the database on a cache miss and populates the cache.
     */
    @Transactional(readOnly = true)
    public Mono<Rule> findByApiAndOp(String api, String op) {
        return ruleCacheRepository.get(api, op)
                .switchIfEmpty(
                        ruleRepository.findByApiAndOp(api, op)
                                .flatMap(rule -> ruleCacheRepository.put(rule).thenReturn(rule))
                );
    }

    @Transactional(readOnly = true)
    public Flux<Rule> findByApi(String api) {
        return ruleRepository.findByApi(api);
    }

    @Transactional
    public Mono<Rule> save(Rule rule) {
        return ruleRepository.save(rule)
                .flatMap(saved -> ruleCacheRepository.put(saved).thenReturn(saved));
    }

    @Transactional
    public Mono<Void> deleteById(String id) {
        return ruleRepository.findById(id)
                .flatMap(rule -> ruleCacheRepository.evict(rule.getApi(), rule.getOp()))
                .then(ruleRepository.deleteById(id));
    }

    @Transactional
    public Mono<Rule> create(Rule rule) {
        rule.setId(null);
        return save(rule);
    }

    @Transactional
    public Mono<Rule> update(String id, Rule rule) {
        return ruleRepository.findById(id)
                .flatMap(existing -> {
                    existing.setName(rule.getName());
                    existing.setApi(rule.getApi());
                    existing.setOp(rule.getOp());
                    existing.setTimeInSeconds(rule.getTimeInSeconds());
                    existing.setRateLimit(rule.getRateLimit());
                    return save(existing);
                });
    }

    @Transactional
    public Mono<Boolean> delete(String id) {
        return ruleRepository.existsById(id)
                .flatMap(exists -> {
                    if (!exists) return Mono.just(false);
                    return deleteById(id).thenReturn(true);
                });
    }

    @Transactional(readOnly = true)
    public Mono<RulePage> getPagedRules(int limit, String nextToken) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int fetchSize = safeLimit + 1;

        Flux<Rule> fetched = (nextToken == null || nextToken.isBlank())
                ? ruleRepository.findAllByOrderByIdAsc(fetchSize)
                : ruleRepository.findByIdGreaterThanOrderByIdAsc(nextToken, fetchSize);

        return fetched.collectList().map(list -> {
            boolean hasMore = list.size() > safeLimit;
            List<Rule> items = hasMore ? list.subList(0, safeLimit) : list;
            String newNextToken = hasMore ? items.getLast().getId() : null;
            return new RulePage(items, newNextToken);
        });
    }

    public record RulePage(List<Rule> items, String nextToken) {}

    /**
     * Reload rules from the database into the Redis cache, batch by batch.
     * Called by {@link RuleCacheRefreshWorker} on a schedule.
     */
    @Transactional(readOnly = true)
    public Mono<Void> refreshCache() {
        return ruleRepository.findAll()
                .buffer(batchSize)
                .flatMap(batch -> Flux.fromIterable(batch)
                        .flatMap(ruleCacheRepository::put))
                .then()
                .doOnSuccess(v -> log.info("Rule cache refreshed"));
    }
}
