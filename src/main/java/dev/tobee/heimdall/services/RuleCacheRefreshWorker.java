package dev.tobee.heimdall.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background worker that periodically refreshes the in-memory rule cache
 * so that database changes are picked up without waiting for a cache miss.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleCacheRefreshWorker {

    private final RuleService ruleService;

    /**
     * Refresh the rule cache every 60 seconds.
     */
    @Scheduled(fixedRateString = "${heimdall.cache.refresh-interval-ms:60000}")
    public void refresh() {
        try {
            ruleService.refreshCache();
        } catch (Exception e) {
            log.error("Failed to refresh rule cache", e);
        }
    }
}
