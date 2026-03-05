package dev.tobee.heimdall.repositories.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Redis shards.
 *
 * <pre>
 * heimdall.redis.shards[0].host=redis-1
 * heimdall.redis.shards[0].port=6379
 * heimdall.redis.shards[1].host=redis-2
 * heimdall.redis.shards[1].port=6379
 * heimdall.redis.virtual-nodes=150
 * </pre>
 *
 * When no shards are configured, falls back to the default
 * {@code spring.data.redis.*} single-node connection.
 */
@ConfigurationProperties(prefix = "heimdall.redis")
public record RedisShardProperties(
        List<Shard> shards,
        int virtualNodes
) {
    public RedisShardProperties {
        if (shards == null) shards = new ArrayList<>();
        if (virtualNodes <= 0) virtualNodes = 150;
    }

    public record Shard(String host, int port, String password, int database) {
        public Shard {
            if (host == null || host.isBlank()) host = "localhost";
            if (port <= 0) port = 6379;
            if (database < 0) database = 0;
        }

        /** Stable identity used as the hash-ring node name. */
        @Override
        public String toString() {
            return host + ":" + port + "/" + database;
        }
    }
}
