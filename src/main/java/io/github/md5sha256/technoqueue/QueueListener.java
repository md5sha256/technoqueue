package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.md5sha256.technoqueue.config.PermissionWeight;
import io.github.md5sha256.technoqueue.config.ServerQueueData;
import io.github.md5sha256.technoqueue.localization.MessageContainer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public class QueueListener {

    private final QueueManager queueManager;
    private final MessageContainer messages;
    private final LuckPerms luckPerms;
    // Sorted by weight descending so resolveWeight can short-circuit on the first match.
    private final List<PermissionWeight> permissionWeights;

    public QueueListener(@NotNull QueueManager queueManager,
                         @NotNull MessageContainer messages,
                         @NotNull LuckPerms luckPerms,
                         @NotNull List<PermissionWeight> permissionWeights) {
        this.queueManager = queueManager;
        this.messages = messages;
        this.luckPerms = luckPerms;
        this.permissionWeights = List.copyOf(permissionWeights);
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
        if (queueManager.canConnectDirectly(data)) {
            return;
        }
        Optional<RegisteredServer> fallback = selectFallback(data);
        if (fallback.isPresent() && queueManager.enqueue(player.getUniqueId(),
                serverName,
                resolveWeight(player)) == EnqueueResult.SUCCESS) {
            event.setInitialServer(fallback.get());
            player.sendMessage(messages.prefixedTemplate("queue.joined",
                    Placeholder.unparsed("server", serverName)));
            queueManager.status(player.getUniqueId())
                    .ifPresent(status -> player.sendMessage(QueueCommand.statusMessage(messages, status)));
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
        // Only let the drain's own promotion connect through. Checking "is this
        // player in the queue for serverName" would let queued players bypass
        // the queue by running /server <target> themselves.
        if (queueManager.isPromoting(player.getUniqueId())) {
            return;
        }
        if (hasBypass(player, data)) {
            return;
        }
        if (queueManager.canConnectDirectly(data)) {
            return;
        }
        boolean alreadyConnected = player.getCurrentServer().isPresent();
        Optional<RegisteredServer> fallback = alreadyConnected
                ? Optional.empty()
                : selectFallback(data);
        boolean canEnqueue = alreadyConnected || fallback.isPresent();
        if (canEnqueue && queueManager.enqueue(player.getUniqueId(),
                serverName,
                resolveWeight(player)) == EnqueueResult.SUCCESS) {
            // Only redirect to a fallback when the player isn't already on a
            // server; otherwise just deny the /server attempt so they stay put.
            if (alreadyConnected) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(fallback.get()));
            }
            player.sendMessage(messages.prefixedTemplate("queue.joined",
                    Placeholder.unparsed("server", serverName)));
            queueManager.status(player.getUniqueId())
                    .ifPresent(status -> player.sendMessage(QueueCommand.statusMessage(messages, status)));
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

    private boolean hasBypass(@NotNull Player player, @NotNull ServerQueueData data) {
        String permission = data.bypassPermission();
        if (permission == null) {
            return false;
        }
        CachedPermissionData permData = permissionData(player);
        return permData != null && permData.checkPermission(permission).asBoolean();
    }

    private @Nullable CachedPermissionData permissionData(@NotNull Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        ContextManager contextManager = luckPerms.getContextManager();
        QueryOptions options = contextManager.getQueryOptions(user)
                .orElseGet(contextManager::getStaticQueryOptions);
        return user.getCachedData().getPermissionData(options);
    }

    // Returns the highest configured weight for any permission the player holds
    // according to LuckPerms, or 0 if none match.
    private int resolveWeight(@NotNull Player player) {
        if (permissionWeights.isEmpty()) {
            return 0;
        }
        CachedPermissionData permData = permissionData(player);
        if (permData == null) {
            return 0;
        }
        for (PermissionWeight pw : permissionWeights) {
            if (permData.checkPermission(pw.permission()).asBoolean()) {
                return pw.weight();
            }
        }
        return 0;
    }

    // Returns the next fallback in declaration order after `kickedFrom`,
    // skipping managed fallbacks that are full. Returns empty if `kickedFrom`
    // isn't a fallback for this queue or no later option exists.
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
                if (queueManager.hasCapacityFor(managed.get())) {
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
                if (queueManager.hasCapacityFor(managed.get())) {
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
}
