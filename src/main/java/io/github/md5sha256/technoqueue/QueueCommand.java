package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.md5sha256.technoqueue.config.ServerQueueData;
import io.github.md5sha256.technoqueue.localization.MessageContainer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class QueueCommand implements SimpleCommand {

    private final QueueManager queueManager;
    private final MessageContainer messages;

    public QueueCommand(@NotNull QueueManager queueManager, @NotNull MessageContainer messages) {
        this.queueManager = queueManager;
        this.messages = messages;
    }

    @Override
    public void execute(@NotNull Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            source.sendMessage(messages.prefixedTemplate("queue.usage"));
            return;
        }
        switch (args[0].toLowerCase()) {
            case "leave" -> handleLeave(source);
            case "status" -> handleStatus(source);
            default -> source.sendMessage(messages.prefixedTemplate("queue.usage"));
        }
    }

    @Override
    public List<String> suggest(@NotNull Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return List.of("leave", "status").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private void handleLeave(@NotNull CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(messages.prefixedTemplate("queue.leave-not-player"));
            return;
        }
        Optional<String> queued = queueManager.queuedServer(player.getUniqueId());
        if (queued.isEmpty()) {
            player.sendMessage(messages.prefixedTemplate("queue.leave-not-queued"));
            return;
        }
        queueManager.dequeue(player.getUniqueId());
        player.sendMessage(messages.prefixedTemplate("queue.leave-success",
                Placeholder.unparsed("server", queued.get())));
    }

    private void handleStatus(@NotNull CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(messages.prefixedTemplate("queue.status-not-player"));
            return;
        }
        Optional<String> queued = queueManager.queuedServer(player.getUniqueId());
        if (queued.isEmpty()) {
            player.sendMessage(messages.prefixedTemplate("queue.status-not-queued"));
            return;
        }
        String serverName = queued.get();
        Optional<ServerQueueData> dataOpt = queueManager.get(serverName);
        if (dataOpt.isEmpty()) {
            player.sendMessage(messages.prefixedTemplate("queue.status-not-queued"));
            return;
        }
        ServerQueueData data = dataOpt.get();
        QueueEntry[] entries = data.queue().queuePositions();
        UUID uuid = player.getUniqueId();
        int position = -1;
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].player().equals(uuid)) {
                position = i + 1;
                break;
            }
        }
        if (position < 0) {
            player.sendMessage(messages.prefixedTemplate("queue.status-not-queued"));
            return;
        }
        player.sendMessage(messages.prefixedTemplate("queue.status",
                Placeholder.unparsed("server", serverName),
                Placeholder.unparsed("position", Integer.toString(position)),
                Placeholder.unparsed("size", Integer.toString(entries.length))));
    }
}
