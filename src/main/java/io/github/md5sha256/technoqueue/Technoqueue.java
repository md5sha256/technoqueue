package io.github.md5sha256.technoqueue;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class Technoqueue {

    private static final Duration DRAIN_INTERVAL = Duration.ofSeconds(10);

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
        Settings config;
        try {
            config = loadConfig(dataDir);
        } catch (Exception e) {
            logger.error("Failed to load technoqueue config; queues disabled.", e);
            return;
        }
        for (Map.Entry<String, ServerEntry> mapEntry : config.servers().entrySet()) {
            String name = mapEntry.getKey();
            ServerEntry entry = mapEntry.getValue();
            if (entry.fallback().isBlank()) {
                logger.warn("Skipping queue for '{}' — missing 'fallback'.", name);
                continue;
            }
            Optional<RegisteredServer> target = server.getServer(name);
            Optional<RegisteredServer> fallback = server.getServer(entry.fallback());
            if (target.isEmpty()) {
                logger.warn("Skipping queue for '{}' — server not registered with proxy.", name);
                continue;
            }
            if (fallback.isEmpty()) {
                logger.warn("Skipping queue for '{}' — fallback '{}' not registered.", name, entry.fallback());
                continue;
            }
            ServerQueueData data = new ServerQueueData(
                    name,
                    target.get(),
                    fallback.get(),
                    entry.targetCapacity(),
                    entry.maxQueueSize()
            );
            queueManager.register(data);
            scheduleDrain(data);
            logger.info("Registered queue for '{}' (capacity={}, maxQueue={}, fallback={}).",
                    name, entry.targetCapacity(), entry.maxQueueSize(), entry.fallback());
        }
    }

    @Subscribe
    public void onPlayerConnect(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> chosen = event.getInitialServer();
        if (chosen.isEmpty()) {
            return;
        }
        String serverName = chosen.get().getServerInfo().getName();
        Optional<ServerQueueData> dataOpt = queueManager.get(serverName);
        if (dataOpt.isEmpty()) {
            return;
        }
        ServerQueueData data = dataOpt.get();
        Player player = event.getPlayer();
        if (data.queue().isEmpty() && data.hasCapacity()) {
            return;
        }
        if (queueManager.enqueue(player.getUniqueId(), serverName, 0) == EnqueueResult.SUCCESS) {
            event.setInitialServer(data.fallbackServer());
            player.sendMessage(Component.text(
                    "Server '" + serverName + "' is full — you've been placed in the queue.",
                    NamedTextColor.YELLOW));
        } else {
            event.setInitialServer(null);
            player.disconnect(Component.text(
                    "The queue for '" + serverName + "' is full. Please try again later.",
                    NamedTextColor.RED));
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        RegisteredServer destination = event.getResult().getServer().orElse(event.getOriginalServer());
        String serverName = destination.getServerInfo().getName();
        Optional<ServerQueueData> dataOpt = queueManager.get(serverName);
        if (dataOpt.isEmpty()) {
            return;
        }
        ServerQueueData data = dataOpt.get();
        Player player = event.getPlayer();
        // Allow drain-initiated promotions: if the player is currently queued for this
        // server, this connect is the queue routing them in — let it through.
        Optional<String> queued = queueManager.queuedServer(player.getUniqueId());
        if (queued.isPresent() && queued.get().equals(serverName)) {
            return;
        }
        if (data.queue().isEmpty() && data.hasCapacity()) {
            return;
        }
        if (queueManager.enqueue(player.getUniqueId(), serverName, 0) == EnqueueResult.SUCCESS) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(data.fallbackServer()));
            player.sendMessage(Component.text(
                    "Server '" + serverName + "' is full — you've been placed in the queue.",
                    NamedTextColor.YELLOW));
        } else {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text(
                    "The queue for '" + serverName + "' is full.",
                    NamedTextColor.RED));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        queueManager.dequeue(event.getPlayer().getUniqueId());
    }

    private static @NotNull Settings loadConfig(@NotNull Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("settings.yml");
        if (!Files.exists(file)) {
            writeDefaultConfig(file);
        }
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(file)
                .build();
        CommentedConfigurationNode root = loader.load();
        try {
            Settings config = root.get(Settings.class);
            return config == null ? new Settings() : config;
        } catch (ConfigurateException e) {
            throw new IOException("Failed to parse technoqueue.yml", e);
        }
    }

    private static void writeDefaultConfig(@NotNull Path file) throws IOException {
        try (InputStream in = Technoqueue.class.getResourceAsStream("/settings.yml")) {
            if (in == null) {
                throw new IOException("Default technoqueue.yml resource missing from plugin jar.");
            }
            Files.copy(in, file);
        }
    }

    private void scheduleDrain(@NotNull ServerQueueData data) {
        server.getScheduler()
                .buildTask(this, () -> drain(data))
                .repeat(DRAIN_INTERVAL)
                .delay(DRAIN_INTERVAL)
                .schedule();
    }

    private void drain(@NotNull ServerQueueData data) {
        data.targetServer().ping().whenComplete((ping, error) -> {
            if (error != null) {
                return;
            }
            promoteFromQueue(data);
        });
    }

    private void promoteFromQueue(@NotNull ServerQueueData data) {
        while (data.hasCapacity() && !data.queue().isEmpty()) {
            Optional<QueueEntry> next = data.queue().dequePlayer();
            if (next.isEmpty()) {
                return;
            }
            UUID uuid = next.get().player();
            Optional<Player> playerOpt = server.getPlayer(uuid);
            if (playerOpt.isEmpty()) {
                queueManager.dequeue(uuid);
                continue;
            }
            Player player = playerOpt.get();
            queueManager.dequeue(uuid);
            player.createConnectionRequest(data.targetServer()).connect().whenComplete((result, err) -> {
                if (err != null || result == null || !result.isSuccessful()) {
                    logger.debug("Failed to promote {} to {}; re-queueing.", uuid, data.serverName());
                    queueManager.enqueue(uuid, data.serverName(), 0);
                }
            });
        }
    }
}
