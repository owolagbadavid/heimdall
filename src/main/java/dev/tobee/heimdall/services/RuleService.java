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
