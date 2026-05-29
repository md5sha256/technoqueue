package io.github.md5sha256.technoqueue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.md5sha256.technoqueue.localization.MessageContainer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public class LeaveQueueCommand implements SimpleCommand {

    private final QueueManager queueManager;
    private final MessageContainer messages;

    public LeaveQueueCommand(@NotNull QueueManager queueManager, @NotNull MessageContainer messages) {
        this.queueManager = queueManager;
        this.messages = messages;
    }

    @Override
    public void execute(@NotNull Invocation invocation) {
        CommandSource source = invocation.source();
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
}
