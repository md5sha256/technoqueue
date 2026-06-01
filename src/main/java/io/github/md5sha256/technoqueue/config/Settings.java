package io.github.md5sha256.technoqueue.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;
import java.util.Map;

@ConfigSerializable
public record Settings(@NotNull @Setting("servers") @Required Map<String, ServerSetting> servers,
                       @NotNull @Setting("permissions") List<PermissionWeight> permissions,
                       @Setting("drain-interval-seconds") long drainIntervalSeconds,
                       @Setting("action-bar-interval-seconds") long actionBarIntervalSeconds,
                       @Setting("disconnect-grace-period-seconds") long disconnectGracePeriodSeconds,
                       @NotNull @Setting("no-requeue-kick-reasons") List<String> noRequeueKickReasons) {

    public Settings() {
        this(Map.of(), List.of(), 10L, 2L, 0L, List.of());
    }

    public Settings(@NotNull Map<String, ServerSetting> servers,
                    @NotNull List<PermissionWeight> permissions,
                    long drainIntervalSeconds,
                    long actionBarIntervalSeconds,
                    long disconnectGracePeriodSeconds,
                    @NotNull List<String> noRequeueKickReasons) {
        this.servers = Map.copyOf(servers);
        this.permissions = List.copyOf(permissions);
        this.drainIntervalSeconds = drainIntervalSeconds;
        this.actionBarIntervalSeconds = actionBarIntervalSeconds;
        this.disconnectGracePeriodSeconds = disconnectGracePeriodSeconds;
        // Defensive: a config file predating this key leaves the node absent.
        this.noRequeueKickReasons =
                noRequeueKickReasons == null ? List.of() : List.copyOf(noRequeueKickReasons);
    }
}
