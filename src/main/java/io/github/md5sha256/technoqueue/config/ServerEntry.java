package io.github.md5sha256.technoqueue.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;

@ConfigSerializable
public record ServerEntry(@Setting @Required int targetCapacity,
                          @Setting @Required int maxQueueSize,
                          @Setting @NotNull List<String> fallbacks,
                          @Setting @Nullable String bypassPermission) {

    public ServerEntry() {
        this(200, 500, List.of(), null);
    }

    public ServerEntry(int targetCapacity,
                       int maxQueueSize,
                       @NotNull List<String> fallbacks,
                       @Nullable String bypassPermission) {
        this.targetCapacity = targetCapacity;
        this.maxQueueSize = maxQueueSize;
        this.fallbacks = List.copyOf(fallbacks);
        this.bypassPermission = bypassPermission;
    }
}
