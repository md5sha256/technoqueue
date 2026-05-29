package io.github.md5sha256.technoqueue;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Map;

@ConfigSerializable
public record Settings(@NotNull @Setting @Required Map<String, ServerEntry> servers) {

    public Settings() {
        this(Map.of());
    }

    public Settings(@NotNull Map<String, ServerEntry> servers) {
        this.servers = Map.copyOf(servers);
    }
}
