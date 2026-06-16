package com.baymc.whitelist.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
