package io.github.md5sha256.technoqueue;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

public record QueueEntry(@NotNull UUID player, int weight,
                         Instant queueTime) implements Comparable<QueueEntry> {
    @Override
    public int compareTo(QueueEntry o) {
        return Integer.compare(this.weight, o.weight);
    }
}
