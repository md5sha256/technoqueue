package io.github.md5sha256.technoqueue;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
public record ServerEntry(@Setting @Required int targetCapacity,
                          @Setting @Required int maxQueueSize,
                          @Setting @Nullable String fallback) {

    public ServerEntry() {
        this(200, 500, "");
    }
}
