package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Function;

public class ServerQueueData {

    private final String serverName;
    private final PlayerQueue queue;
    private final Function<UUID, RegisteredServer> targetServerProvider;

    public ServerQueueData(
            @NotNull String serverName,
            int maxQueueSize,
            @NotNull Function<UUID, RegisteredServer> targetServerProvider
    ) {
        this.serverName = serverName;
        this.queue = new PlayerQueue(maxQueueSize);
        this.targetServerProvider = targetServerProvider;
    }

    @NotNull
    public String serverName() {
        return this.serverName;
    }

    @NotNull
    public PlayerQueue queue() {
        return this.queue;
    }

    @NotNull
    public RegisteredServer getTargetServer(@NotNull UUID playerId) {
        return this.targetServerProvider.apply(playerId);
    }



}
