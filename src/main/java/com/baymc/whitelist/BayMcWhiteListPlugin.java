package com.baymc.whitelist;

import com.baymc.whitelist.code.InviteCodeService;
import com.baymc.whitelist.command.BayMcWhiteListCommand;
import com.baymc.whitelist.command.WhitelistCommand;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.lang.LangManager;
import com.baymc.whitelist.listener.WhitelistLoginListener;
import com.baymc.whitelist.mojang.MojangProfileService;
import com.baymc.whitelist.scheduler.PlatformScheduler;
import com.baymc.whitelist.security.VerifyRateLimiter;
import com.baymc.whitelist.storage.DatabaseManager;
import com.baymc.whitelist.storage.WhitelistRepository;
import org.bstats.bukkit.Metrics;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * plugin.yml 中注册的插件主入口
 *
 * <p>该类负责持有运行期单例, 并协调重载顺序, 确保命令, 监听器, 语言文件和数据库访问
 * 都使用同一份已校验的配置快照
 */
public final class BayMcWhiteListPlugin extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 32035;
    private static final String DEFAULT_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET";

    private PluginConfig pluginConfig;
    private LangManager langManager;
    private PlatformScheduler platformScheduler;
    private DatabaseManager databaseManager;
    private WhitelistRepository whitelistRepository;
    private InviteCodeService inviteCodeService;
    private VerifyRateLimiter verifyRateLimiter;
    private final MojangProfileService mojangProfileService = new MojangProfileService();
    private final List<DatabaseManager> retiredDatabases = new ArrayList<>();

    public BayMcWhiteListPlugin() {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        platformScheduler = new PlatformScheduler(this);

        try {
            reloadRuntime();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "BayMcWhiteList failed to load its configuration.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerCommands();
        getServer().getPluginManager().registerEvents(new WhitelistLoginListener(this), this);
        startMetrics();
    }

    @Override
    public synchronized void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
            databaseManager = null;
        }
        for (DatabaseManager retiredDatabase : retiredDatabases) {
            retiredDatabase.close();
        }
        retiredDatabases.clear();
    }

    public synchronized boolean reloadBayMcWhiteList() {
        try {
            reloadConfig();
            return reloadRuntime();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "BayMcWhiteList failed to reload.", exception);
            return false;
        }
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public LangManager lang() {
        return langManager;
    }

    public PlatformScheduler scheduler() {
        return platformScheduler;
    }

    public WhitelistRepository repository() {
        return whitelistRepository;
    }

    public InviteCodeService inviteCodeService() {
        return inviteCodeService;
    }

    public boolean isDatabaseReady() {
        return databaseManager != null && databaseManager.isReady();
    }

    public synchronized RuntimeState runtimeState() {
        DatabaseManager currentDatabase = databaseManager;
        DatabaseManager.Lease databaseLease = currentDatabase == null ? null : currentDatabase.lease();
        return new RuntimeState(
                pluginConfig,
                langManager,
                platformScheduler,
                whitelistRepository,
                inviteCodeService,
                mojangProfileService,
                verifyRateLimiter,
                databaseLease,
                this::pruneRetiredDatabases,
                isDatabaseReady()
        );
    }

    private synchronized boolean reloadRuntime() {
        PluginConfig loadedConfig = PluginConfig.load(getConfig());
        ensureBundledLanguage(loadedConfig.language().file());

        LangManager loadedLang = new LangManager(this);
        loadedLang.reload(loadedConfig.language().file());

        DatabaseManager loadedDatabase = new DatabaseManager(loadedConfig.storage(), getDataFolder().toPath());
        boolean databaseReady = connect(loadedDatabase);
        DatabaseManager oldDatabase = databaseManager;

        if (shouldPreserveCurrentRuntime(databaseReady, oldDatabase != null && oldDatabase.isReady())) {
            loadedDatabase.close();
            getLogger().warning("Reload failed to connect to the new database. Keeping the previous runtime active.");
            return false;
        }

        // 即使数据库不可用也保持插件启用: 登录服可以显示配置好的"未就绪"提示
        // 受保护服务器则会按失败关闭策略拒绝进入, 避免误放未知玩家
        pluginConfig = loadedConfig;
        langManager = loadedLang;
        databaseManager = loadedDatabase;
        whitelistRepository = new WhitelistRepository(loadedDatabase, loadedConfig.server().name());
        inviteCodeService = new InviteCodeService(loadedConfig.code());
        verifyRateLimiter = new VerifyRateLimiter(loadedConfig.security().verifyRateLimit());

        if (oldDatabase != null && oldDatabase != loadedDatabase) {
            retireDatabase(oldDatabase);
        }

        if (DEFAULT_SECRET.equals(loadedConfig.code().secret())) {
            getLogger().warning("The default invite-code secret is still configured. Change code.secret before production use.");
        }
        if (!databaseReady) {
            getLogger().warning("Database is not ready. Login servers cannot verify codes and protected servers will reject joins.");
        }
        return databaseReady;
    }

    static boolean shouldPreserveCurrentRuntime(boolean loadedDatabaseReady, boolean currentDatabaseReady) {
        return !loadedDatabaseReady && currentDatabaseReady;
    }

    private void retireDatabase(DatabaseManager database) {
        if (database.retire()) {
            retiredDatabases.add(database);
        }
        pruneRetiredDatabases();
    }

    private synchronized void pruneRetiredDatabases() {
        retiredDatabases.removeIf(DatabaseManager::isClosed);
    }

    private boolean connect(DatabaseManager database) {
        try {
            database.start();
            return true;
        } catch (SQLException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Failed to connect to database or initialize tables.", exception);
            return false;
        }
    }

    private void registerCommands() {
        BayMcWhiteListCommand adminCommand = new BayMcWhiteListCommand(this);
        WhitelistCommand whitelistCommand = new WhitelistCommand(this);

        PluginCommand bayCommand = Objects.requireNonNull(getCommand("baymcwhitelist"), "baymcwhitelist command missing");
        bayCommand.setExecutor(adminCommand);
        bayCommand.setTabCompleter(adminCommand);

        PluginCommand whitelist = Objects.requireNonNull(getCommand("whitelist"), "whitelist command missing");
        whitelist.setExecutor(whitelistCommand);
        whitelist.setTabCompleter(whitelistCommand);
    }

    private void startMetrics() {
        new Metrics(this, BSTATS_PLUGIN_ID);
    }

    private void ensureBundledLanguage(String fileName) {
        String resourcePath = "lang/" + fileName;
        if (getDataFolder().toPath().resolve(resourcePath).toFile().exists()) {
            return;
        }
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("language.file does not exist and is not bundled: " + fileName);
            }
            saveResource(resourcePath, false);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect bundled language resource", exception);
        }
    }

    /**
     * 一次操作使用的运行期服务快照
     *
     */
    public record RuntimeState(
            PluginConfig config,
            LangManager lang,
            PlatformScheduler scheduler,
            WhitelistRepository repository,
            InviteCodeService inviteCodeService,
            MojangProfileService mojangProfileService,
            VerifyRateLimiter verifyRateLimiter,
            DatabaseManager.Lease databaseLease,
            Runnable closeCallback,
            boolean databaseReady
    ) implements AutoCloseable {

        @Override
        public void close() {
            try {
                if (databaseLease != null) {
                    databaseLease.close();
                }
            } finally {
                closeCallback.run();
            }
        }
    }
}
