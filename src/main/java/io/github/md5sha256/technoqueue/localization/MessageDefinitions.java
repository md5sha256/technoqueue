package io.github.md5sha256.technoqueue.localization;

import net.kyori.adventure.text.Component;

import java.util.Map;

// Flattened message data loaded from messages.yml (dotted keys -> values).
public record MessageDefinitions(Map<String, Component> resolvedComponents,
                                 Map<String, String> rawMiniMessageSources) {
}
