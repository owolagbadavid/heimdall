package dev.tobee.heimdall.repositories.redis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class ConsistentHashRingTest {

    @Test
    void emptyRing_getNode_throwsIllegalState() {
        var ring = new ConsistentHashRing<String>(List.of(), 10);
        assertThatThrownBy(() -> ring.getNode("any"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void size_reflectsPhysicalNodeCount() {
        var ring = new ConsistentHashRing<>(List.of("a", "b", "c"), 50);
        assertThat(ring.size()).isEqualTo(3);
    }

    @Test
    void singleNode_allKeysRouteToThatNode() {
        var ring = new ConsistentHashRing<>(List.of("only"), 100);
        assertThat(ring.getNode("foo")).isEqualTo("only");
        assertThat(ring.getNode("bar")).isEqualTo("only");
        assertThat(ring.getNode("heimdall:tokens:api:op:user-42")).isEqualTo("only");
    }

    @Test
    void sameKey_alwaysReturnsTheSameNode() {
        var ring = new ConsistentHashRing<>(List.of("n1", "n2", "n3"), 150);
        String first = ring.getNode("stable-key");
        for (int i = 0; i < 200; i++) {
            assertThat(ring.getNode("stable-key")).isEqualTo(first);
        }
    }

    @Test
    void differentKeys_canRouteTodifferentNodes() {
        var ring = new ConsistentHashRing<>(List.of("n1", "n2", "n3"), 150);
        // With 3 nodes and 1000 keys, at least two distinct nodes must appear
        long distinctNodes = IntStream.range(0, 1000)
                .mapToObj(i -> ring.getNode("key-" + i))
                .distinct()
                .count();
        assertThat(distinctNodes).isGreaterThan(1);
    }

    @Test
    void distribution_isReasonablyEvenAcrossNodes() {
        var nodes = List.of("node1", "node2", "node3");
        var ring = new ConsistentHashRing<>(nodes, 150);

        int[] counts = new int[3];
        for (int i = 0; i < 3000; i++) {
            counts[nodes.indexOf(ring.getNode("key-" + i))]++;
        }
        // Each node should receive roughly 1000 keys; allow ±50%
        for (int count : counts) {
            assertThat(count)
                    .as("node count should be between 500 and 1500")
                    .isBetween(500, 1500);
        }
    }

    @Test
    void addNode_incrementsSize() {
        var ring = new ConsistentHashRing<>(List.of("n1"), 10);
        ring.addNode("n2");
        assertThat(ring.size()).isEqualTo(2);
    }

    @Test
    void removeNode_decrementsSize() {
        var ring = new ConsistentHashRing<>(List.of("n1", "n2"), 10);
        ring.removeNode("n2");
        assertThat(ring.size()).isEqualTo(1);
    }

    @Test
    void removeLastNode_sizeIsZero_getNodeThrows() {
        var ring = new ConsistentHashRing<>(List.of("only"), 10);
        ring.removeNode("only");
        assertThat(ring.size()).isEqualTo(0);
        assertThatThrownBy(() -> ring.getNode("k")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addNode_minimisesRemapping() {
        // Consistent hashing guarantee: adding one node should only move ~1/N keys
        var ring = new ConsistentHashRing<>(List.of("n1", "n2", "n3"), 150);
        var keys = IntStream.range(0, 3000).mapToObj(i -> "key-" + i).toList();

        var before = keys.stream().map(ring::getNode).toList();
        ring.addNode("n4");
        var after = keys.stream().map(ring::getNode).toList();

        long unchanged = IntStream.range(0, keys.size())
                .filter(i -> before.get(i).equals(after.get(i)))
                .count();

        // At least 70% of keys should stay on the same node
        assertThat(unchanged).isGreaterThan(2100);
    }

    @Test
    void hashQuality_realisticTokenKeys_distributeAcrossAllNodes() {
        // Use a large sample of realistic rate-limit bucket keys; every physical
        // node must receive at least one key.  FNV-1a produces well-distributed
        // 64-bit values, so with 3000 keys and 3 nodes this is deterministic.
        var nodes = List.of("n1", "n2", "n3");
        var ring = new ConsistentHashRing<>(nodes, 150);
        long distinct = IntStream.range(0, 3000)
                .mapToObj(i -> ring.getNode("heimdall:tokens:payments:createOrder:user-" + i))
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(3);
    }
}
