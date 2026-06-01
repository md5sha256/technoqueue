package io.github.md5sha256.technoqueue;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PlayerQueue {

    private final Lock lock = new ReentrantLock();

    private final Map<UUID, QueueEntry> queueEntryMap = new HashMap<>();
    // Buckets are kept sorted by weight, highest first. Each bucket groups
    // entries that share the same weight and preserves their FIFO order.
    private final List<Deque<QueueEntry>> buckets = new ArrayList<>();
    private final int queueMaxSize;
    private int size = 0;

    public PlayerQueue(int queueMaxSize) {
        this.queueMaxSize = queueMaxSize;
    }

    public int size() {
        try {
            this.lock.lock();
            return this.size;
        } finally {
            this.lock.unlock();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean enqueuePlayer(@NotNull UUID uuid, int weight) {
        lock.lock();
        try {
            QueueEntry existing = queueEntryMap.remove(uuid);
            if (existing != null) {
                removeFromBuckets(existing);
                size--;
            }
            if (size == queueMaxSize) {
                return false;
            }
            QueueEntry entry = new QueueEntry(uuid, weight, Instant.now());
            insertIntoBucket(entry, false);
            queueEntryMap.put(uuid, entry);
            size++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    // Re-inserts a previously dequeued entry at the FRONT of its weight tier,
    // preserving its original weight/queueTime. Used when a player was popped
    // for promotion but their connection failed while they held the head, so
    // they reclaim the front of their tier rather than going to the back.
    public boolean requeueAtHead(@NotNull QueueEntry entry) {
        lock.lock();
        try {
            QueueEntry existing = queueEntryMap.remove(entry.player());
            if (existing != null) {
                removeFromBuckets(existing);
                size--;
            }
            if (size == queueMaxSize) {
                return false;
            }
            insertIntoBucket(entry, true);
            queueEntryMap.put(entry.player(), entry);
            size++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Optional<QueueEntry> dequePlayer() {
        lock.lock();
        try {
            if (buckets.isEmpty()) {
                return Optional.empty();
            }
            Deque<QueueEntry> top = buckets.getFirst();
            // bucket can never be empty, empty buckets either don't exist or are removed
            QueueEntry next = top.poll();
            if (top.isEmpty()) {
                buckets.removeFirst();
            }
            queueEntryMap.remove(next.player());
            size--;
            return Optional.of(next);
        } finally {
            lock.unlock();
        }
    }

    public void dequeuePlayer(@NotNull UUID uuid) {
        lock.lock();
        try {
            QueueEntry existing = queueEntryMap.remove(uuid);
            if (existing != null) {
                removeFromBuckets(existing);
                size--;
            }
        } finally {
            lock.unlock();
        }
    }

    public @NotNull QueueEntry[] queuePositions() {
        lock.lock();
        try {
            QueueEntry[] result = new QueueEntry[size];
            int i = 0;
            for (Deque<QueueEntry> bucket : buckets) {
                for (QueueEntry entry : bucket) {
                    result[i++] = entry;
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            buckets.clear();
            queueEntryMap.clear();
            size = 0;
        } finally {
            lock.unlock();
        }
    }

    // Inserts the entry into its weight tier. When `front` is true it goes to
    // the head of that tier (used by requeueAtHead); otherwise it appends,
    // preserving FIFO order within the tier.
    private void insertIntoBucket(QueueEntry entry, boolean front) {
        int weight = entry.weight();
        for (int i = 0; i < buckets.size(); i++) {
            // bucket won't ever be empty because empty buckets are removed
            int bucketWeight = buckets.get(i).peek().weight();
            if (bucketWeight == weight) {
                if (front) {
                    buckets.get(i).offerFirst(entry);
                } else {
                    buckets.get(i).offer(entry);
                }
                return;
            }
            if (bucketWeight < weight) {
                Deque<QueueEntry> bucket = new ArrayDeque<>();
                bucket.offer(entry);
                buckets.add(i, bucket);
                return;
            }
        }
        Deque<QueueEntry> bucket = new ArrayDeque<>();
        bucket.offer(entry);
        buckets.add(bucket);
    }

    private void removeFromBuckets(QueueEntry entry) {
        Iterator<Deque<QueueEntry>> it = buckets.iterator();
        while (it.hasNext()) {
            Deque<QueueEntry> bucket = it.next();
            // bucket won't ever be empty because empty buckets are removed
            if (bucket.peek().weight() != entry.weight()) {
                continue;
            }
            if (bucket.remove(entry) && bucket.isEmpty()) {
                it.remove();
            }
            return;
        }
    }
}
