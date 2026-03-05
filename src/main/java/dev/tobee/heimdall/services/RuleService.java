package dev.tobee.heimdall.services;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.RuleCacheRepository;
import dev.tobee.heimdall.repositories.RuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RuleCacheRepository ruleCacheRepository;

    @Value("${heimdall.cache.refresh-batch-size:100}")
    private int batchSize;

    @Transactional(readOnly = true)
    public List<Rule> findAll() {
        return ruleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Rule> findById(String id) {
        return ruleRepository.findById(id);
    }

    /**
     * Looks up a rule by api + op. Checks the Redis cache first;
     * falls back to the database on a cache miss and populates the cache.
     */
    @Transactional(readOnly = true)
    public Optional<Rule> findByApiAndOp(String api, String op) {
        Optional<Rule> cached = ruleCacheRepository.get(api, op);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<Rule> rule = ruleRepository.findByApiAndOp(api, op);
        rule.ifPresent(ruleCacheRepository::put);
        return rule;
    }

    @Transactional(readOnly = true)
    public List<Rule> findByApi(String api) {
        return ruleRepository.findByApi(api);
    }

    @Transactional
    public Rule save(Rule rule) {
        Rule saved = ruleRepository.save(rule);
        ruleCacheRepository.put(saved);
        return saved;
    }

    @Transactional
    public void deleteById(String id) {
        ruleRepository.findById(id).ifPresent(rule ->
                ruleCacheRepository.evict(rule.getApi(), rule.getOp()));
        ruleRepository.deleteById(id);
    }

    @Transactional
    public Rule create(Rule rule) {
        rule.setId(null);
        return save(rule);
    }

    @Transactional
    public Optional<Rule> update(String id, Rule rule) {
        return ruleRepository.findById(id)
                .map(existing -> {
                    existing.setName(rule.getName());
                    existing.setApi(rule.getApi());
                    existing.setOp(rule.getOp());
                    existing.setTimeInSeconds(rule.getTimeInSeconds());
                    existing.setRateLimit(rule.getRateLimit());
                    return save(existing);
                });
    }

    @Transactional
    public boolean delete(String id) {
        if (ruleRepository.existsById(id)) {
            deleteById(id);
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public RulePage getPagedRules(int limit, String nextToken) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int fetchSize = safeLimit + 1;

        List<Rule> fetched = (nextToken == null || nextToken.isBlank())
                ? ruleRepository.findAllByOrderByIdAsc(PageRequest.of(0, fetchSize))
                : ruleRepository.findByIdGreaterThanOrderByIdAsc(nextToken, PageRequest.of(0, fetchSize));

        boolean hasMore = fetched.size() > safeLimit;
        List<Rule> items = hasMore
                ? new ArrayList<>(fetched.subList(0, safeLimit))
                : fetched;

        String newNextToken = hasMore ? items.getLast().getId() : null;
        return new RulePage(items, newNextToken);
    }

    public record RulePage(List<Rule> items, String nextToken) {}

    /**
     * Reload rules from the database into the Redis cache, batch by batch,
     * so we never load all rules into memory at once.
     * Called by {@link RuleCacheRefreshWorker} on a schedule.
     */
    @Transactional(readOnly = true)
    public void refreshCache() {
        int page = 0;
        int total = 0;
        Page<Rule> batch;

        do {
            batch = ruleRepository.findAll(PageRequest.of(page, batchSize, Sort.by("id")));
            for (Rule r : batch.getContent()) {
                ruleCacheRepository.put(r);
            }
            total += batch.getNumberOfElements();
            page++;
        } while (batch.hasNext());

        log.info("Rule cache refreshed — {} rules loaded in {} batches", total, page);
    }
}
