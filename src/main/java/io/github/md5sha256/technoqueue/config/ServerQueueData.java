package io.github.md5sha256.technoqueue.config;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.md5sha256.technoqueue.PlayerQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ServerQueueData(String serverName, RegisteredServer targetServer,
                              List<RegisteredServer> fallbackServers, int targetCapacity,
                              PlayerQueue queue, @Nullable String bypassPermission) {

    public ServerQueueData(
            @NotNull String serverName,
            @NotNull RegisteredServer targetServer,
            @NotNull List<RegisteredServer> fallbackServers,
            int targetCapacity,
            int queue,
            @Nullable String bypassPermission
    ) {
        this(serverName, targetServer, fallbackServers, targetCapacity, new PlayerQueue(queue), bypassPermission);
    }

    public ServerQueueData(
            @NotNull String serverName,
            RegisteredServer targetServer,
            @NotNull List<RegisteredServer> fallbackServers,
            int targetCapacity,
            @NotNull PlayerQueue queue,
            @Nullable String bypassPermission
    ) {
        this.serverName = serverName;
        this.targetServer = targetServer;
        this.fallbackServers = List.copyOf(fallbackServers);
        this.targetCapacity = targetCapacity;
        this.queue = queue;
        this.bypassPermission = bypassPermission;
    }

    @Override
    public @NotNull String serverName() {
        return this.serverName;
    }

    @Override
    public @NotNull RegisteredServer targetServer() {
        return this.targetServer;
    }

    @Override
    public @NotNull List<RegisteredServer> fallbackServers() {
        return this.fallbackServers;
    }

    @Override
    public @NotNull PlayerQueue queue() {
        return this.queue;
    }
}
