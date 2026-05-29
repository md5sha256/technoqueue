package io.github.md5sha256.technoqueue;

import io.github.md5sha256.technoqueue.config.ServerQueueData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class QueueManager {

    private final Lock lock = new ReentrantLock();
    private final Map<String, ServerQueueData> queueDataMap = new HashMap<>();
    // Tracks which server queue a player is currently in, so we can enforce
    // that a player only ever occupies one queue at a time.
    private final Map<UUID, String> playerQueueLocation = new HashMap<>();

    public void register(@NotNull ServerQueueData queueData) {
        lock.lock();
        try {
            queueDataMap.put(queueData.serverName(), queueData);
        } finally {
            lock.unlock();
        }
    }

    public @NotNull Optional<ServerQueueData> get(@NotNull String serverName) {
        lock.lock();
        try {
            return Optional.ofNullable(queueDataMap.get(serverName));
        } finally {
            lock.unlock();
        }
    }

    public @NotNull EnqueueResult enqueue(@NotNull UUID player, @NotNull String serverName, int weight) {
        lock.lock();
        try {
            ServerQueueData target = queueDataMap.get(serverName);
            if (target == null) {
                return EnqueueResult.UNKNOWN_SERVER;
            }
            String currentLocation = playerQueueLocation.get(player);
            if (currentLocation != null && !currentLocation.equals(serverName)) {
                ServerQueueData previous = queueDataMap.get(currentLocation);
                if (previous != null) {
                    previous.queue().dequeuePlayer(player);
                }
            }
            boolean enqueued = target.queue().enqueuePlayer(player, weight);
            if (enqueued) {
                playerQueueLocation.put(player, serverName);
                return EnqueueResult.SUCCESS;
            }
            if (currentLocation != null && !currentLocation.equals(serverName)) {
                // We removed the player from their previous queue but couldn't
                // place them in the new one; clear the stale tracking entry.
                playerQueueLocation.remove(player);
            }
            return EnqueueResult.QUEUE_FULL;
        } finally {
            lock.unlock();
        }
    }

    public void dequeue(@NotNull UUID player) {
        lock.lock();
        try {
            String location = playerQueueLocation.remove(player);
            if (location == null) {
                return;
            }
            ServerQueueData data = queueDataMap.get(location);
            if (data != null) {
                data.queue().dequeuePlayer(player);
            }
        } finally {
            lock.unlock();
        }
    }

    public @NotNull Optional<String> queuedServer(@NotNull UUID player) {
        lock.lock();
        try {
            return Optional.ofNullable(playerQueueLocation.get(player));
        } finally {
            lock.unlock();
        }
    }
}
