package io.github.md5sha256.technoqueue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueManagerTest {

    private static ServerQueueData data(String name, int maxQueueSize) {
        // QueueManager never touches targetServer/fallbackServers, so nulls are safe here.
        return new ServerQueueData(name, null, List.of(), 0, new PlayerQueue(maxQueueSize));
    }

    @Test
    void getReturnsEmptyWhenServerNotRegistered() {
        QueueManager manager = new QueueManager();
        assertTrue(manager.get("missing").isEmpty());
    }

    @Test
    void registerMakesServerRetrievable() {
        QueueManager manager = new QueueManager();
        ServerQueueData data = data("main", 10);
        manager.register(data);

        Optional<ServerQueueData> looked = manager.get("main");
        assertTrue(looked.isPresent());
        assertEquals(data, looked.get());
    }

    @Test
    void registerOverwritesPriorEntry() {
        QueueManager manager = new QueueManager();
        ServerQueueData first = data("main", 10);
        ServerQueueData second = data("main", 20);
        manager.register(first);
        manager.register(second);

        assertEquals(second, manager.get("main").orElseThrow());
    }

    @Test
    void enqueueRejectsUnknownServer() {
        QueueManager manager = new QueueManager();
        assertEquals(EnqueueResult.UNKNOWN_SERVER, manager.enqueue(UUID.randomUUID(), "missing", 0));
    }

    @Test
    void enqueueAddsPlayerAndTracksLocation() {
        QueueManager manager = new QueueManager();
        ServerQueueData data = data("main", 10);
        manager.register(data);
        UUID player = UUID.randomUUID();

        assertEquals(EnqueueResult.SUCCESS, manager.enqueue(player, "main", 0));
        assertEquals(1, data.queue().size());
        assertEquals(Optional.of("main"), manager.queuedServer(player));
    }

    @Test
    void enqueueAtFullQueueIsRejectedAndLeavesPlayerUnqueued() {
        QueueManager manager = new QueueManager();
        ServerQueueData data = data("main", 1);
        manager.register(data);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertEquals(EnqueueResult.SUCCESS, manager.enqueue(first, "main", 0));
        assertEquals(EnqueueResult.QUEUE_FULL, manager.enqueue(second, "main", 0));

        assertTrue(manager.queuedServer(second).isEmpty());
        assertEquals(Optional.of("main"), manager.queuedServer(first));
    }

    @Test
    void enqueueMovesPlayerBetweenServers() {
        QueueManager manager = new QueueManager();
        ServerQueueData a = data("a", 10);
        ServerQueueData b = data("b", 10);
        manager.register(a);
        manager.register(b);
        UUID player = UUID.randomUUID();

        assertEquals(EnqueueResult.SUCCESS, manager.enqueue(player, "a", 0));
        assertEquals(1, a.queue().size());

        assertEquals(EnqueueResult.SUCCESS, manager.enqueue(player, "b", 0));
        assertEquals(0, a.queue().size());
        assertEquals(1, b.queue().size());
        assertEquals(Optional.of("b"), manager.queuedServer(player));
    }

    @Test
    void enqueueFailingOnNewServerClearsStaleLocation() {
        QueueManager manager = new QueueManager();
        ServerQueueData a = data("a", 10);
        ServerQueueData full = data("full", 1);
        manager.register(a);
        manager.register(full);
        UUID blocker = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        manager.enqueue(blocker, "full", 0);
        manager.enqueue(player, "a", 0);

        // Move into a server with no room left; player should be removed from "a"
        // and end up tracked in neither queue.
        assertEquals(EnqueueResult.QUEUE_FULL, manager.enqueue(player, "full", 0));
        assertEquals(0, a.queue().size());
        assertEquals(1, full.queue().size());
        assertTrue(manager.queuedServer(player).isEmpty());
    }

    @Test
    void enqueueSameServerIsTreatedAsReEnqueue() {
        QueueManager manager = new QueueManager();
        ServerQueueData data = data("main", 10);
        manager.register(data);
        UUID player = UUID.randomUUID();

        assertEquals(EnqueueResult.SUCCESS, manager.enqueue(player, "main", 0));
        assertEquals(EnqueueResult.SUCCESS, manager.enqueue(player, "main", 5));
        assertEquals(1, data.queue().size());
        assertEquals(Optional.of("main"), manager.queuedServer(player));

        // Updated weight should be reflected in the underlying entry.
        QueueEntry head = data.queue().dequePlayer().orElseThrow();
        assertEquals(player, head.player());
        assertEquals(5, head.weight());
    }

    @Test
    void dequeueRemovesPlayerFromTrackingAndQueue() {
        QueueManager manager = new QueueManager();
        ServerQueueData data = data("main", 10);
        manager.register(data);
        UUID player = UUID.randomUUID();
        manager.enqueue(player, "main", 0);

        manager.dequeue(player);
        assertEquals(0, data.queue().size());
        assertTrue(manager.queuedServer(player).isEmpty());
    }

    @Test
    void dequeueUnknownPlayerIsNoOp() {
        QueueManager manager = new QueueManager();
        ServerQueueData data = data("main", 10);
        manager.register(data);
        manager.enqueue(UUID.randomUUID(), "main", 0);

        manager.dequeue(UUID.randomUUID());
        assertEquals(1, data.queue().size());
    }

    @Test
    void queuedServerReturnsEmptyForUnknownPlayer() {
        QueueManager manager = new QueueManager();
        assertTrue(manager.queuedServer(UUID.randomUUID()).isEmpty());
    }
}
