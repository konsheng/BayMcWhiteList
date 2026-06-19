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

    /**
     * 保存插件实例, 用于从插件数据目录中解析语言文件
     *
     * @param plugin 当前插件实例
     */
    public LangManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 从 plugins/BayMcWhiteList/lang 重新加载指定语言文件
     *
     * @param languageFileName 配置中指定的语言文件名
     * @throws IllegalArgumentException 当语言文件不存在或无法解析时抛出
     */
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

    /**
     * 发送不带占位符的语言键
     *
     * @param sender 消息接收者
     * @param key 语言文件中的节点路径
     */
    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    /**
     * 发送某个语言键对应的所有行
     *
     * @param sender 消息接收者
     * @param key 语言文件中的节点路径
     * @param placeholders MiniMessage 占位符值
     */
    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        for (Component component : components(key, placeholders)) {
            sender.sendMessage(component);
        }
    }

    /**
     * 解析不带占位符的单个组件
     *
     * @param key 语言文件中的节点路径
     * @return 解析后的第一个组件, 缺失时返回兜底组件
     */
    public Component component(String key) {
        return component(key, Map.of());
    }

    /**
     * 解析某个语言键的第一个组件
     *
     * @param key 语言文件中的节点路径
     * @param placeholders MiniMessage 占位符值
     * @return 解析后的第一个组件, 空列表时返回空组件
     */
    public Component component(String key, Map<String, String> placeholders) {
        List<Component> components = components(key, placeholders);
        if (components.isEmpty()) {
            return Component.empty();
        }
        return components.getFirst();
    }

    /**
     * 将多行语言键合并为一个用换行分隔的组件
     *
     * @param key 语言文件中的节点路径
     * @param placeholders MiniMessage 占位符值
     * @return 合并后的组件
     */
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

    /**
     * 将语言键转为纯文本, 便于嵌入其他消息
     *
     * @param key 语言文件中的节点路径
     * @return 去除 MiniMessage 样式后的纯文本
     */
    public String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(component(key));
    }

    /**
     * 将字符串节点或字符串列表节点解析为 MiniMessage 组件
     *
     * @param key 语言文件中的节点路径
     * @param placeholders MiniMessage 占位符值
     * @return 解析后的组件列表
     */
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

    /**
     * 使用传入占位符解析一行 MiniMessage 文本
     */
    private Component deserialize(String input, Map<String, String> placeholders) {
        return miniMessage.deserialize(input, resolver(placeholders));
    }

    /**
     * 返回语言文件中配置的缺失键提示, 避免硬编码兜底文本
     */
    private Component missingKey(String key) {
        String fallback = language.getString(MISSING_KEY_PATH);
        if (fallback == null) {
            plugin.getLogger().warning("Missing language key: " + key);
            return Component.empty();
        }
        return deserialize(fallback, Map.of("key", key));
    }

    /**
     * 将简单字符串占位符转换为 MiniMessage 标签解析器
     */
    private TagResolver resolver(Map<String, String> placeholders) {
        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(Placeholder.parsed(PREFIX_PATH, prefix == null ? "" : prefix));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolvers.add(Placeholder.unparsed(entry.getKey(), entry.getValue() == null ? "" : entry.getValue()));
        }
        return TagResolver.resolver(resolvers.toArray(TagResolver[]::new));
    }
}
