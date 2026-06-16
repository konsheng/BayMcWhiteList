package com.baymc.whitelist.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 从 Bukkit YAML 配置中读取并校验后的运行期配置
 *
 * <p>命令和监听器读取这份不可变记录, 而不是直接读取原始 YAML 路径
 * 这样可以把配置校验集中在一个地方
 */
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
    private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern SERVER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern MYSQL_HOST_PATTERN = Pattern.compile(
            "(?=.{1,255}$)(?:(?:[A-Za-z0-9](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9])?)"
                    + "(?:\\.(?:[A-Za-z0-9](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9])?))*"
                    + "|\\[[0-9A-Fa-f:.]+])"
    );

    /**
     * 加载并校验所有支持的配置路径
     *
     * @throws IllegalArgumentException 当配置值超出允许范围或格式不合法时抛出
     */
    public static PluginConfig load(FileConfiguration config) {
        // 前缀既会展示给玩家, 也会参与 HMAC 载荷计算
        // 因此所有服务器都必须使用稳定的大写标准值
        String prefix = requirePattern(
                string(config, "code.prefix", "BAYMC").toUpperCase(Locale.ROOT),
                CODE_PREFIX_PATTERN,
                "code.prefix"
        );
        String secret = string(config, "code.secret", "CHANGE_ME_TO_A_LONG_RANDOM_SECRET");
        if (secret.isBlank()) {
            throw new IllegalArgumentException("code.secret cannot be blank");
        }

        // 显式解析 ZoneId, 让错误时区在重载时直接失败
        // 避免悄悄改变邀请码有效期窗口
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

        // 表前缀后续会插入 SQL 标识符中, 因此使用前必须限制为安全字符
        String tablePrefix = requirePattern(
                string(config, "storage.mysql.table-prefix", "baymcwhitelist_"),
                TABLE_PREFIX_PATTERN,
                "storage.mysql.table-prefix"
        );
        int maximumPoolSize = intRange(config, "storage.mysql.pool.maximum-pool-size", 10, 1, 64);
        int minimumIdle = intRange(config, "storage.mysql.pool.minimum-idle", 2, 0, 64);
        if (minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("storage.mysql.pool.minimum-idle cannot be greater than maximum-pool-size");
        }
        MysqlSettings mysql = new MysqlSettings(
                requirePattern(string(config, "storage.mysql.host", "127.0.0.1"), MYSQL_HOST_PATTERN, "storage.mysql.host"),
                intRange(config, "storage.mysql.port", 3306, 1, 65535),
                requirePattern(string(config, "storage.mysql.database", "baymc"), DATABASE_NAME_PATTERN, "storage.mysql.database"),
                string(config, "storage.mysql.username", "root"),
                string(config, "storage.mysql.password", "password"),
                tablePrefix,
                config.getBoolean("storage.mysql.use-ssl", false),
                maximumPoolSize,
                minimumIdle,
                longRange(config, "storage.mysql.pool.connection-timeout", 10000L, 250L, 120000L),
                longRange(config, "storage.mysql.pool.idle-timeout", 600000L, 10000L, 3600000L),
                longRange(config, "storage.mysql.pool.max-lifetime", 1800000L, 30000L, 7200000L)
        );

        ServerSettings server = new ServerSettings(
                requirePattern(string(config, "server.name", "login"), SERVER_NAME_PATTERN, "server.name"),
                ServerMode.from(string(config, "server.mode", "login"))
        );

        LanguageSettings language = new LanguageSettings(
                requirePattern(string(config, "language.file", "zh_CN.yml"), LANGUAGE_FILE_PATTERN, "language.file")
        );

        return new PluginConfig(code, player, mysql, server, language);
    }

    /**
     * 读取去除首尾空白后的字符串, 同时保留传入的默认值
     */
    private static String string(FileConfiguration config, String path, String fallback) {
        String value = config.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    /**
     * 读取整数, 并在数值可能影响运行稳定性时快速失败
     */
    private static int intRange(FileConfiguration config, String path, int fallback, int min, int max) {
        int value = config.getInt(path, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    /**
     * 读取 long 值, 并按 HikariCP 超时配置范围进行校验
     */
    private static long longRange(FileConfiguration config, String path, long fallback, long min, long max) {
        long value = config.getLong(path, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    /**
     * 确保用于标识符或资源路径的值保持安全格式
     */
    private static String requirePattern(String value, Pattern pattern, String path) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(path + " contains unsupported characters");
        }
        return value;
    }

    /**
     * 邀请码签名和校验配置
     */
    public record CodeSettings(
            String prefix,
            String secret,
            int suffixLength,
            int validDays,
            ZoneId zoneId,
            boolean caseSensitive
    ) {
    }

    /**
     * 用于生成标准白名单键的玩家身份策略
     */
    public record PlayerSettings(PlayerIdType idType) {
    }

    /**
     * MySQL 连接和 HikariCP 连接池配置
     */
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

    /**
     * 当前服务器的行为开关: 登录服负责验证, 受保护服务器负责拒绝未过白玩家
     */
    public record ServerSettings(String name, ServerMode mode) {
    }

    /**
     * 从插件数据目录 lang 文件夹中选择的语言文件
     */
    public record LanguageSettings(String file) {
    }

    /**
     * 白名单记录支持的标准玩家标识类型
     */
    public enum PlayerIdType {
        UUID,
        NAME;

        /**
         * 解析配置中的 id-type 值
         */
        public static PlayerIdType from(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "uuid" -> UUID;
                case "name" -> NAME;
                default -> throw new IllegalArgumentException("player.id-type must be uuid or name");
            };
        }
    }

    /**
     * 控制命令可用性和预登录拦截策略的服务器角色
     */
    public enum ServerMode {
        LOGIN,
        PROTECTED;

        /**
         * 解析配置中的服务器模式
         */
        public static ServerMode from(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "login" -> LOGIN;
                case "protected" -> PROTECTED;
                default -> throw new IllegalArgumentException("server.mode must be login or protected");
            };
        }
    }
}
