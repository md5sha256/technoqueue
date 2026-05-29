package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ServerQueueData(String serverName, RegisteredServer targetServer,
                              List<RegisteredServer> fallbackServers, int targetCapacity,
                              PlayerQueue queue) {

    public ServerQueueData(
            @NotNull String serverName,
            @NotNull RegisteredServer targetServer,
            @NotNull List<RegisteredServer> fallbackServers,
            int targetCapacity,
            int queue
    ) {
        this(serverName, targetServer, fallbackServers, targetCapacity, new PlayerQueue(queue));
    }

    public ServerQueueData(
            @NotNull String serverName,
            RegisteredServer targetServer,
            @NotNull List<RegisteredServer> fallbackServers,
            int targetCapacity,
            @NotNull PlayerQueue queue
    ) {
        this.serverName = serverName;
        this.targetServer = targetServer;
        this.fallbackServers = List.copyOf(fallbackServers);
        this.targetCapacity = targetCapacity;
        this.queue = queue;
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

    public boolean hasCapacity() {
        return this.targetServer.getPlayersConnected().size() < this.targetCapacity;
    }
}
