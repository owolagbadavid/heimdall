package dev.tobee.heimdall.repositories.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A consistent-hash ring that maps arbitrary string keys to nodes.
 * <p>
 * Each physical node is placed at {@code virtualNodes} positions on the ring
 * (using MD5-based hashing) to ensure even distribution. When a key is looked
 * up, the ring returns the first node whose position is ≥ the key's hash —
 * wrapping around to the first node when the key hashes past the highest
 * virtual node.
 *
 * @param <T> the node type (e.g. a shard identifier or a RedisTemplate)
 */
public class ConsistentHashRing<T> {

    private final SortedMap<Long, T> ring = new TreeMap<>();
    private final int virtualNodes;

    /**
     * @param nodes        the physical nodes to place on the ring
     * @param virtualNodes number of virtual positions per physical node (higher = more even distribution)
     */
    public ConsistentHashRing(Collection<T> nodes, int virtualNodes) {
        this.virtualNodes = virtualNodes;
        for (T node : nodes) {
            addNode(node);
        }
    }

    public void addNode(T node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(node.toString() + "#" + i);
            ring.put(hash, node);
        }
    }

    public void removeNode(T node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(node.toString() + "#" + i);
            ring.remove(hash);
        }
    }

    /**
     * Returns the node responsible for the given key.
     *
     * @throws IllegalStateException if the ring is empty
     */
    public T getNode(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("ConsistentHashRing is empty");
        }
        long hash = hash(key);
        // Find the first node at or after the hash position
        SortedMap<Long, T> tail = ring.tailMap(hash);
        long nodeHash = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(nodeHash);
    }

    public int size() {
        return ring.size() / virtualNodes;
    }

    /**
     * MD5-based hash → unsigned 32-bit value for good distribution.
     */
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return ((long) (digest[0] & 0xFF))
                    | ((long) (digest[1] & 0xFF) << 8)
                    | ((long) (digest[2] & 0xFF) << 16)
                    | ((long) (digest[3] & 0xFF) << 24);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
