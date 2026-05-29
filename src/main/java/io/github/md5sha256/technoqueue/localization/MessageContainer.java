package io.github.md5sha256.technoqueue.localization;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageContainer {

    private final Map<String, Component> messages = new ConcurrentHashMap<>();
    // Raw MiniMessage source for scalar keys (for runtime MiniMessage.deserialize with TagResolvers).
    private final Map<String, String> rawMiniMessages = new ConcurrentHashMap<>();

    public @NotNull String plaintextMessageFor(@NotNull String key) {
        return PlainTextComponentSerializer.plainText().serialize(messageFor(key));
    }

    public @NotNull String prefixedPlaintextMessageFor(@NotNull String key) {
        return plaintextMessageFor("prefix") + " " + plaintextMessageFor(key);
    }

    public @NotNull Component prefix() {
        return this.messages.getOrDefault("prefix", Component.empty());
    }

    public @NotNull Component messageFor(@NotNull String key) {
        return this.messages.getOrDefault(key, Component.text(key));
    }

    public @NotNull Component prefixedMessageFor(@NotNull String key) {
        return prefix().appendSpace().append(messageFor(key));
    }

    public @NotNull Component prefixedTemplate(@NotNull String key, @NotNull TagResolver... resolvers) {
        return prefix().appendSpace().append(template(key, resolvers));
    }

    public @NotNull String miniMessageFormattedFor(@NotNull String key) {
        return MiniMessage.miniMessage().serialize(messageFor(key));
    }

    public void setMessage(@NotNull String key, @NotNull Component message) {
        this.messages.put(key, message);
    }

    public void clear() {
        this.messages.clear();
        this.rawMiniMessages.clear();
    }

    // Returns null if this key was not a scalar MiniMessage string (e.g. list-only node).
    public @Nullable String rawMiniMessageFor(@NotNull String key) {
        return this.rawMiniMessages.get(key);
    }

    // Deserialize raw MiniMessage for key with the supplied TagResolvers. Prefer
    // over messageFor when the string contains dynamic placeholders.
    public @NotNull Component template(@NotNull String key, @NotNull TagResolver... resolvers) {
        String raw = rawMiniMessageFor(key);
        if (raw == null || raw.isEmpty()) {
            return Component.text(key);
        }
        if (resolvers.length == 0) {
            return MiniMessage.miniMessage().deserialize(raw);
        }
        return MiniMessage.miniMessage().deserialize(raw, TagResolver.resolver(resolvers));
    }

    public void load(@NotNull MessageDefinitions definitions) {
        this.messages.clear();
        this.rawMiniMessages.clear();
        this.messages.putAll(definitions.resolvedComponents());
        this.rawMiniMessages.putAll(definitions.rawMiniMessageSources());
    }
}
