package dev.tobee.heimdall.config;

import dev.tobee.heimdall.entities.Rule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.util.UUID;

@Configuration
public class R2dbcConfig {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Bean
    BeforeConvertCallback<Rule> ruleBeforeConvertCallback() {
        return (rule, table) -> {
            if (rule.getId() == null) {
                rule.setId(uuidV7().toString());
            }
            return Mono.just(rule);
        };
    }

    /**
     * Generate a UUIDv7 (RFC 9562) — time-ordered with 48-bit Unix
     * millisecond timestamp, 4-bit version, 2-bit variant, and 74 random bits.
     */
    private static UUID uuidV7() {
        long timestamp = System.currentTimeMillis();
        long rand = RANDOM.nextLong();

        // upper 64 bits: 48-bit timestamp | 4-bit version (0111) | 12-bit random
        long msb = (timestamp << 16) | (0x7L << 12) | (rand & 0xFFFL);

        // lower 64 bits: 2-bit variant (10) | 62-bit random
        long lsb = (0x2L << 62) | (RANDOM.nextLong() >>> 2);

        return new UUID(msb, lsb);
    }
}
