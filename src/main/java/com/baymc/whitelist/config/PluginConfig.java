package com.baymc.whitelist.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

public record PluginConfig(
        CodeSettings code,
        PlayerSettings player,
        MysqlSettings mysql,
        ServerSettings server,
        LanguageSettings language
) {
    private static final Pattern CODE_PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,24}");
    private static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final Pattern LANGUAGE_FILE_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+\\.ya?ml");

    public static PluginConfig load(FileConfiguration config) {
        String prefix = requirePattern(
                string(config, "code.prefix", "BAYMC").toUpperCase(Locale.ROOT),
                CODE_PREFIX_PATTERN,
                "code.prefix"
        );
        String secret = string(config, "code.secret", "CHANGE_ME_TO_A_LONG_RANDOM_SECRET");
        if (secret.isBlank()) {
            throw new IllegalArgumentException("code.secret cannot be blank");
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(string(config, "code.timezone", "Asia/Shanghai"));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("code.timezone is invalid", exception);
        }

        CodeSettings code = new CodeSettings(
                prefix,
                secret,
                intRange(config, "code.suffix-length", 8, 4, 64),
                intRange(config, "code.valid-days", 7, 1, 365),
                zoneId,
                config.getBoolean("code.case-sensitive", false)
        );

        PlayerSettings player = new PlayerSettings(PlayerIdType.from(
                string(config, "player.id-type", "uuid")
        ));

        String tablePrefix = requirePattern(
                string(config, "storage.mysql.table-prefix", "baymc_"),
                TABLE_PREFIX_PATTERN,
                "storage.mysql.table-prefix"
        );
        MysqlSettings mysql = new MysqlSettings(
                string(config, "storage.mysql.host", "127.0.0.1"),
                intRange(config, "storage.mysql.port", 3306, 1, 65535),
                string(config, "storage.mysql.database", "baymc"),
                string(config, "storage.mysql.username", "root"),
                string(config, "storage.mysql.password", "password"),
                tablePrefix,
                config.getBoolean("storage.mysql.use-ssl", false),
                intRange(config, "storage.mysql.pool.maximum-pool-size", 10, 1, 64),
                intRange(config, "storage.mysql.pool.minimum-idle", 2, 0, 64),
                longRange(config, "storage.mysql.pool.connection-timeout", 10000L, 250L, 120000L),
                longRange(config, "storage.mysql.pool.idle-timeout", 600000L, 10000L, 3600000L),
                longRange(config, "storage.mysql.pool.max-lifetime", 1800000L, 30000L, 7200000L)
        );

        ServerSettings server = new ServerSettings(
                string(config, "server.name", "login"),
                ServerMode.from(string(config, "server.mode", "login"))
        );

        LanguageSettings language = new LanguageSettings(
                requirePattern(string(config, "language.file", "zh_CN.yml"), LANGUAGE_FILE_PATTERN, "language.file")
        );

        return new PluginConfig(code, player, mysql, server, language);
    }

    private static String string(FileConfiguration config, String path, String fallback) {
        String value = config.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    private static int intRange(FileConfiguration config, String path, int fallback, int min, int max) {
        int value = config.getInt(path, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static long longRange(FileConfiguration config, String path, long fallback, long min, long max) {
        long value = config.getLong(path, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static String requirePattern(String value, Pattern pattern, String path) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(path + " contains unsupported characters");
        }
        return value;
    }

    public record CodeSettings(
            String prefix,
            String secret,
            int suffixLength,
            int validDays,
            ZoneId zoneId,
            boolean caseSensitive
    ) {
    }

    public record PlayerSettings(PlayerIdType idType) {
    }

    public record MysqlSettings(
            String host,
            int port,
            String database,
            String username,
            String password,
            String tablePrefix,
            boolean useSsl,
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeout,
            long idleTimeout,
            long maxLifetime
    ) {
    }

    public record ServerSettings(String name, ServerMode mode) {
    }

    public record LanguageSettings(String file) {
    }

    public enum PlayerIdType {
        UUID,
        NAME;

        public static PlayerIdType from(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "uuid" -> UUID;
                case "name" -> NAME;
                default -> throw new IllegalArgumentException("player.id-type must be uuid or name");
            };
        }
    }

    public enum ServerMode {
        LOGIN,
        PROTECTED;

        public static ServerMode from(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "login" -> LOGIN;
                case "protected" -> PROTECTED;
                default -> throw new IllegalArgumentException("server.mode must be login or protected");
            };
        }
    }
}
