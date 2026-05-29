package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

public class ServerQueueData {

    private final String serverName;
    private final RegisteredServer targetServer;
    private final RegisteredServer fallbackServer;
    private final int targetCapacity;
    private final PlayerQueue queue;

    public ServerQueueData(
            @NotNull String serverName,
            @NotNull RegisteredServer targetServer,
            @NotNull RegisteredServer fallbackServer,
            int targetCapacity,
            int maxQueueSize
    ) {
        this.serverName = serverName;
        this.targetServer = targetServer;
        this.fallbackServer = fallbackServer;
        this.targetCapacity = targetCapacity;
        this.queue = new PlayerQueue(maxQueueSize);
    }

    public @NotNull String serverName() {
        return this.serverName;
    }

    public @NotNull RegisteredServer targetServer() {
        return this.targetServer;
    }

    public @NotNull RegisteredServer fallbackServer() {
        return this.fallbackServer;
    }

    public int targetCapacity() {
        return this.targetCapacity;
    }

    public @NotNull PlayerQueue queue() {
        return this.queue;
    }

    public boolean hasCapacity() {
        return this.targetServer.getPlayersConnected().size() < this.targetCapacity;
    }
}
