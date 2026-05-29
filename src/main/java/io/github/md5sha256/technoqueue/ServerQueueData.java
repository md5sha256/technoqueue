package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

public record ServerQueueData(String serverName, RegisteredServer targetServer,
                              RegisteredServer fallbackServer, int targetCapacity,
                              PlayerQueue queue) {

    public ServerQueueData(
            @NotNull String serverName,
            @NotNull RegisteredServer targetServer,
            @NotNull RegisteredServer fallbackServer,
            int targetCapacity,
            int queue
    ) {
        this(serverName, targetServer, fallbackServer, targetCapacity, new PlayerQueue(queue));
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
    public @NotNull RegisteredServer fallbackServer() {
        return this.fallbackServer;
    }

    @Override
    public @NotNull PlayerQueue queue() {
        return this.queue;
    }

    public boolean hasCapacity() {
        return this.targetServer.getPlayersConnected().size() < this.targetCapacity;
    }
}
