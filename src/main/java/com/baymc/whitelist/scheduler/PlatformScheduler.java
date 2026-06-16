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

    /**
     * 保存 Paper 调度器 API 需要的插件所有者
     */
    public PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 在 Paper 异步调度器上运行阻塞任务或数据库任务
     */
    public void runAsync(Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    /**
     * 在全局区域调度器上运行非玩家绑定任务
     */
    public void runGlobal(Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }

    /**
     * 在玩家实体调度器上运行面向玩家的任务
     */
    public void runForPlayer(Player player, Runnable runnable) {
        player.getScheduler().run(plugin, task -> runnable.run(), null);
    }

    /**
     * 根据发送者类型, 在正确的调度器上发送命令反馈
     */
    public void runForSender(CommandSender sender, Runnable runnable) {
        if (sender instanceof Player player) {
            runForPlayer(player, runnable);
            return;
        }
        runGlobal(runnable);
    }
}
