package com.baymc.whitelist.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 针对运行期配置加载和边界校验的单元测试
 */
class PluginConfigTest {
    /**
     * 空配置应使用内置默认值并成功加载
     */
    @Test
    void defaultConfigurationValuesLoad() {
        assertDoesNotThrow(() -> PluginConfig.load(new YamlConfiguration()));
    }

    /**
     * 空配置应使用和内置 config.yml 一致的默认表前缀
     */
    @Test
    void defaultTablePrefixMatchesBundledConfiguration() {
        PluginConfig config = PluginConfig.load(new YamlConfiguration());

        assertEquals("baymcwhitelist_", config.mysql().tablePrefix());
    }

    @Test
    void rejectsSuffixLengthLongerThanHmacBase32Output() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("code.suffix-length", 53);

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(config));
    }

    @Test
    void acceptsMaximumSupportedSuffixLength() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("code.suffix-length", 52);

        assertEquals(52, PluginConfig.load(config).code().suffixLength());
    }

    @Test
    void publicKeyRetrievalIsDisabledByDefault() {
        PluginConfig config = PluginConfig.load(new YamlConfiguration());

        assertEquals(false, config.mysql().allowPublicKeyRetrieval());
    }

    @Test
    void publicKeyRetrievalCanBeEnabledExplicitly() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.allow-public-key-retrieval", true);

        assertEquals(true, PluginConfig.load(config).mysql().allowPublicKeyRetrieval());
    }

    @Test
    void defaultRemoveKickOnlinePlayerIsEnabled() {
        PluginConfig config = PluginConfig.load(new YamlConfiguration());

        assertEquals(true, config.remove().kickOnlinePlayer());
    }

    @Test
    void removeKickOnlinePlayerCanBeDisabled() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("remove.kick-online-player", false);

        assertEquals(false, PluginConfig.load(config).remove().kickOnlinePlayer());
    }

    @Test
    void defaultVerifyRateLimitSettingsLoad() {
        PluginConfig.VerifyRateLimitSettings settings = PluginConfig.load(new YamlConfiguration())
                .security()
                .verifyRateLimit();

        assertEquals(true, settings.enabled());
        assertEquals(true, settings.playerEnabled());
        assertEquals(5, settings.maxFailuresPerPlayer());
        assertEquals(300, settings.playerWindowSeconds());
        assertEquals(true, settings.ipEnabled());
        assertEquals(20, settings.maxFailuresPerIp());
        assertEquals(300, settings.ipWindowSeconds());
        assertEquals(600, settings.lockSeconds());
        assertEquals(true, settings.kickOnLock());
        assertEquals(60, settings.blockedLogIntervalSeconds());
        assertEquals(true, settings.notifyConsole());
        assertEquals(true, settings.notifyAdmins());
        assertEquals("baymcwhitelist.notify", settings.notifyPermission());
        assertEquals(60, settings.notifyIntervalSeconds());
    }

    @Test
    void verifyRateLimitCanBeDisabled() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("security.verify-rate-limit.enabled", false);

        assertEquals(false, PluginConfig.load(config).security().verifyRateLimit().enabled());
    }

    @Test
    void rejectsInvalidVerifyRateLimitValues() {
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityValue("security.verify-rate-limit.max-failures-per-player", 0));
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityValue("security.verify-rate-limit.player-window-seconds", 0));
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityValue("security.verify-rate-limit.max-failures-per-ip", 1001));
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityValue("security.verify-rate-limit.lock-seconds", 86401));
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityValue("security.verify-rate-limit.blocked-log-interval-seconds", 0));
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityValue("security.verify-rate-limit.notify-interval-seconds", 0));
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityText("security.verify-rate-limit.notify-permission", "BayMcWhiteList.Notify"));
        assertThrows(IllegalArgumentException.class, () ->
                loadWithSecurityText("security.verify-rate-limit.notify-permission", "baymcwhitelist.notify!"));
    }

    /**
     * 服务器名不能为空白, 否则审计来源字段没有稳定含义
     */
    @Test
    void rejectsBlankServerName() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("server.name", " ");

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(config));
    }

    /**
     * 服务器名不能超过数据库字段长度
     */
    @Test
    void rejectsServerNameLongerThanDatabaseColumn() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("server.name", "a".repeat(65));

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(config));
    }

    /**
     * 数据库名只能使用安全标识符字符, 防止拼入 JDBC 连接地址时改变连接参数
     */
    @Test
    void rejectsUnsafeDatabaseName() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.database", "baymc?rewrite=true");

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(config));
    }

    /**
     * MySQL 主机名不能包含路径或查询分隔符
     */
    @Test
    void rejectsUnsafeMysqlHost() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.host", "127.0.0.1/path");

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(config));
    }

    /**
     * MySQL 主机名只接受单个主机, 允许普通域名, 容器服务名和括号 IPv6
     */
    @Test
    void acceptsSingleMysqlHostForms() {
        assertDoesNotThrow(() -> loadWithMysqlHost("127.0.0.1"));
        assertDoesNotThrow(() -> loadWithMysqlHost("mysql-1.internal"));
        assertDoesNotThrow(() -> loadWithMysqlHost("mysql_service"));
        assertDoesNotThrow(() -> loadWithMysqlHost("[2001:db8::1]"));
    }

    /**
     * MySQL 主机名不能携带端口, 用户信息, host list 或连接属性分隔符
     */
    @Test
    void rejectsMysqlHostAuthorityAndListSeparators() {
        assertThrows(IllegalArgumentException.class, () -> loadWithMysqlHost("127.0.0.1:3307"));
        assertThrows(IllegalArgumentException.class, () -> loadWithMysqlHost("user@127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> loadWithMysqlHost("db1,db2"));
        assertThrows(IllegalArgumentException.class, () -> loadWithMysqlHost("127.0.0.1;profileSQL=true"));
        assertThrows(IllegalArgumentException.class, () -> loadWithMysqlHost("a.".repeat(128) + "a"));
    }

    /**
     * 最小空闲连接数不能大于最大连接池大小
     */
    @Test
    void rejectsMinimumIdleGreaterThanMaximumPoolSize() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.pool.maximum-pool-size", 2);
        config.set("storage.mysql.pool.minimum-idle", 3);

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(config));
    }

    private static PluginConfig loadWithMysqlHost(String host) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.host", host);
        return PluginConfig.load(config);
    }

    private static PluginConfig loadWithSecurityValue(String path, int value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, value);
        return PluginConfig.load(config);
    }

    private static PluginConfig loadWithSecurityText(String path, String value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, value);
        return PluginConfig.load(config);
    }
}
