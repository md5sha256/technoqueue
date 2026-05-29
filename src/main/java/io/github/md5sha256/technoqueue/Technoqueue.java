package io.github.md5sha256.technoqueue;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.md5sha256.technoqueue.config.PermissionWeight;
import io.github.md5sha256.technoqueue.config.ServerEntry;
import io.github.md5sha256.technoqueue.config.ServerQueueData;
import io.github.md5sha256.technoqueue.config.Settings;
import io.github.md5sha256.technoqueue.localization.MessageContainer;
import io.github.md5sha256.technoqueue.localization.MessageDefinitions;
import io.github.md5sha256.technoqueue.localization.MessagesYamlLoader;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class Technoqueue {

    private final Logger logger;
    private final ProxyServer server;
    private final Path dataDir;
    private final QueueManager queueManager = new QueueManager();
    private final MessageContainer messages = new MessageContainer();

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
        try {
            this.messages.load(loadMessages());
        } catch (IOException e) {
            logger.error("Failed to load technoqueue messages; using defaults.", e);
        }
        LuckPerms luckPerms = LuckPermsProvider.get();
        List<PermissionWeight> sorted = new ArrayList<>(config.permissions());
        sorted.sort(Comparator.comparingInt(PermissionWeight::weight).reversed());
        List<PermissionWeight> permissionWeights = List.copyOf(sorted);
        Duration drainInterval = Duration.ofSeconds(config.drainIntervalSeconds());
        Duration actionBarInterval = Duration.ofSeconds(config.actionBarIntervalSeconds());
        for (Map.Entry<String, ServerEntry> mapEntry : config.servers().entrySet()) {
            String name = mapEntry.getKey();
            ServerEntry entry = mapEntry.getValue();
            if (entry.fallbacks().isEmpty()) {
                logger.warn("Skipping queue for '{}' — 'fallbacks' is empty.", name);
                continue;
            }
            Optional<RegisteredServer> target = server.getServer(name);
            if (target.isEmpty()) {
                logger.warn("Skipping queue for '{}' — server not registered with proxy.", name);
                continue;
            }
            List<RegisteredServer> fallbacks = new ArrayList<>(entry.fallbacks().size());
            for (String fallbackName : entry.fallbacks()) {
                Optional<RegisteredServer> fallback = server.getServer(fallbackName);
                if (fallback.isEmpty()) {
                    logger.warn("Queue for '{}' — fallback '{}' not registered; skipping it.",
                            name,
                            fallbackName);
                    continue;
                }
                fallbacks.add(fallback.get());
            }
            if (fallbacks.isEmpty()) {
                logger.warn(
                        "Skipping queue for '{}' — none of the configured fallbacks are registered.",
                        name);
                continue;
            }
            ServerQueueData data = new ServerQueueData(
                    name,
                    target.get(),
                    fallbacks,
                    entry.targetCapacity(),
                    entry.maxQueueSize(),
                    entry.bypassPermission()
            );
            queueManager.register(data);
            scheduleDrain(data, drainInterval);
            if (!actionBarInterval.isZero() && !actionBarInterval.isNegative()) {
                scheduleActionBar(data, actionBarInterval);
            }
            logger.info("Registered queue for '{}' (capacity={}, maxQueue={}, fallbacks={}).",
                    name, entry.targetCapacity(), entry.maxQueueSize(), entry.fallbacks());
        }
        server.getEventManager()
                .register(this, new QueueListener(queueManager, messages, luckPerms, permissionWeights));
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

    private static void writeDefaultMessages(@NotNull Path file) throws IOException {
        try (InputStream in = Technoqueue.class.getResourceAsStream("/messages.yml")) {
            if (in == null) {
                throw new IOException("Default messages.yml resource missing from plugin jar.");
            }
            Files.copy(in, file);
        }
    }

    private @NotNull MessageDefinitions loadMessages() throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("messages.yml");
        if (!Files.exists(file)) {
            writeDefaultMessages(file);
        }
        return MessagesYamlLoader.load(file);
    }

    private void scheduleDrain(@NotNull ServerQueueData data, @NotNull Duration interval) {
        server.getScheduler()
                .buildTask(this, () -> drain(data))
                .repeat(interval)
                .delay(interval)
                .schedule();
    }

    private void scheduleActionBar(@NotNull ServerQueueData data, @NotNull Duration interval) {
        server.getScheduler()
                .buildTask(this, () -> sendActionBars(data))
                .repeat(interval)
                .delay(interval)
                .schedule();
    }

    private void sendActionBars(@NotNull ServerQueueData data) {
        QueueEntry[] entries = data.queue().queuePositions();
        if (entries.length == 0) {
            return;
        }
        int size = entries.length;
        for (int i = 0; i < entries.length; i++) {
            UUID uuid = entries[i].player();
            Optional<Player> playerOpt = server.getPlayer(uuid);
            if (playerOpt.isEmpty()) {
                continue;
            }
            int position = i + 1;
            playerOpt.get().sendActionBar(messages.template("queue.action-bar",
                    Placeholder.unparsed("server", data.serverName()),
                    Placeholder.unparsed("position", Integer.toString(position)),
                    Placeholder.unparsed("size", Integer.toString(size))));
        }
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
        while (data.hasCapacity()) {
            Optional<QueueEntry> next = queueManager.beginPromotion(data.serverName());
            if (next.isEmpty()) {
                return;
            }
            QueueEntry entry = next.get();
            UUID uuid = entry.player();
            int weight = entry.weight();
            Optional<Player> playerOpt = server.getPlayer(uuid);
            if (playerOpt.isEmpty()) {
                queueManager.clearPromoting(uuid);
                continue;
            }
            playerOpt.get().createConnectionRequest(data.targetServer())
                    .connect()
                    .whenComplete((result, err) -> {
                        queueManager.clearPromoting(uuid);
                        if (err != null || result == null || !result.isSuccessful()) {
                            logger.debug("Failed to promote {} to {}; re-queueing.",
                                    uuid,
                                    data.serverName());
                            queueManager.enqueue(uuid, data.serverName(), weight);
                        }
                    });
        }
    }
}
