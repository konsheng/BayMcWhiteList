package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.VerificationResult;
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

/**
 * 处理面向玩家的 /whitelist 邀请码命令
 */
public final class WhitelistCommand implements TabExecutor {
    private final BayMcWhiteListPlugin plugin;

    /**
     * 保存用于访问配置, 语言, 调度器和存储层的插件门面对象
     */
    public WhitelistCommand(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 校验命令上下文, 检查邀请码, 并异步持久化成功结果
     */
    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState();
        Player player = sender instanceof Player playerSender ? playerSender : null;
        CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                player != null,
                player != null && player.hasPermission("baymcwhitelist.use"),
                runtime.config().server().mode(),
                args.length
        );
        switch (decision) {
            case ONLY_PLAYER -> runtime.lang().send(sender, "common.only-player");
            case NO_PERMISSION -> runtime.lang().send(sender, "common.no-permission");
            case LOGIN_SERVER_ONLY -> runtime.lang().send(sender, "code.login-server-only");
            case USAGE -> runtime.lang().send(sender, "usage.whitelist");
            case OK -> {
            }
        }
        if (decision != CommandBoundaries.WhitelistDecision.OK) {
            return true;
        }

        // 先快照异步数据库任务需要的所有玩家数据, Folia 下后续不能在
        // 连接池线程中读取玩家实体状态
        PlayerIdentity identity = PlayerIdentity.fromPlayer(player, runtime.config().player().idType());
        VerificationResult result = runtime.inviteCodeService().verify(args[0], identity.key());
        if (result.status() == VerificationResult.Status.INVALID_FORMAT) {
            runtime.lang().send(player, "code.invalid-format", Map.of("code_prefix", runtime.config().code().prefix()));
            logAttempt(runtime, identity, args[0], "VERIFY_INVALID_FORMAT", "invalid_format", addressOf(player));
            return true;
        }
        if (result.status() == VerificationResult.Status.INVALID_OR_EXPIRED) {
            runtime.lang().send(player, "code.invalid-or-expired");
            logAttempt(runtime, identity, args[0], "VERIFY_INVALID_OR_EXPIRED", "invalid_or_expired", addressOf(player));
            return true;
        }
        if (!runtime.databaseReady()) {
            runtime.lang().send(player, "mysql.not-ready");
            return true;
        }

        String ip = addressOf(player);
        runtime.scheduler().runAsync(() -> verifyAndPersist(runtime, player, identity, result, ip));
        return true;
    }

    /**
     * 不返回补全内容, 因为邀请码不应该被自动建议
     */
    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return List.of();
    }

    /**
     * 向 MySQL 写入验证成功或已过白的审计记录
     */
    private void verifyAndPersist(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            PlayerIdentity identity,
            VerificationResult result,
            String ip
    ) {
        try {
            if (runtime.repository().isWhitelisted(identity.key())) {
                runtime.repository().log(new WhitelistLogEntry(
                        identity.key(),
                        identity.name(),
                        "VERIFY_ALREADY_WHITELISTED",
                        result.normalizedCode(),
                        runtime.config().server().name(),
                        ip,
                        null,
                        now(runtime)
                ));
                runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "code.already-whitelisted"));
                return;
            }

            LocalDateTime usedAt = now(runtime);
            runtime.repository().upsert(identity, result.normalizedCode(), result.issueDate(), usedAt);
            runtime.repository().log(new WhitelistLogEntry(
                    identity.key(),
                    identity.name(),
                    "VERIFY_SUCCESS",
                    result.normalizedCode(),
                    runtime.config().server().name(),
                    ip,
                    null,
                    usedAt
            ));

            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "code.success"));
        } catch (SQLException exception) {
            plugin.getLogger().severe("Failed to verify whitelist code for " + identity.name() + ".");
            exception.printStackTrace();
            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "mysql.operation-failed"));
        }
    }

    /**
     * 尽力记录无效提交的审计日志
     */
    private void logAttempt(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            String code,
            String action,
            String message,
            String ip
    ) {
        if (!runtime.databaseReady()) {
            return;
        }
        runtime.scheduler().runAsync(() -> {
            try {
                runtime.repository().log(new WhitelistLogEntry(
                        identity.key(),
                        identity.name(),
                        action,
                        code,
                        runtime.config().server().name(),
                        ip,
                        message,
                        now(runtime)
                ));
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to write whitelist attempt log: " + exception.getMessage());
            }
        });
    }

    /**
     * 所有白名单存储时间戳都使用配置中的时区
     */
    private static LocalDateTime now(BayMcWhiteListPlugin.RuntimeState runtime) {
        return LocalDateTime.now(runtime.config().code().zoneId());
    }

    /**
     * 提取玩家远程地址, 同时兼容地址不可用的情况
     */
    private static String addressOf(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }
}
