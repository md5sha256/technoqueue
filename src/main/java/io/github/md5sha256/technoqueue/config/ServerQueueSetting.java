package io.github.md5sha256.technoqueue.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;

@ConfigSerializable
public record ServerQueueSetting(
        @Setting("target-capacity") @Required int targetCapacity,
        @Setting("max-queue-size") @Required int maxQueueSize,
        @Setting("fallbacks") @NotNull List<String> fallbacks
) {

    public ServerQueueSetting() {
        this(200, 500, List.of());
    }

    public ServerQueueSetting(int targetCapacity,
                              int maxQueueSize,
                              @NotNull List<String> fallbacks) {
        this.targetCapacity = targetCapacity;
        this.maxQueueSize = maxQueueSize;
        this.fallbacks = List.copyOf(fallbacks);
    }
}