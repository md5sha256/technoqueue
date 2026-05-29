package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.md5sha256.technoqueue.config.ServerQueueData;
import io.github.md5sha256.technoqueue.localization.MessageContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
            case "info" -> handleInfo(source, args);
            default -> source.sendMessage(messages.prefixedTemplate("queue.usage"));
        }
    }

    @Override
    public List<String> suggest(@NotNull Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return Stream.of("leave", "status", "info")
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

    private void handleInfo(@NotNull CommandSource source, @NotNull String[] args) {
        if (args.length < 2) {
            source.sendMessage(messages.prefixedTemplate("queue.info-usage"));
            return;
        }
        String serverName = args[1];
        Optional<ServerQueueData> dataOpt = queueManager.get(serverName);
        if (dataOpt.isEmpty()) {
            source.sendMessage(messages.prefixedTemplate("queue.info-unknown-server",
                    Placeholder.unparsed("server", serverName)));
            return;
        }
        int size = dataOpt.get().queue().size();
        source.sendMessage(messages.prefixedTemplate("queue.info",
                Placeholder.unparsed("server", serverName),
                Placeholder.unparsed("size", Integer.toString(size))));
    }

    private void handleStatus(@NotNull CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(messages.prefixedTemplate("queue.status-not-player"));
            return;
        }
        Optional<QueueManager.QueueStatus> status = queueManager.status(player.getUniqueId());
        if (status.isEmpty()) {
            player.sendMessage(messages.prefixedTemplate("queue.status-not-queued"));
            return;
        }
        player.sendMessage(statusMessage(messages, status.get()));
    }

    public static @NotNull Component statusMessage(@NotNull MessageContainer messages,
                                                   @NotNull QueueManager.QueueStatus status) {
        return messages.prefixedTemplate("queue.status",
                Placeholder.unparsed("server", status.serverName()),
                Placeholder.unparsed("position", Integer.toString(status.position())),
                Placeholder.unparsed("size", Integer.toString(status.size())));
    }
}
