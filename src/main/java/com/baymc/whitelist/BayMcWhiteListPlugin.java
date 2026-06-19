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

    /**
     * Paper/Folia 通过无参构造器创建插件实例
     */
    public BayMcWhiteListPlugin() {
    }

    /**
     * 初始化配置, 语言文件, 数据库访问, 命令和监听器
     */
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

    /**
     * 插件关闭时释放 HikariCP 连接池
     */
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

    /**
     * 为管理员 reload 命令重载所有由配置驱动的运行期状态
     *
     * @return 只有新的数据库管理器连接成功时才返回 true
     */
    public synchronized boolean reloadBayMcWhiteList() {
        try {
            reloadConfig();
            return reloadRuntime();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "BayMcWhiteList failed to reload.", exception);
            return false;
        }
    }

    /**
     * 返回当前不可变配置快照
     *
     * @return 当前运行期配置快照
     */
    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    /**
     * 返回用于所有玩家可见文本的语言管理器
     *
     * @return 当前运行期语言管理器
     */
    public LangManager lang() {
        return langManager;
    }

    /**
     * 返回兼容 Folia 的调度器适配器
     *
     * @return 当前运行期调度器适配器
     */
    public PlatformScheduler scheduler() {
        return platformScheduler;
    }

    /**
     * 返回绑定当前数据库管理器的仓库对象
     *
     * @return 当前运行期白名单仓库
     */
    public WhitelistRepository repository() {
        return whitelistRepository;
    }

    /**
     * 返回绑定当前邀请码配置的邀请码服务
     *
     * @return 当前运行期邀请码服务
     */
    public InviteCodeService inviteCodeService() {
        return inviteCodeService;
    }

    /**
     * 判断命令和受保护服务器登录检查是否可以安全使用当前数据库后端
     *
     * @return 当前数据库管理器是否已经成功启动
     */
    public boolean isDatabaseReady() {
        return databaseManager != null && databaseManager.isReady();
    }

    /**
     * 捕获一次命令或监听器处理过程需要使用的运行期服务快照
     *
     * <p>异步任务会继续持有这份快照, 避免 reload 期间同一次操作混用新旧配置, 语言或数据库仓库
     *
     * @return 一次操作应持有并最终关闭的运行期快照
     */
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

    /**
     * 根据当前 Bukkit 配置重建所有运行期服务
     */
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

    /**
     * 判断新数据库不可用时是否保留旧运行期
     *
     * @param loadedDatabaseReady 新运行期数据库是否可用
     * @param currentDatabaseReady 当前旧数据库是否仍可用
     * @return 新数据库不可用且旧数据库可用时返回 true
     */
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

    /**
     * 尝试启动当前数据库后端, 连接失败时不直接禁用插件
     */
    private boolean connect(DatabaseManager database) {
        try {
            database.start();
            return true;
        } catch (SQLException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Failed to connect to database or initialize tables.", exception);
            return false;
        }
    }

    /**
     * 在 plugin.yml 注册命令名之后绑定命令执行器
     */
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

    /**
     * 启动 bStats 匿名统计上报
     */
    private void startMetrics() {
        new Metrics(this, BSTATS_PLUGIN_ID);
    }

    /**
     * 首次运行时复制内置语言文件, 同时保留用户已经编辑过的文件
     */
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
     * @param config 已完成校验的配置快照
     * @param lang 与该配置绑定的语言管理器
     * @param scheduler 用于切换异步任务和玩家/全局调度器的适配器
     * @param repository 与当前数据库管理器绑定的仓库
     * @param inviteCodeService 与当前邀请码配置绑定的服务
     * @param mojangProfileService 用于管理员手动添加时校验正版玩家档案
     * @param verifyRateLimiter 与当前安全配置绑定的验证码失败限流器
     * @param databaseLease 保持当前数据库管理器存活的快照引用
     * @param closeCallback 快照释放后执行的运行期清理逻辑
     * @param databaseReady 捕获快照时数据库是否可用
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
        /**
         * {@inheritDoc}
         */
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
