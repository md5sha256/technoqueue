package io.github.md5sha256.technoqueue.localization;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Loads messages.yml into MessageDefinitions. Configurate node handling stays here only.
public final class MessagesYamlLoader {

    private MessagesYamlLoader() {
    }

    public static @NotNull MessageDefinitions load(@NotNull Path yamlFile) throws IOException {
        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(yamlFile)
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();
            ConfigurationNode root = loader.load();
            Map<String, Component> components = new HashMap<>();
            Map<String, String> raw = new HashMap<>();
            loadInto("", root, components, raw);
            return new MessageDefinitions(Map.copyOf(components), Map.copyOf(raw));
        } catch (ConfigurateException e) {
            throw new IOException("Failed to load messages from " + yamlFile, e);
        }
    }

    private static void loadInto(@NotNull String path,
                                 @NotNull ConfigurationNode root,
                                 @NotNull Map<String, Component> components,
                                 @NotNull Map<String, String> raw) throws ConfigurateException {
        if (!root.empty()) {
            if (root.isList()) {
                List<String> strings = root.getList(String.class, Collections.emptyList());
                TextComponent.Builder builder = Component.text();
                Iterator<String> iterator = strings.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next().trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    builder.append(MiniMessage.miniMessage().deserialize(line));
                    if (iterator.hasNext()) {
                        builder.appendNewline();
                    }
                }
                components.put(path, builder.build());
            } else {
                String str = root.getString();
                if (str != null) {
                    String trimmed = str.trim();
                    raw.put(path, trimmed);
                    components.put(path, MiniMessage.miniMessage().deserialize(trimmed));
                }
            }
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            String key = entry.getKey().toString();
            ConfigurationNode child = entry.getValue();
            String newPath = path.isEmpty() ? key : path + "." + key;
            loadInto(newPath, child, components, raw);
        }
    }
}
