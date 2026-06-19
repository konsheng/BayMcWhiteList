package com.baymc.whitelist.lang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 针对语言文件加载失败和成功路径的单元测试
 */
class LangManagerTest {
    @TempDir
    private Path tempDir;

    @Test
    void missingLanguageFileFailsFast() {
        Path missingFile = tempDir.resolve("missing.yml");

        assertThrows(IllegalArgumentException.class,
                () -> LangManager.loadLanguageFile(missingFile.toFile(), "missing.yml"));
    }

    @Test
    void malformedLanguageFileFailsFast() throws IOException {
        Path malformedFile = tempDir.resolve("bad.yml");
        Files.writeString(malformedFile, "prefix: [unterminated");

        assertThrows(IllegalArgumentException.class,
                () -> LangManager.loadLanguageFile(malformedFile.toFile(), "bad.yml"));
    }

    @Test
    void validLanguageFileLoads() throws IOException {
        Path languageFile = tempDir.resolve("ok.yml");
        Files.writeString(languageFile, "prefix: \"[BayMC]\"\n");

        YamlConfiguration language = LangManager.loadLanguageFile(languageFile.toFile(), "ok.yml");

        assertEquals("[BayMC]", language.getString("prefix"));
    }
}
