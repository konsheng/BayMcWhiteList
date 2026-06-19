package com.baymc.whitelist.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从 Bukkit YAML 配置中读取并校验后的运行期配置
 *
 * <p>命令和监听器读取这份不可变记录, 而不是直接读取原始 YAML 路径
 * 这样可以把配置校验集中在一个地方
 *
 * @param code 邀请码生成和校验配置
 * @param player 玩家身份来源配置
 * @param storage 当前启用的数据库后端配置
 * @param server 当前服务器角色配置
 * @param language 语言文件配置
 * @param remove 撤销白名单后的本服处理策略
 * @param security 邀请码验证安全策略
 */
public record PluginConfig(
        CodeSettings code,
        PlayerSettings player,
        StorageSettings storage,
        ServerSettings server,
        LanguageSettings language,
        RemoveSettings remove,
        SecuritySettings security
) {
    private static final int MAX_CODE_SUFFIX_LENGTH = 52;
    private static final Pattern CODE_PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,24}");
    private static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final Pattern LANGUAGE_FILE_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+\\.ya?ml");
    private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern SERVER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern PERMISSION_NODE_PATTERN = Pattern.compile("[a-z0-9_.-]{1,128}");
    private static final Pattern SQLITE_FILE_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}\\.(?:db|sqlite|sqlite3)");
    private static final Pattern MYSQL_HOST_PATTERN = Pattern.compile(
            "(?=.{1,255}$)(?:(?:[A-Za-z0-9](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9])?)"
                    + "(?:\\.(?:[A-Za-z0-9](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9])?))*"
                    + "|\\[[0-9A-Fa-f:.]+])"
    );

    /**
     * 加载并校验所有支持的配置路径
     *
     * @param config Bukkit 已加载的 YAML 配置对象
     * @return 完成格式和范围校验后的运行期配置快照
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
                intRange(config, "code.suffix-length", 8, 4, MAX_CODE_SUFFIX_LENGTH),
                intRange(config, "code.valid-days", 7, 1, 365),
                zoneId,
                bool(config, "code.case-sensitive", false)
        );

        PlayerSettings player = new PlayerSettings(UuidSource.from(
                string(config, "player.uuid-source", "mojang")
        ));

        // 只校验当前启用的存储后端, 允许同一份配置中保留另一个后端的占位值
        // 这样开发阶段可以在 MySQL 与 SQLite 之间切换, 而不会被未启用后端的示例配置阻断启动
        StorageType storageType = StorageType.from(string(config, "storage.type", "mysql"));
        StorageSettings storage = new StorageSettings(
                storageType,
                storageType == StorageType.MYSQL ? mysqlSettings(config) : defaultMysqlSettings(),
                storageType == StorageType.SQLITE ? sqliteSettings(config) : defaultSqliteSettings()
        );

        ServerSettings server = new ServerSettings(
                requirePattern(string(config, "server.name", "login"), SERVER_NAME_PATTERN, "server.name"),
                ServerMode.from(string(config, "server.mode", "login"))
        );

        LanguageSettings language = new LanguageSettings(
                requirePattern(string(config, "language.file", "zh_CN.yml"), LANGUAGE_FILE_PATTERN, "language.file")
        );

        RemoveSettings remove = new RemoveSettings(
                bool(config, "remove.kick-online-player", true),
                serverModes(config, "remove.kick-server-modes", Set.of(ServerMode.PROTECTED))
        );

        VerifyRateLimitSettings verifyRateLimit = new VerifyRateLimitSettings(
                bool(config, "security.verify-rate-limit.enabled", true),
                bool(config, "security.verify-rate-limit.player-enabled", true),
                intRange(config, "security.verify-rate-limit.max-failures-per-player", 5, 1, 1000),
                intRange(config, "security.verify-rate-limit.player-window-seconds", 300, 1, 86400),
                bool(config, "security.verify-rate-limit.ip-enabled", true),
                intRange(config, "security.verify-rate-limit.max-failures-per-ip", 20, 1, 1000),
                intRange(config, "security.verify-rate-limit.ip-window-seconds", 300, 1, 86400),
                intRange(config, "security.verify-rate-limit.lock-seconds", 600, 1, 86400),
                bool(config, "security.verify-rate-limit.kick-on-lock", true),
                intRange(config, "security.verify-rate-limit.blocked-log-interval-seconds", 60, 1, 86400),
                bool(config, "security.verify-rate-limit.notify-console", true),
                bool(config, "security.verify-rate-limit.notify-admins", true),
                requirePattern(
                        string(config, "security.verify-rate-limit.notify-permission", "baymcwhitelist.notify"),
                        PERMISSION_NODE_PATTERN,
                        "security.verify-rate-limit.notify-permission"
                ),
                intRange(config, "security.verify-rate-limit.notify-interval-seconds", 60, 1, 86400)
        );
        SecuritySettings security = new SecuritySettings(verifyRateLimit);

        return new PluginConfig(code, player, storage, server, language, remove, security);
    }

    /**
     * 保留 MySQL 配置快捷访问器, 便于现有调用方不关心 storage 包装层
     *
     * @return 当前快照中的 MySQL 配置
     */
    public MysqlSettings mysql() {
        return storage.mysql();
    }

    /**
     * 保留 SQLite 配置快捷访问器, 供数据库管理器和测试直接读取
     *
     * @return 当前快照中的 SQLite 配置
     */
    public SqliteSettings sqlite() {
        return storage.sqlite();
    }

    /**
     * 读取去除首尾空白后的字符串, 同时保留传入的默认值
     */
    private static String string(FileConfiguration config, String path, String fallback) {
        if (!config.isSet(path)) {
            return fallback;
        }
        Object value = config.get(path);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return text.trim();
    }

    /**
     * 读取布尔值, 显式拒绝字符串或其他会被 Bukkit 静默转换的类型
     */
    private static boolean bool(FileConfiguration config, String path, boolean fallback) {
        if (!config.isSet(path)) {
            return fallback;
        }
        Object value = config.get(path);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return bool;
    }

    /**
     * 读取整数, 并在数值可能影响运行稳定性时快速失败
     */
    private static int intRange(FileConfiguration config, String path, int fallback, int min, int max) {
        int value = intValue(config, path, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    /**
     * 读取 long 值, 并按 HikariCP 超时配置范围进行校验
     */
    private static long longRange(FileConfiguration config, String path, long fallback, long min, long max) {
        long value = longValue(config, path, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static int intValue(FileConfiguration config, String path, int fallback) {
        long value = longValue(config, path, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return (int) value;
    }

    private static long longValue(FileConfiguration config, String path, long fallback) {
        if (!config.isSet(path)) {
            return fallback;
        }
        Object value = config.get(path);
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException(path + " must be an integer");
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
     * 读取并校验 MySQL 专属配置; 只有 storage.type=mysql 时会调用
     */
    private static MysqlSettings mysqlSettings(FileConfiguration config) {
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
        return new MysqlSettings(
                requirePattern(string(config, "storage.mysql.host", "127.0.0.1"), MYSQL_HOST_PATTERN, "storage.mysql.host"),
                intRange(config, "storage.mysql.port", 3306, 1, 65535),
                requirePattern(string(config, "storage.mysql.database", "baymc"), DATABASE_NAME_PATTERN, "storage.mysql.database"),
                string(config, "storage.mysql.username", "root"),
                string(config, "storage.mysql.password", "password"),
                tablePrefix,
                bool(config, "storage.mysql.use-ssl", false),
                bool(config, "storage.mysql.allow-public-key-retrieval", false),
                maximumPoolSize,
                minimumIdle,
                longRange(config, "storage.mysql.pool.connection-timeout", 10000L, 250L, 120000L),
                longRange(config, "storage.mysql.pool.idle-timeout", 600000L, 10000L, 3600000L),
                longRange(config, "storage.mysql.pool.max-lifetime", 1800000L, 30000L, 7200000L)
        );
    }

    /**
     * 读取并校验 SQLite 专属配置; 文件名只允许落在插件数据目录下
     */
    private static SqliteSettings sqliteSettings(FileConfiguration config) {
        return new SqliteSettings(requirePattern(
                string(config, "storage.sqlite.file", "whitelist.db"),
                SQLITE_FILE_PATTERN,
                "storage.sqlite.file"
        ));
    }

    private static MysqlSettings defaultMysqlSettings() {
        return new MysqlSettings(
                "127.0.0.1",
                3306,
                "baymc",
                "root",
                "password",
                "baymcwhitelist_",
                false,
                false,
                10,
                2,
                10000L,
                600000L,
                1800000L
        );
    }

    private static SqliteSettings defaultSqliteSettings() {
        return new SqliteSettings("whitelist.db");
    }

    /**
     * 读取服务器模式列表配置, 用于控制只在指定类型服务器上执行某些动作
     *
     * <p>未显式配置时使用传入默认值; 显式配置为空列表时表示所有模式都不启用该动作
     */
    private static Set<ServerMode> serverModes(FileConfiguration config, String path, Set<ServerMode> fallback) {
        if (!config.isSet(path)) {
            return Set.copyOf(fallback);
        }

        EnumSet<ServerMode> modes = EnumSet.noneOf(ServerMode.class);
        for (String raw : stringList(config, path)) {
            try {
                modes.add(ServerMode.from(raw));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(path + " contains unsupported server mode: " + raw, exception);
            }
        }
        return Set.copyOf(modes);
    }

    private static List<String> stringList(FileConfiguration config, String path) {
        if (!config.isList(path)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        List<?> rawValues = config.getList(path);
        if (rawValues == null) {
            return List.of();
        }

        List<String> values = new ArrayList<>(rawValues.size());
        for (Object rawValue : rawValues) {
            if (!(rawValue instanceof String text)) {
                throw new IllegalArgumentException(path + " must contain only strings");
            }
            values.add(text.trim());
        }
        return values;
    }

    /**
     * 邀请码签名和校验配置
     *
     * @param prefix 邀请码前缀, 同时参与 HMAC 载荷计算
     * @param secret HMAC 密钥
     * @param suffixLength Base32 后缀长度
     * @param validDays 邀请码可验证的自然日数量
     * @param zoneId 签发日期和过期时间使用的时区
     * @param caseSensitive 是否区分玩家输入的邀请码大小写
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
     *
     * @param uuidSource 当前 UUID 来源策略
     */
    public record PlayerSettings(UuidSource uuidSource) {
    }

    /**
     * 当前启用的存储后端及各后端配置
     *
     * <p>未启用后端只保存安全默认值, 不代表用户配置已经通过该后端的专属校验
     *
     * @param type 当前启用的存储后端类型
     * @param mysql MySQL 后端配置
     * @param sqlite SQLite 后端配置
     */
    public record StorageSettings(StorageType type, MysqlSettings mysql, SqliteSettings sqlite) {
    }

    /**
     * MySQL 连接和 HikariCP 连接池配置
     *
     * @param host MySQL 主机名或 IP
     * @param port MySQL 端口
     * @param database 数据库名
     * @param username 数据库用户名
     * @param password 数据库密码
     * @param tablePrefix 插件表名前缀
     * @param useSsl 是否在 JDBC 连接中启用 SSL
     * @param allowPublicKeyRetrieval 是否允许 Connector/J 请求 RSA 公钥
     * @param maximumPoolSize HikariCP 最大连接数
     * @param minimumIdle HikariCP 最小空闲连接数
     * @param connectionTimeout 借出连接超时时间, 毫秒
     * @param idleTimeout 空闲连接保留时间, 毫秒
     * @param maxLifetime 单个连接最长生命周期, 毫秒
     */
    public record MysqlSettings(
            String host,
            int port,
            String database,
            String username,
            String password,
            String tablePrefix,
            boolean useSsl,
            boolean allowPublicKeyRetrieval,
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeout,
            long idleTimeout,
            long maxLifetime
    ) {
    }

    /**
     * 当前服务器的行为开关: 登录服负责验证, 受保护服务器负责拒绝未过白玩家
     *
     * @param name 当前服务器名称
     * @param mode 当前服务器角色
     */
    public record ServerSettings(String name, ServerMode mode) {
    }

    /**
     * 从插件数据目录 lang 文件夹中选择的语言文件
     *
     * @param file 语言文件名
     */
    public record LanguageSettings(String file) {
    }

    /**
     * 撤销白名单后的本服处理策略
     *
     * <p>kickOnlinePlayer 是总开关; kickServerModes 决定哪些服务器模式会执行本服在线玩家踢出
     * 默认只在 protected 模式踢出, 登录服保留玩家在线以便重新查看状态或再次验证
     *
     * @param kickOnlinePlayer 是否允许撤销后踢出本服在线玩家
     * @param kickServerModes 允许执行撤销踢出的服务器模式集合
     */
    public record RemoveSettings(boolean kickOnlinePlayer, Set<ServerMode> kickServerModes) {
        /**
         * 判断指定服务器模式下是否应该执行撤销踢出
         *
         * @param mode 当前服务器模式
         * @return 总开关开启且当前模式在允许集合中时返回 true
         */
        public boolean shouldKickIn(ServerMode mode) {
            return kickOnlinePlayer && kickServerModes.contains(mode);
        }
    }

    /**
     * 邀请码验证安全策略
     *
     * @param verifyRateLimit 邀请码验证失败限流配置
     */
    public record SecuritySettings(VerifyRateLimitSettings verifyRateLimit) {
    }

    /**
     * 玩家执行 /whitelist 时的失败计数和临时锁定配置
     *
     * <p>enabled 是整套验证限流的总开关, playerEnabled 和 ipEnabled 分别控制玩家 UUID 与 IP 维度
     * 这样代理端真实 IP 未配置完成时可以单独关闭 IP 维度, 同时保留玩家维度保护
     *
     * @param enabled 是否启用整套验证限流
     * @param playerEnabled 是否启用玩家 UUID 维度
     * @param maxFailuresPerPlayer 玩家维度窗口内最大失败次数
     * @param playerWindowSeconds 玩家维度统计窗口秒数
     * @param ipEnabled 是否启用 IP 维度
     * @param maxFailuresPerIp IP 维度窗口内最大失败次数
     * @param ipWindowSeconds IP 维度统计窗口秒数
     * @param lockSeconds 触发限流后的锁定秒数
     * @param kickOnLock 锁定或限流时是否踢出玩家
     * @param blockedLogIntervalSeconds 锁定期间重复尝试的审计日志最短间隔
     * @param notifyConsole 是否向后台输出安全通知
     * @param notifyAdmins 是否通知在线管理员
     * @param notifyPermission 接收安全通知需要的权限
     * @param notifyIntervalSeconds 同一维度重复通知的最短间隔
     */
    public record VerifyRateLimitSettings(
            boolean enabled,
            boolean playerEnabled,
            int maxFailuresPerPlayer,
            int playerWindowSeconds,
            boolean ipEnabled,
            int maxFailuresPerIp,
            int ipWindowSeconds,
            int lockSeconds,
            boolean kickOnLock,
            int blockedLogIntervalSeconds,
            boolean notifyConsole,
            boolean notifyAdmins,
            String notifyPermission,
            int notifyIntervalSeconds
    ) {
    }

    /**
     * SQLite 本地数据库文件配置
     *
     * <p>文件名会在插件数据目录下解析, 不允许路径分隔符或上级目录跳转
     *
     * @param file SQLite 数据库文件名
     */
    public record SqliteSettings(String file) {
    }

    /**
     * 白名单状态支持的存储后端
     */
    public enum StorageType {
        /** 通过 MySQL 共享白名单状态和审计日志 */
        MYSQL,
        /** 通过插件数据目录中的 SQLite 文件保存白名单状态和审计日志 */
        SQLITE;

        /**
         * 解析配置中的 storage.type 值
         *
         * @param raw 配置中的原始文本
         * @return 匹配到的存储后端类型
         * @throws IllegalArgumentException 当值不是 mysql 或 sqlite 时抛出
         */
        public static StorageType from(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "mysql" -> MYSQL;
                case "sqlite" -> SQLITE;
                default -> throw new IllegalArgumentException("storage.type must be mysql or sqlite");
            };
        }
    }

    /**
     * 白名单记录支持的 UUID 来源
     */
    public enum UuidSource {
        /** 使用正版服或代理转发提供的 Mojang UUID */
        MOJANG,
        /** 按 Bukkit 离线名算法从玩家名计算 UUID */
        OFFLINE_NAME,
        /** 完全信任服务端实际看到的 UUID */
        SERVER;

        /**
         * 解析配置中的 uuid-source 值
         *
         * @param raw 配置中的原始文本
         * @return 匹配到的 UUID 来源策略
         * @throws IllegalArgumentException 当值不在支持范围内时抛出
         */
        public static UuidSource from(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "mojang" -> MOJANG;
                case "offline-name" -> OFFLINE_NAME;
                case "server" -> SERVER;
                default -> throw new IllegalArgumentException("player.uuid-source must be mojang, offline-name or server");
            };
        }
    }

    /**
     * 控制命令可用性和预登录拦截策略的服务器角色
     */
    public enum ServerMode {
        /** 登录/验证服, 允许玩家提交邀请码 */
        LOGIN,
        /** 受保护服务器, 预登录阶段会拒绝未过白玩家 */
        PROTECTED;

        /**
         * 解析配置中的服务器模式
         *
         * @param raw 配置中的原始文本
         * @return 匹配到的服务器模式
         * @throws IllegalArgumentException 当值不是 login 或 protected 时抛出
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
