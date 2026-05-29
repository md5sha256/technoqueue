package io.github.md5sha256.technoqueue.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
public record PermissionWeight(@Setting("permission") @Required @NotNull String permission,
                               @Setting("weight") @Required int weight) {

    public PermissionWeight() {
        this("", 0);
    }
}
