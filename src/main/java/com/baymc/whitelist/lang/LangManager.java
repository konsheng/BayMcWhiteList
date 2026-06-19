package com.baymc.whitelist.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 lang/*.yml 加载 MiniMessage 文本, 并发送给 Bukkit 命令来源
 */
public final class LangManager {
    private static final String PREFIX_PATH = "prefix";
    private static final String MISSING_KEY_PATH = "common.missing-language-key";

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration language;
    private String prefix = "";

    public LangManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(String languageFileName) {
        File file = new File(plugin.getDataFolder(), "lang/" + languageFileName);
        language = loadLanguageFile(file, languageFileName);
        prefix = language.getString(PREFIX_PATH, "");
    }

    static YamlConfiguration loadLanguageFile(File file, String languageFileName) {
        if (!file.isFile()) {
            throw new IllegalArgumentException("language.file does not exist: " + languageFileName);
        }
        YamlConfiguration loadedLanguage = new YamlConfiguration();
        try {
            loadedLanguage.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalArgumentException("language.file cannot be loaded: " + languageFileName, exception);
        }
        return loadedLanguage;
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        for (Component component : components(key, placeholders)) {
            sender.sendMessage(component);
        }
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> placeholders) {
        List<Component> components = components(key, placeholders);
        if (components.isEmpty()) {
            return Component.empty();
        }
        return components.getFirst();
    }

    public Component joined(String key, Map<String, String> placeholders) {
        List<Component> components = components(key, placeholders);
        Component joined = Component.empty();
        for (int index = 0; index < components.size(); index++) {
            if (index > 0) {
                joined = joined.append(Component.newline());
            }
            joined = joined.append(components.get(index));
        }
        return joined;
    }

    public String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(component(key));
    }

    public List<Component> components(String key, Map<String, String> placeholders) {
        if (language == null) {
            return List.of(Component.empty());
        }
        if (language.isList(key)) {
            List<Component> components = new ArrayList<>();
            for (String line : language.getStringList(key)) {
                components.add(deserialize(line, placeholders));
            }
            return components;
        }
        String line = language.getString(key);
        if (line == null) {
            return List.of(missingKey(key));
        }
        return List.of(deserialize(line, placeholders));
    }

    private Component deserialize(String input, Map<String, String> placeholders) {
        return miniMessage.deserialize(input, resolver(placeholders));
    }

    private Component missingKey(String key) {
        String fallback = language.getString(MISSING_KEY_PATH);
        if (fallback == null) {
            plugin.getLogger().warning("Missing language key: " + key);
            return Component.empty();
        }
        return deserialize(fallback, Map.of("key", key));
    }

    private TagResolver resolver(Map<String, String> placeholders) {
        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(Placeholder.parsed(PREFIX_PATH, prefix == null ? "" : prefix));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolvers.add(Placeholder.unparsed(entry.getKey(), entry.getValue() == null ? "" : entry.getValue()));
        }
        return TagResolver.resolver(resolvers.toArray(TagResolver[]::new));
    }
}
