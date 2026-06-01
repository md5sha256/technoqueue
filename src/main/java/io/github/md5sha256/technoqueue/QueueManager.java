package io.github.md5sha256.technoqueue;

import io.github.md5sha256.technoqueue.config.ServerQueueData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Iterator;
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
    // Players whose connect was initiated by the drain, mapped to the server
    // they are being promoted into. The listener uses this to distinguish
    // queue-driven promotions from manual /server attempts — without it, any
    // queued player could bypass the queue by running /server. The target
    // server is recorded so an in-flight promotion counts against that server's
    // capacity, which both throttles the drain and stops players from racing
    // into the slot before the promoted connection lands.
    private final Map<UUID, String> promoting = new HashMap<>();
    // Players who disconnected from the proxy while queued, mapped to the
    // wall-clock millis at which their grace window lapses. They stay in their
    // queue at their position; the drain skips them (promoting online players
    // behind them) until they either reconnect (markOnline) or the window
    // expires, at which point they are dropped. Empty when the grace feature is
    // disabled, since the listener dequeues disconnects outright in that case.
    private final Map<UUID, Long> offlineDeadline = new HashMap<>();

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
            offlineDeadline.remove(player);
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

    // Walks the named queue in priority order and atomically pops the first
    // promotable player, clearing its location tracking and marking it as being
    // promoted. Splitting the pop/clear/mark steps would let a queued player's
    // manual /server slip through and bypass the queue, so they stay together
    // under the one lock.
    //
    // Players inside their disconnect grace window are skipped (left in place so
    // they keep their position) and the player behind them is promoted instead;
    // players whose grace window has lapsed are dropped as they're passed. A
    // player not flagged offline is treated as promotable.
    public @NotNull Optional<QueueEntry> beginPromotion(@NotNull String serverName) {
        lock.lock();
        try {
            ServerQueueData data = queueDataMap.get(serverName);
            if (data == null) {
                return Optional.empty();
            }
            long now = System.currentTimeMillis();
            for (QueueEntry entry : data.queue().queuePositions()) {
                UUID uuid = entry.player();
                Long deadline = offlineDeadline.get(uuid);
                if (deadline != null) {
                    if (deadline > now) {
                        // Disconnected but within grace: leave them in place.
                        continue;
                    }
                    // Grace lapsed: drop and keep scanning for an online player.
                    data.queue().dequeuePlayer(uuid);
                    playerQueueLocation.remove(uuid);
                    offlineDeadline.remove(uuid);
                    continue;
                }
                data.queue().dequeuePlayer(uuid);
                playerQueueLocation.remove(uuid);
                promoting.put(uuid, serverName);
                return Optional.of(entry);
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    // Marks a queued player as disconnected, starting their grace window. The
    // player keeps their queue position; beginPromotion skips them until they
    // reconnect or the window lapses. No-op if the player isn't in a queue (e.g.
    // a disconnect mid-promotion, handled by requeueAtHead instead).
    public void markOffline(@NotNull UUID player, long graceSeconds) {
        lock.lock();
        try {
            if (!playerQueueLocation.containsKey(player)) {
                return;
            }
            offlineDeadline.put(player, System.currentTimeMillis() + graceSeconds * 1000L);
        } finally {
            lock.unlock();
        }
    }

    // Clears a reconnecting player's grace window so the drain can promote them
    // again. They keep the queue position they held while offline.
    public void markOnline(@NotNull UUID player) {
        lock.lock();
        try {
            offlineDeadline.remove(player);
        } finally {
            lock.unlock();
        }
    }

    // Drops queued players whose grace window has lapsed. Run on a timer so
    // offline players are cleaned up even when beginPromotion never reaches them
    // (e.g. the target has no capacity, so the drain promotes nobody).
    public void sweepExpired() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, Long>> it = offlineDeadline.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> deadline = it.next();
                if (deadline.getValue() > now) {
                    continue;
                }
                UUID player = deadline.getKey();
                it.remove();
                String location = playerQueueLocation.remove(player);
                if (location != null) {
                    ServerQueueData data = queueDataMap.get(location);
                    if (data != null) {
                        data.queue().dequeuePlayer(player);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // Restores a player who was popped for promotion but whose connection failed
    // while they were the head, putting them back at the front of their weight
    // tier. Paired with markOffline when the failure was a disconnect, so they
    // keep their place while offline and are first in line on reconnect.
    public boolean requeueAtHead(@NotNull String serverName, @NotNull QueueEntry entry) {
        lock.lock();
        try {
            ServerQueueData data = queueDataMap.get(serverName);
            if (data == null) {
                return false;
            }
            boolean queued = data.queue().requeueAtHead(entry);
            if (queued) {
                playerQueueLocation.put(entry.player(), serverName);
            }
            return queued;
        } finally {
            lock.unlock();
        }
    }

    public void clearPromoting(@NotNull UUID player) {
        lock.lock();
        try {
            promoting.remove(player);
        } finally {
            lock.unlock();
        }
    }

    public boolean isPromoting(@NotNull UUID player) {
        lock.lock();
        try {
            return promoting.containsKey(player);
        } finally {
            lock.unlock();
        }
    }

    // True when the target has room for one more player, counting promotions
    // that the drain has already initiated but whose connections have not yet
    // landed (Velocity only counts a player as connected once the connect
    // completes). Used to throttle the drain so it never promotes more players
    // than there are free slots.
    public boolean hasCapacityFor(@NotNull ServerQueueData data) {
        lock.lock();
        try {
            return effectiveLoad(data) < data.targetCapacity();
        } finally {
            lock.unlock();
        }
    }

    // True when a player may connect straight to the target without queueing:
    // nobody is waiting and there is real capacity once in-flight promotions are
    // accounted for. Evaluating both conditions under the lock closes the race
    // where the drain has just popped the last queued player (queue now empty)
    // but their promotion has not yet occupied the slot.
    public boolean canConnectDirectly(@NotNull ServerQueueData data) {
        lock.lock();
        try {
            return data.queue().isEmpty() && effectiveLoad(data) < data.targetCapacity();
        } finally {
            lock.unlock();
        }
    }

    // Connected players plus in-flight promotions into this server. Caller must
    // hold the lock.
    private int effectiveLoad(@NotNull ServerQueueData data) {
        int promotingCount = 0;
        for (String target : promoting.values()) {
            if (target.equals(data.serverName())) {
                promotingCount++;
            }
        }
        return data.targetServer().getPlayersConnected().size() + promotingCount;
    }

    public @NotNull Optional<String> queuedServer(@NotNull UUID player) {
        lock.lock();
        try {
            return Optional.ofNullable(playerQueueLocation.get(player));
        } finally {
            lock.unlock();
        }
    }

    public @NotNull Optional<QueueStatus> status(@NotNull UUID player) {
        lock.lock();
        try {
            String serverName = playerQueueLocation.get(player);
            if (serverName == null) {
                return Optional.empty();
            }
            ServerQueueData data = queueDataMap.get(serverName);
            if (data == null) {
                return Optional.empty();
            }
            QueueEntry[] entries = data.queue().queuePositions();
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].player().equals(player)) {
                    return Optional.of(new QueueStatus(serverName, i + 1, entries.length));
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public record QueueStatus(@NotNull String serverName, int position, int size) {
    }
}
