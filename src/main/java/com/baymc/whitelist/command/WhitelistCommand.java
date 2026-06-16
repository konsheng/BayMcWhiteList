package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.VerificationResult;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.storage.WhitelistLogEntry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class WhitelistCommand implements TabExecutor {
    private final BayMcWhiteListPlugin plugin;

    public WhitelistCommand(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "common.only-player");
            return true;
        }
        if (!player.hasPermission("baymcwhitelist.use")) {
            plugin.lang().send(player, "common.no-permission");
            return true;
        }
        if (plugin.pluginConfig().server().mode() != PluginConfig.ServerMode.LOGIN) {
            plugin.lang().send(player, "code.login-server-only");
            return true;
        }
        if (args.length != 1) {
            plugin.lang().send(player, "usage.whitelist");
            return true;
        }

        // Snapshot all player data needed by the async database task. Folia
        // entity state must not be read later from the pool thread.
        PlayerIdentity identity = PlayerIdentity.fromPlayer(player, plugin.pluginConfig().player().idType());
        VerificationResult result = plugin.inviteCodeService().verify(args[0], identity.key());
        if (result.status() == VerificationResult.Status.INVALID_FORMAT) {
            plugin.lang().send(player, "code.invalid-format", Map.of("prefix", plugin.pluginConfig().code().prefix()));
            logAttempt(identity, args[0], "VERIFY_INVALID_FORMAT", "invalid_format", addressOf(player));
            return true;
        }
        if (result.status() == VerificationResult.Status.INVALID_OR_EXPIRED) {
            plugin.lang().send(player, "code.invalid-or-expired");
            logAttempt(identity, args[0], "VERIFY_INVALID_OR_EXPIRED", "invalid_or_expired", addressOf(player));
            return true;
        }
        if (!plugin.isDatabaseReady()) {
            plugin.lang().send(player, "mysql.not-ready");
            return true;
        }

        String ip = addressOf(player);
        plugin.scheduler().runAsync(() -> verifyAndPersist(player, identity, result, ip));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return List.of();
    }

    private void verifyAndPersist(Player player, PlayerIdentity identity, VerificationResult result, String ip) {
        try {
            if (plugin.repository().isWhitelisted(identity.key())) {
                plugin.repository().log(new WhitelistLogEntry(
                        identity.key(),
                        identity.name(),
                        "VERIFY_ALREADY_WHITELISTED",
                        result.normalizedCode(),
                        plugin.pluginConfig().server().name(),
                        ip,
                        null,
                        now()
                ));
                plugin.scheduler().runForPlayer(player, () -> plugin.lang().send(player, "code.already-whitelisted"));
                return;
            }

            LocalDateTime usedAt = now();
            plugin.repository().upsert(identity, result.normalizedCode(), result.issueDate(), usedAt);
            plugin.repository().log(new WhitelistLogEntry(
                    identity.key(),
                    identity.name(),
                    "VERIFY_SUCCESS",
                    result.normalizedCode(),
                    plugin.pluginConfig().server().name(),
                    ip,
                    null,
                    usedAt
            ));

            plugin.scheduler().runForPlayer(player, () -> plugin.lang().send(player, "code.success"));
        } catch (SQLException exception) {
            plugin.getLogger().severe("Failed to verify whitelist code for " + identity.name() + ".");
            exception.printStackTrace();
            plugin.scheduler().runForPlayer(player, () -> plugin.lang().send(player, "mysql.operation-failed"));
        }
    }

    private void logAttempt(PlayerIdentity identity, String code, String action, String message, String ip) {
        if (!plugin.isDatabaseReady()) {
            return;
        }
        plugin.scheduler().runAsync(() -> {
            try {
                plugin.repository().log(new WhitelistLogEntry(
                        identity.key(),
                        identity.name(),
                        action,
                        code,
                        plugin.pluginConfig().server().name(),
                        ip,
                        message,
                        now()
                ));
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to write whitelist attempt log: " + exception.getMessage());
            }
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(plugin.pluginConfig().code().zoneId());
    }

    private static String addressOf(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }
}
