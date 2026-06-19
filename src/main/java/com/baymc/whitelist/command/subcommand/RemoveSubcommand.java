package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;
import com.baymc.whitelist.command.target.LookupResult;
import com.baymc.whitelist.command.target.LookupTarget;
import com.baymc.whitelist.mojang.MojangProfileLookupException;
import com.baymc.whitelist.storage.WhitelistLogEntry;
import com.baymc.whitelist.storage.WhitelistRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * 处理 /baymcwhitelist remove <玩家名|UUID>
 *
 * <p>该命令只按解析出的 UUID 删除白名单记录, 删除成功后再按配置决定
 * 是否踢出当前服务器在线玩家
 */
public final class RemoveSubcommand implements BayMcSubcommand {
    @Override
    public String name() {
        return "remove";
    }

    @Override
    public String permission() {
        return "baymcwhitelist.remove";
    }

    @Override
    public CommandExecution execute(CommandContext context, String[] args) {
        if (!context.requirePermission(permission())) {
            return CommandExecution.FINISHED;
        }
        if (!context.requireExactArgs(args, 2, "usage.remove")) {
            return CommandExecution.FINISHED;
        }

        LookupTarget target = context.targetResolver().resolveRemoveTarget(
                context.runtime(),
                context.sender(),
                args[1]
        );
        if (target == null || !context.requireDatabaseReady()) {
            return CommandExecution.FINISHED;
        }

        return context.runAsyncClosing(() -> {
            BayMcWhiteListPlugin.RuntimeState runtime = context.runtime();
            CommandSender sender = context.sender();
            try {
                LookupTarget resolvedTarget = context.targetResolver().resolveMojangRemoveTarget(runtime, sender, target);
                if (resolvedTarget == null) {
                    return;
                }

                LookupResult result = findRecord(runtime, resolvedTarget);
                if (result.record().isEmpty()) {
                    runtime.scheduler().runForSender(sender, () ->
                            runtime.lang().send(
                                    sender,
                                    "admin.remove-not-found",
                                    Map.of("player", resolvedTarget.input())
                            ));
                    return;
                }
                WhitelistRecord record = result.record().get();

                boolean removed = runtime.repository().removeByUuid(record.playerUuid());
                if (removed) {
                    runtime.repository().log(new WhitelistLogEntry(
                            record.playerUuid(),
                            record.playerName(),
                            "ADMIN_REMOVE",
                            null,
                            runtime.config().server().name(),
                            null,
                            sender.getName(),
                            LocalDateTime.now(runtime.config().code().zoneId())
                    ));
                }

                if (!removed) {
                    runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                            sender,
                            "admin.remove-not-found",
                            Map.of("player", record.playerName())
                    ));
                    return;
                }

                completeRemoval(context, record);
            } catch (MojangProfileLookupException exception) {
                context.logger().warning("Failed to query Mojang profile for whitelist removal: " + exception.getMessage());
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "admin.remove-lookup-failed"));
            } catch (SQLException exception) {
                context.logger().log(Level.SEVERE, "Failed to remove whitelist record.", exception);
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "database.operation-failed"));
            }
        });
    }

    private LookupResult findRecord(
            BayMcWhiteListPlugin.RuntimeState runtime,
            LookupTarget target
    ) throws SQLException {
        if (target.playerUuid() == null) {
            return new LookupResult(Optional.empty());
        }
        return new LookupResult(runtime.repository().findByUuid(target.playerUuid()));
    }

    /**
     * 完成删除后的反馈和可选踢出流程
     *
     * <p>踢人需要先到全局调度器查找在线玩家, 再切到目标玩家调度器执行 kick
     */
    private void completeRemoval(
            CommandContext context,
            WhitelistRecord record
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = context.runtime();
        CommandSender sender = context.sender();
        if (!runtime.config().remove().kickOnlinePlayer()) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.remove-success",
                    context.messages().removalPlaceholders(runtime, record)
            ));
            return;
        }
        if (!runtime.config().remove().shouldKickIn(runtime.config().server().mode())) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.remove-success-kick-skipped-mode",
                    context.messages().removalPlaceholders(runtime, record)
            ));
            return;
        }

        runtime.scheduler().runGlobal(() -> {
            Player onlinePlayer = context.targetResolver().findOnlineRemovedPlayer(record);
            if (onlinePlayer == null) {
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.remove-success-offline",
                        context.messages().removalPlaceholders(runtime, record)
                ));
                return;
            }

            Component kickMessage = runtime.lang().joined(
                    "kick.whitelist-removed",
                    context.messages().removalPlaceholders(runtime, record)
            );
            runtime.scheduler().runForPlayer(onlinePlayer, () -> onlinePlayer.kick(kickMessage));
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.remove-success-kicked",
                    context.messages().removalPlaceholders(runtime, record)
            ));
        });
    }
}
