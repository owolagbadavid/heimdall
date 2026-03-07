package dev.tobee.heimdall.repositories.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When {@code heimdall.redis.shards} is populated, creates one
 * {@link StringRedisTemplate} per shard and wires them into a
 * {@link ConsistentHashRing} that the {@link RedisClient} uses
 * for key-based routing.
 */
@Configuration
@EnableConfigurationProperties(RedisShardProperties.class)
public class RedisShardConfig {

    /**
     * Only created when at least one shard is configured.
     * Otherwise the default single-node {@code StringRedisTemplate}
     * auto-configured by Spring Boot is used (see {@link RedisClient}).
     */
    @Bean
    @ConditionalOnProperty(prefix = "heimdall.redis", name = "shards[0].host")
    public ConsistentHashRing<String> redisShardRing(RedisShardProperties props) {
        return new ConsistentHashRing<>(
                props.shards().stream().map(RedisShardProperties.Shard::toString).toList(),
                props.virtualNodes()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "heimdall.redis", name = "shards[0].host")
    public Map<String, StringRedisTemplate> redisShardTemplates(RedisShardProperties props) {
        Map<String, StringRedisTemplate> templates = new LinkedHashMap<>();
        for (RedisShardProperties.Shard shard : props.shards()) {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(shard.host(), shard.port());
            config.setDatabase(shard.database());
            if (shard.password() != null && !shard.password().isBlank()) {
                config.setPassword(shard.password());
            }
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();

            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            templates.put(shard.toString(), template);
        }
        return templates;
    }
}
