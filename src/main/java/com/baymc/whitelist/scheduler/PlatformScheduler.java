package com.baymc.whitelist.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 命令和监听器使用的 Paper/Folia 调度器小型适配层
 *
 * <p>把调度器访问集中在这里, 可以降低在 Folia 数据库线程中误调用 Bukkit API 的风险
 */
public final class PlatformScheduler {
    private final Plugin plugin;

    public PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void runAsync(Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    public void runGlobal(Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }

    public void runForPlayer(Player player, Runnable runnable) {
        player.getScheduler().run(plugin, task -> runnable.run(), null);
    }

    public void runForSender(CommandSender sender, Runnable runnable) {
        if (sender instanceof Player player) {
            runForPlayer(player, runnable);
            return;
        }
        runGlobal(runnable);
    }
}
