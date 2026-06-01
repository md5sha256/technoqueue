package io.github.md5sha256.technoqueue;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.md5sha256.technoqueue.config.PermissionWeight;
import io.github.md5sha256.technoqueue.config.ServerQueueData;
import io.github.md5sha256.technoqueue.config.ServerQueueSetting;
import io.github.md5sha256.technoqueue.config.ServerSetting;
import io.github.md5sha256.technoqueue.config.Settings;
import io.github.md5sha256.technoqueue.localization.MessageContainer;
import io.github.md5sha256.technoqueue.localization.MessageDefinitions;
import io.github.md5sha256.technoqueue.localization.MessagesYamlLoader;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Technoqueue {

    private final Logger logger;
    private final ProxyServer server;
    private final Path dataDir;
    private final QueueManager queueManager = new QueueManager();
    private final MessageContainer messages = new MessageContainer();
    // Seconds a disconnected player's queue spot is held; 0 disables the grace
    // period. Read from config on init and consulted by the promotion-failure
    // handler when a player drops mid-promotion.
    private long disconnectGraceSeconds;

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
        List<Pattern> noRequeuePatterns = compileNoRequeuePatterns(config.noRequeueKickReasons());
        Duration drainInterval = Duration.ofSeconds(config.drainIntervalSeconds());
        Duration actionBarInterval = Duration.ofSeconds(config.actionBarIntervalSeconds());
        this.disconnectGraceSeconds = config.disconnectGracePeriodSeconds();
        for (Map.Entry<String, ServerSetting> mapEntry : config.servers().entrySet()) {
            String name = mapEntry.getKey();
            ServerSetting entry = mapEntry.getValue();
            ServerQueueSetting queueSetting = entry.queueSetting();
            if (queueSetting == null) {
                // No queue-settings means this server isn't queue-managed; leave it alone.
                continue;
            }
            if (queueSetting.fallbacks().isEmpty()) {
                logger.warn("Skipping queue for '{}' — 'fallbacks' is empty.", name);
                continue;
            }
            Optional<RegisteredServer> target = server.getServer(name);
            if (target.isEmpty()) {
                logger.warn("Skipping queue for '{}' — server not registered with proxy.", name);
                continue;
            }
            List<RegisteredServer> fallbacks = new ArrayList<>(queueSetting.fallbacks().size());
            for (String fallbackName : queueSetting.fallbacks()) {
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
                    queueSetting.targetCapacity(),
                    queueSetting.maxQueueSize(),
                    entry.bypassPermission()
            );
            queueManager.register(data);
            scheduleDrain(data, drainInterval);
            if (entry.showActionBar()
                    && !actionBarInterval.isZero()
                    && !actionBarInterval.isNegative()) {
                scheduleActionBar(data, actionBarInterval);
            }
            logger.info("Registered queue for '{}' (capacity={}, maxQueue={}, fallbacks={}).",
                    name, queueSetting.targetCapacity(), queueSetting.maxQueueSize(),
                    queueSetting.fallbacks());
        }
        if (disconnectGraceSeconds > 0) {
            // Reaps queued players whose grace window lapsed but whom the drain
            // never reached (e.g. the target had no capacity to promote into).
            scheduleSweeper(Duration.ofSeconds(1));
        }
        server.getEventManager()
                .register(this, new QueueListener(queueManager, messages, luckPerms,
                        permissionWeights, noRequeuePatterns, disconnectGraceSeconds));
        registerCommands();
    }

    // Compiles the configured no-requeue kick-reason regexes (case-insensitive),
    // skipping blanks and warning on — rather than failing over — invalid syntax.
    private @NotNull List<Pattern> compileNoRequeuePatterns(@NotNull List<String> raw) {
        List<Pattern> patterns = new ArrayList<>(raw.size());
        for (String regex : raw) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                logger.warn("Ignoring invalid no-requeue-kick-reasons regex '{}': {}",
                        regex, e.getMessage());
            }
        }
        return List.copyOf(patterns);
    }

    private void registerCommands() {
        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("queue")
                .plugin(this)
                .build();
        commandManager.register(meta, new QueueCommand(queueManager, messages));
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

    private void scheduleSweeper(@NotNull Duration interval) {
        server.getScheduler()
                .buildTask(this, queueManager::sweepExpired)
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
        while (queueManager.hasCapacityFor(data)) {
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
                            logger.info("Failed to promote {} to {}; re-queueing ({}).",
                                    uuid,
                                    data.serverName(),
                                    describePromotionFailure(result),
                                    err);
                            if (disconnectGraceSeconds > 0 && server.getPlayer(uuid).isEmpty()) {
                                // Dropped mid-promotion: restore them to the head
                                // of their tier and start their grace window so
                                // they keep their place while offline rather than
                                // going to the back of the queue.
                                queueManager.requeueAtHead(data.serverName(), entry);
                                queueManager.markOffline(uuid, disconnectGraceSeconds);
                            } else {
                                queueManager.enqueue(uuid, data.serverName(), weight);
                            }
                        }
                    });
        }
    }

    // Summarizes why a connection request failed for logging. A null result
    // means the request completed exceptionally (the throwable is logged
    // separately); otherwise we surface the connection status and any reason
    // component the backend supplied (e.g. a kick message during config).
    private static @NotNull String describePromotionFailure(
            @Nullable ConnectionRequestBuilder.Result result) {
        if (result == null) {
            return "connection error";
        }
        StringBuilder sb = new StringBuilder("status=").append(result.getStatus());
        result.getReasonComponent().ifPresent(reason -> sb.append(", reason=")
                .append(PlainTextComponentSerializer.plainText().serialize(reason)));
        return sb.toString();
    }
}
