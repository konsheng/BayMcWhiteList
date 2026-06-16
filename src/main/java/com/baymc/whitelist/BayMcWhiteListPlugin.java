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

public final class BayMcWhiteListPlugin extends JavaPlugin {
    private static final String DEFAULT_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET";

    private PluginConfig pluginConfig;
    private LangManager langManager;
    private PlatformScheduler platformScheduler;
    private DatabaseManager databaseManager;
    private WhitelistRepository whitelistRepository;
    private InviteCodeService inviteCodeService;

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

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

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

    private synchronized void reloadRuntime() {
        PluginConfig loadedConfig = PluginConfig.load(getConfig());
        ensureBundledLanguage(loadedConfig.language().file());

        LangManager loadedLang = new LangManager(this);
        loadedLang.reload(loadedConfig.language().file());

        DatabaseManager loadedDatabase = new DatabaseManager(loadedConfig.mysql());
        boolean databaseReady = connect(loadedDatabase);

        // Keep the plugin enabled even when MySQL is down: login servers can
        // show a configured "not ready" message, and protected servers fail
        // closed instead of accidentally allowing unknown players to enter.
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
