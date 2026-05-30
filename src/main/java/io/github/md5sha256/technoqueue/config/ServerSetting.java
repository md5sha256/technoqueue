package io.github.md5sha256.technoqueue.config;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
public record ServerSetting(
        @Setting("queue-settings") @Nullable ServerQueueSetting queueSetting,
        @Setting("bypass-permission") @Nullable String bypassPermission
) {
    public ServerSetting() {
        this(new ServerQueueSetting(), null);
    }
}