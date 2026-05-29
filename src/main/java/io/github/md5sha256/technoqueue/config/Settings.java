package io.github.md5sha256.technoqueue.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;
import java.util.Map;

@ConfigSerializable
public record Settings(@NotNull @Setting @Required Map<String, ServerEntry> servers,
                       @NotNull @Setting List<PermissionWeight> permissions) {

    public Settings() {
        this(Map.of(), List.of());
    }

    public Settings(@NotNull Map<String, ServerEntry> servers,
                    @NotNull List<PermissionWeight> permissions) {
        this.servers = Map.copyOf(servers);
        this.permissions = List.copyOf(permissions);
    }
}
