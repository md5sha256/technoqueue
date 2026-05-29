package io.github.md5sha256.technoqueue;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;

@ConfigSerializable
public record ServerEntry(@Setting @Required int targetCapacity,
                          @Setting @Required int maxQueueSize,
                          @Setting @NotNull List<String> fallbacks) {

    public ServerEntry() {
        this(200, 500, List.of());
    }

    public ServerEntry(int targetCapacity, int maxQueueSize, @NotNull List<String> fallbacks) {
        this.targetCapacity = targetCapacity;
        this.maxQueueSize = maxQueueSize;
        this.fallbacks = List.copyOf(fallbacks);
    }
}
