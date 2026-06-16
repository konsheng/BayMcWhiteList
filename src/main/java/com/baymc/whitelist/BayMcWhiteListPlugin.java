package com.baymc.whitelist;

import com.baymc.whitelist.code.InviteCodeService;
import com.baymc.whitelist.command.BayMcWhiteListCommand;
import com.baymc.whitelist.command.WhitelistCommand;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.lang.LangManager;
import com.baymc.whitelist.listener.WhitelistLoginListener;
import com.baymc.whitelist.scheduler.PlatformScheduler;
import com.baymc.whitelist.storage.DatabaseManager;
import com.baymc.whitelist.storage.WhitelistRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Objects;

/**
 * plugin.yml 中注册的插件主入口
 *
 * <p>该类负责持有运行期单例, 并协调重载顺序, 确保命令, 监听器, 语言文件和数据库访问
 * 都使用同一份已校验的配置快照
 */
public final class BayMcWhiteListPlugin extends JavaPlugin {
    private static final String DEFAULT_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET";

    private PluginConfig pluginConfig;
    private LangManager langManager;
    private PlatformScheduler platformScheduler;
    private DatabaseManager databaseManager;
    private WhitelistRepository whitelistRepository;
    private InviteCodeService inviteCodeService;

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
            getLogger().severe("BayMcWhiteList failed to load its configuration.");
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerCommands();
        getServer().getPluginManager().registerEvents(new WhitelistLoginListener(this), this);
    }

    /**
     * 插件关闭时释放 HikariCP 连接池
     */
    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    /**
     * 为管理员 reload 命令重载所有由配置驱动的运行期状态
     *
     * @return 只有新的数据库管理器连接成功时才返回 true
     */
    public synchronized boolean reloadBayMcWhiteList() {
        reloadConfig();
        try {
            reloadRuntime();
            return isDatabaseReady();
        } catch (RuntimeException exception) {
            getLogger().severe("BayMcWhiteList failed to reload.");
            exception.printStackTrace();
            return false;
        }
    }

    /**
     * 返回当前不可变配置快照
     */
    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    /**
     * 返回用于所有玩家可见文本的语言管理器
     */
    public LangManager lang() {
        return langManager;
    }

    /**
     * 返回兼容 Folia 的调度器适配器
     */
    public PlatformScheduler scheduler() {
        return platformScheduler;
    }

    /**
     * 返回绑定当前数据库管理器的仓库对象
     */
    public WhitelistRepository repository() {
        return whitelistRepository;
    }

    /**
     * 返回绑定当前邀请码配置的邀请码服务
     */
    public InviteCodeService inviteCodeService() {
        return inviteCodeService;
    }

    /**
     * 判断命令和受保护服务器登录检查是否可以安全使用 MySQL
     */
    public boolean isDatabaseReady() {
        return databaseManager != null && databaseManager.isReady();
    }

    /**
     * 根据当前 Bukkit 配置重建所有运行期服务
     */
    private synchronized void reloadRuntime() {
        PluginConfig loadedConfig = PluginConfig.load(getConfig());
        ensureBundledLanguage(loadedConfig.language().file());

        LangManager loadedLang = new LangManager(this);
        loadedLang.reload(loadedConfig.language().file());

        DatabaseManager loadedDatabase = new DatabaseManager(loadedConfig.mysql());
        boolean databaseReady = connect(loadedDatabase);

        // 即使 MySQL 不可用也保持插件启用: 登录服可以显示配置好的"未就绪"提示
        // 受保护服务器则会按失败关闭策略拒绝进入, 避免误放未知玩家
        DatabaseManager oldDatabase = databaseManager;
        pluginConfig = loadedConfig;
        langManager = loadedLang;
        databaseManager = loadedDatabase;
        whitelistRepository = new WhitelistRepository(loadedDatabase, loadedConfig.server().name());
        inviteCodeService = new InviteCodeService(loadedConfig.code());

        if (oldDatabase != null && oldDatabase != loadedDatabase) {
            oldDatabase.close();
        }

        if (DEFAULT_SECRET.equals(loadedConfig.code().secret())) {
            getLogger().warning("The default invite-code secret is still configured. Change code.secret before production use.");
        }
        if (!databaseReady) {
            getLogger().warning("Database is not ready. Login servers cannot verify codes and protected servers will reject joins.");
        }
    }

    /**
     * 尝试启动 MySQL, 连接失败时不直接禁用插件
     */
    private boolean connect(DatabaseManager database) {
        try {
            database.start();
            return true;
        } catch (SQLException | RuntimeException exception) {
            getLogger().severe("Failed to connect to MySQL or initialize tables.");
            exception.printStackTrace();
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
     * 首次运行时复制内置语言文件, 同时保留用户已经编辑过的文件
     */
    private void ensureBundledLanguage(String fileName) {
        String resourcePath = "lang/" + fileName;
        if (getDataFolder().toPath().resolve(resourcePath).toFile().exists()) {
            return;
        }
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream != null) {
                saveResource(resourcePath, false);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect bundled language resource", exception);
        }
    }
}
