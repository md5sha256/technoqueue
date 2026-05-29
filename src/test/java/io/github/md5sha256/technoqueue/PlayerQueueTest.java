package io.github.md5sha256.technoqueue;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerQueueTest {

    private static UUID uuid() {
        return UUID.randomUUID();
    }

    @Test
    void newQueueIsEmpty() {
        PlayerQueue queue = new PlayerQueue(10);
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertTrue(queue.dequePlayer().isEmpty());
    }

    @Test
    void enqueueIncrementsSize() {
        PlayerQueue queue = new PlayerQueue(10);
        assertTrue(queue.enqueuePlayer(uuid(), 0));
        assertTrue(queue.enqueuePlayer(uuid(), 0));
        assertEquals(2, queue.size());
        assertFalse(queue.isEmpty());
    }

    @Test
    void dequeueReturnsFifoOrderForEqualWeights() {
        PlayerQueue queue = new PlayerQueue(10);
        UUID first = uuid();
        UUID second = uuid();
        UUID third = uuid();
        queue.enqueuePlayer(first, 0);
        queue.enqueuePlayer(second, 0);
        queue.enqueuePlayer(third, 0);

        assertEquals(first, queue.dequePlayer().orElseThrow().player());
        assertEquals(second, queue.dequePlayer().orElseThrow().player());
        assertEquals(third, queue.dequePlayer().orElseThrow().player());
        assertTrue(queue.isEmpty());
    }

    @Test
    void higherWeightsAreDequeuedFirst() {
        PlayerQueue queue = new PlayerQueue(10);
        UUID low = uuid();
        UUID high = uuid();
        UUID mid = uuid();
        queue.enqueuePlayer(low, 0);
        queue.enqueuePlayer(high, 10);
        queue.enqueuePlayer(mid, 5);

        assertEquals(high, queue.dequePlayer().orElseThrow().player());
        assertEquals(mid, queue.dequePlayer().orElseThrow().player());
        assertEquals(low, queue.dequePlayer().orElseThrow().player());
    }

    @Test
    void equalWeightInterleavedInsertionsPreserveFifoWithinBucket() {
        PlayerQueue queue = new PlayerQueue(10);
        UUID lowA = uuid();
        UUID highA = uuid();
        UUID lowB = uuid();
        UUID highB = uuid();
        queue.enqueuePlayer(lowA, 0);
        queue.enqueuePlayer(highA, 5);
        queue.enqueuePlayer(lowB, 0);
        queue.enqueuePlayer(highB, 5);

        assertEquals(highA, queue.dequePlayer().orElseThrow().player());
        assertEquals(highB, queue.dequePlayer().orElseThrow().player());
        assertEquals(lowA, queue.dequePlayer().orElseThrow().player());
        assertEquals(lowB, queue.dequePlayer().orElseThrow().player());
    }

    @Test
    void enqueueAtMaxSizeIsRejected() {
        PlayerQueue queue = new PlayerQueue(2);
        assertTrue(queue.enqueuePlayer(uuid(), 0));
        assertTrue(queue.enqueuePlayer(uuid(), 0));
        assertFalse(queue.enqueuePlayer(uuid(), 0));
        assertEquals(2, queue.size());
    }

    @Test
    void reEnqueueUpdatesWeightAndKeepsSizeStable() {
        PlayerQueue queue = new PlayerQueue(10);
        UUID player = uuid();
        UUID other = uuid();
        queue.enqueuePlayer(other, 5);
        queue.enqueuePlayer(player, 0);
        assertEquals(2, queue.size());

        assertTrue(queue.enqueuePlayer(player, 10));
        assertEquals(2, queue.size());

        // Re-enqueued player now has the highest weight.
        assertEquals(player, queue.dequePlayer().orElseThrow().player());
        assertEquals(other, queue.dequePlayer().orElseThrow().player());
    }

    @Test
    void reEnqueueAtMaxSizeReplacesExistingEntry() {
        PlayerQueue queue = new PlayerQueue(1);
        UUID player = uuid();
        assertTrue(queue.enqueuePlayer(player, 0));
        assertFalse(queue.enqueuePlayer(uuid(), 0));

        // Same player re-enqueueing should succeed (their old entry is removed first).
        assertTrue(queue.enqueuePlayer(player, 5));
        assertEquals(1, queue.size());
        QueueEntry head = queue.dequePlayer().orElseThrow();
        assertEquals(player, head.player());
        assertEquals(5, head.weight());
    }

    @Test
    void dequeuePlayerRemovesSpecificEntry() {
        PlayerQueue queue = new PlayerQueue(10);
        UUID a = uuid();
        UUID b = uuid();
        UUID c = uuid();
        queue.enqueuePlayer(a, 0);
        queue.enqueuePlayer(b, 0);
        queue.enqueuePlayer(c, 0);

        queue.dequeuePlayer(b);
        assertEquals(2, queue.size());

        assertEquals(a, queue.dequePlayer().orElseThrow().player());
        assertEquals(c, queue.dequePlayer().orElseThrow().player());
    }

    @Test
    void dequeuePlayerForUnknownIsNoOp() {
        PlayerQueue queue = new PlayerQueue(10);
        queue.enqueuePlayer(uuid(), 0);
        queue.dequeuePlayer(uuid());
        assertEquals(1, queue.size());
    }

    @Test
    void dequeuePlayerEmptiesBucketAndCleansUp() {
        PlayerQueue queue = new PlayerQueue(10);
        UUID solo = uuid();
        UUID other = uuid();
        queue.enqueuePlayer(solo, 10);
        queue.enqueuePlayer(other, 0);

        queue.dequeuePlayer(solo);
        assertEquals(1, queue.size());
        assertEquals(other, queue.dequePlayer().orElseThrow().player());
    }

    @Test
    void clearEmptiesQueue() {
        PlayerQueue queue = new PlayerQueue(10);
        queue.enqueuePlayer(uuid(), 0);
        queue.enqueuePlayer(uuid(), 5);
        queue.clear();

        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertTrue(queue.dequePlayer().isEmpty());
    }

    @Test
    void clearedQueueAcceptsNewEntries() {
        PlayerQueue queue = new PlayerQueue(2);
        queue.enqueuePlayer(uuid(), 0);
        queue.enqueuePlayer(uuid(), 0);
        queue.clear();

        assertTrue(queue.enqueuePlayer(uuid(), 0));
        assertTrue(queue.enqueuePlayer(uuid(), 0));
        assertFalse(queue.enqueuePlayer(uuid(), 0));
    }

    @Test
    void queuePositionsReflectsDequeueOrder() {
        PlayerQueue queue = new PlayerQueue(10);
        UUID low = uuid();
        UUID highA = uuid();
        UUID mid = uuid();
        UUID highB = uuid();
        queue.enqueuePlayer(low, 0);
        queue.enqueuePlayer(highA, 10);
        queue.enqueuePlayer(mid, 5);
        queue.enqueuePlayer(highB, 10);

        QueueEntry[] positions = queue.queuePositions();
        UUID[] order = new UUID[positions.length];
        for (int i = 0; i < positions.length; i++) {
            order[i] = positions[i].player();
        }
        assertArrayEquals(new UUID[]{highA, highB, mid, low}, order);
    }

    @Test
    void queuePositionsOnEmptyQueueIsEmptyArray() {
        PlayerQueue queue = new PlayerQueue(10);
        assertEquals(0, queue.queuePositions().length);
    }

    @Test
    void dequePlayerOnEmptyReturnsEmpty() {
        PlayerQueue queue = new PlayerQueue(10);
        Optional<QueueEntry> result = queue.dequePlayer();
        assertTrue(result.isEmpty());
    }
}
