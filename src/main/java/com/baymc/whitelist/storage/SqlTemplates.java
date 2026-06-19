package com.baymc.whitelist.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 jar 资源中读取按名称分段的 SQL 模板
 */
final class SqlTemplates {
    private static final Pattern NAME_PATTERN = Pattern.compile("^\\s*--\\s*name:\\s*([A-Za-z0-9_.-]+)\\s*$");
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");

    private final Map<String, String> templates;

    private SqlTemplates(Map<String, String> templates) {
        this.templates = templates;
    }

    static SqlTemplates load(String resourcePath) {
        try (InputStream inputStream = SqlTemplates.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing SQL resource: " + resourcePath);
            }
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return new SqlTemplates(parse(resourcePath, content));
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read SQL resource: " + resourcePath, exception);
        }
    }

    String render(String name, Map<String, String> placeholders) {
        String template = templates.get(name);
        if (template == null) {
            throw new IllegalArgumentException("Missing SQL template: " + name);
        }

        String sql = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace("${" + entry.getKey() + "}", entry.getValue());
        }

        Matcher matcher = UNRESOLVED_PLACEHOLDER.matcher(sql);
        if (matcher.find()) {
            throw new IllegalArgumentException("Unresolved SQL placeholder " + matcher.group() + " in template: " + name);
        }
        return sql;
    }

    private static Map<String, String> parse(String resourcePath, String content) {
        Map<String, String> parsed = new LinkedHashMap<>();
        String currentName = null;
        StringBuilder currentSql = new StringBuilder();

        for (String line : content.split("\\R")) {
            Matcher matcher = NAME_PATTERN.matcher(line);
            if (matcher.matches()) {
                store(resourcePath, parsed, currentName, currentSql);
                currentName = matcher.group(1);
                currentSql.setLength(0);
                continue;
            }
            if (currentName != null) {
                currentSql.append(line).append('\n');
            }
        }
        store(resourcePath, parsed, currentName, currentSql);

        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("SQL resource contains no named templates: " + resourcePath);
        }
        return Map.copyOf(parsed);
    }

    private static void store(String resourcePath, Map<String, String> parsed, String name, StringBuilder sql) {
        if (name == null) {
            return;
        }
        String template = sql.toString().trim();
        if (template.isEmpty()) {
            throw new IllegalArgumentException("SQL template is empty: " + name + " in " + resourcePath);
        }
        if (parsed.put(name, template) != null) {
            throw new IllegalArgumentException("Duplicate SQL template name: " + name + " in " + resourcePath);
        }
    }
}
