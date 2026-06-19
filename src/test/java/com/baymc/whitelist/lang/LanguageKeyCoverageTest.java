package com.baymc.whitelist.lang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确保代码中引用的语言键都存在于内置中文语言文件中
 */
class LanguageKeyCoverageTest {
    private static final Pattern LANGUAGE_KEY = Pattern.compile(
            "\"((?:common|usage|code|admin|database|kick|security|state|join)\\.[a-z0-9_.-]+"
                    + "|player\\.status-[a-z0-9_.-]+)\""
    );

    @Test
    void bundledChineseLanguageContainsAllReferencedKeys() throws Exception {
        YamlConfiguration language = new YamlConfiguration();
        language.load(Path.of("src/main/resources/lang/zh_CN.yml").toFile());

        Set<String> referencedKeys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains(".lang()")) {
                    continue;
                }
                Matcher matcher = LANGUAGE_KEY.matcher(source);
                while (matcher.find()) {
                    referencedKeys.add(matcher.group(1));
                }
            }
        }

        Set<String> missing = new TreeSet<>();
        for (String key : referencedKeys) {
            if (!language.contains(key)) {
                missing.add(key);
            }
        }

        assertTrue(missing.isEmpty(), "Missing language keys: " + missing);
    }
}
