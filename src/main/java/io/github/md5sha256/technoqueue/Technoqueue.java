package io.github.md5sha256.technoqueue;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
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
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
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
    private List<PermissionWeight> permissionWeights = List.of();
    private LuckPerms luckPerms;

    @Inject
    public Technoqueue(@NotNull Logger logger,
                       @NotNull ProxyServer server,
                       @DataDirectory @NotNull Path dataDir) {
        this.logger = logger;
        this.server = server;
        this.dataDir = dataDir;
    }

    private boolean hasBypass(@NotNull Player player, @NotNull ServerQueueData data) {
        String permission = data.bypassPermission();
        if (permission == null) {
            return false;
        }
        CachedPermissionData permData = permissionData(player);
        return permData != null && permData.checkPermission(permission).asBoolean();
    }

    private @org.jetbrains.annotations.Nullable CachedPermissionData permissionData(@NotNull Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        ContextManager contextManager = luckPerms.getContextManager();
        QueryOptions options = contextManager.getQueryOptions(user)
                .orElseGet(contextManager::getStaticQueryOptions);
        return user.getCachedData().getPermissionData(options);
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
        this.luckPerms = LuckPermsProvider.get();
        List<PermissionWeight> sorted = new ArrayList<>(config.permissions());
        sorted.sort(Comparator.comparingInt(PermissionWeight::weight).reversed());
        this.permissionWeights = List.copyOf(sorted);
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
        if (hasBypass(player, data)) {
            return;
        }
        if (data.queue().isEmpty() && data.hasCapacity()) {
            return;
        }
        Optional<RegisteredServer> fallback = selectFallback(data);
        if (fallback.isPresent() && queueManager.enqueue(player.getUniqueId(),
                serverName,
                resolveWeight(player)) == EnqueueResult.SUCCESS) {
            event.setInitialServer(fallback.get());
            player.sendMessage(messages.prefixedTemplate("queue.joined",
                    Placeholder.unparsed("server", serverName)));
        } else {
            event.setInitialServer(null);
            player.disconnect(messages.template("queue.full-disconnect",
                    Placeholder.unparsed("server", serverName)));
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        RegisteredServer destination = event.getResult()
                .getServer()
                .orElse(event.getOriginalServer());
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
        if (hasBypass(player, data)) {
            return;
        }
        if (data.queue().isEmpty() && data.hasCapacity()) {
            return;
        }
        Optional<RegisteredServer> fallback = selectFallback(data);
        if (fallback.isPresent() && queueManager.enqueue(player.getUniqueId(),
                serverName,
                resolveWeight(player)) == EnqueueResult.SUCCESS) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(fallback.get()));
            player.sendMessage(messages.prefixedTemplate("queue.joined",
                    Placeholder.unparsed("server", serverName)));
        } else {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(messages.prefixedTemplate("queue.full-denied",
                    Placeholder.unparsed("server", serverName)));
        }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        Optional<String> queued = queueManager.queuedServer(player.getUniqueId());
        if (queued.isEmpty()) {
            return;
        }
        Optional<ServerQueueData> dataOpt = queueManager.get(queued.get());
        if (dataOpt.isEmpty()) {
            return;
        }
        ServerQueueData data = dataOpt.get();
        String kickedFrom = event.getServer().getServerInfo().getName();
        // Only redirect when the player was kicked from one of this queue's
        // fallbacks; kicks from the target server are handled by the normal
        // disconnect flow.
        Optional<RegisteredServer> next = nextFallbackAfter(data, kickedFrom);
        if (next.isEmpty()) {
            return;
        }
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(next.get()));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        queueManager.dequeue(event.getPlayer().getUniqueId());
    }

    // Returns the highest configured weight for any permission the player holds
    // according to LuckPerms, or 0 if none match. Uses the player's contextual
    // query options so per-context permission grants behave as expected.
    private int resolveWeight(@NotNull Player player) {
        if (permissionWeights.isEmpty()) {
            return 0;
        }
        CachedPermissionData permData = permissionData(player);
        if (permData == null) {
            return 0;
        }
        // permissionWeights is sorted by weight descending at load time, so the
        // first hit is the highest weight the player qualifies for.
        for (PermissionWeight pw : permissionWeights) {
            if (permData.checkPermission(pw.permission()).asBoolean()) {
                return pw.weight();
            }
        }
        return 0;
    }

    // Returns the next fallback in declaration order after the one named
    // `kickedFrom`, skipping managed fallbacks that are full. Returns empty if
    // `kickedFrom` isn't a fallback for this queue or no later option exists.
    private @NotNull Optional<RegisteredServer> nextFallbackAfter(@NotNull ServerQueueData data,
                                                                  @NotNull String kickedFrom) {
        List<RegisteredServer> fallbacks = data.fallbackServers();
        int from = -1;
        for (int i = 0; i < fallbacks.size(); i++) {
            if (fallbacks.get(i).getServerInfo().getName().equals(kickedFrom)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return Optional.empty();
        }
        RegisteredServer firstChoice = null;
        for (int i = from + 1; i < fallbacks.size(); i++) {
            RegisteredServer fallback = fallbacks.get(i);
            Optional<ServerQueueData> managed = queueManager.get(fallback.getServerInfo()
                    .getName());
            if (managed.isPresent()) {
                if (managed.get().hasCapacity()) {
                    return Optional.of(fallback);
                }
                continue;
            }
            if (firstChoice == null) {
                firstChoice = fallback;
            }
        }
        return Optional.ofNullable(firstChoice);
    }

    // Walks the configured fallbacks in declaration order and returns the first
    // one with available capacity. If a fallback is itself queue-managed we use
    // its known capacity; otherwise we accept it as soon as it appears online
    // (the proxy will surface a connect failure if it isn't).
    private @NotNull Optional<RegisteredServer> selectFallback(@NotNull ServerQueueData data) {
        RegisteredServer firstChoice = null;
        for (RegisteredServer fallback : data.fallbackServers()) {
            String fallbackName = fallback.getServerInfo().getName();
            Optional<ServerQueueData> managed = queueManager.get(fallbackName);
            if (managed.isPresent()) {
                if (managed.get().hasCapacity()) {
                    return Optional.of(fallback);
                }
                continue;
            }
            if (firstChoice == null) {
                firstChoice = fallback;
            }
        }
        return Optional.ofNullable(firstChoice);
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
        while (data.hasCapacity() && !data.queue().isEmpty()) {
            Optional<QueueEntry> next = data.queue().dequePlayer();
            if (next.isEmpty()) {
                return;
            }
            QueueEntry entry = next.get();
            UUID uuid = entry.player();
            int weight = entry.weight();
            Optional<Player> playerOpt = server.getPlayer(uuid);
            if (playerOpt.isEmpty()) {
                queueManager.dequeue(uuid);
                continue;
            }
            Player player = playerOpt.get();
            queueManager.dequeue(uuid);
            player.createConnectionRequest(data.targetServer())
                    .connect()
                    .whenComplete((result, err) -> {
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
