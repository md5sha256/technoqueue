package io.github.md5sha256.technoqueue;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;

public class Technoqueue {

    private final Logger logger;
    private final ProxyServer server;
    private final Path dataDir;
    private final QueueManager queueManager = new QueueManager();

    @Inject
    public Technoqueue(@NotNull Logger logger,
                       @NotNull ProxyServer server,
                       @DataDirectory @NotNull Path dataDir) {
        this.logger = logger;
        this.server = server;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Plugin initialization logic goes here

    }

    @Subscribe
    public void onPlayerConnect(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> optional = event.getInitialServer();
        if (optional.isEmpty()) {
            return;
        }
        RegisteredServer registeredServer = optional.get();
        String serverName = registeredServer.getServerInfo().getName();
        Optional<ServerQueueData> queueDataOpt = this.queueManager.get(serverName);
        if (queueDataOpt.isEmpty()) {
            return;
        }
        ServerQueueData queueData = queueDataOpt.get();
        RegisteredServer targetServer = queueData.getTargetServer(event.getPlayer().getUniqueId());
        event.setInitialServer(targetServer);
        this.queueManager.enqueue(event.getPlayer().getUniqueId(), serverName, 0);
    }
}
