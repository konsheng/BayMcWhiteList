package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;
import com.baymc.whitelist.command.target.LookupResult;
import com.baymc.whitelist.command.target.LookupTarget;
import com.baymc.whitelist.mojang.MojangProfileLookupException;
import com.baymc.whitelist.storage.WhitelistRecord;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * 处理 /baymcwhitelist status 参数为玩家名或 UUID
 *
 * <p>该命令会先把输入解析到明确 UUID, 再异步查询数据库
 * 避免按历史玩家名误判白名单状态
 */
public final class StatusSubcommand implements BayMcSubcommand {
    @Override
    public String name() {
        return "status";
    }

    @Override
    public String permission() {
        return "baymcwhitelist.status";
    }

    @Override
    public CommandExecution execute(CommandContext context, String[] args) {
        if (!context.requirePermission(permission())) {
            return CommandExecution.FINISHED;
        }
        if (!context.requireExactArgs(args, 2, "usage.status")) {
            return CommandExecution.FINISHED;
        }

        LookupTarget target = context.targetResolver().resolveStatusTarget(
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
                LookupTarget resolvedTarget = context.targetResolver().resolveMojangStatusTarget(runtime, sender, target);
                if (resolvedTarget == null) {
                    return;
                }

                LookupResult result = findRecord(runtime, resolvedTarget);
                runtime.scheduler().runForSender(sender, () -> {
                    if (result.record().isPresent()) {
                        sendStatus(context, result.record().get(), resolvedTarget);
                    } else {
                        runtime.lang().send(
                                sender,
                                "admin.status-not-whitelisted",
                                context.messages().statusLookupPlaceholders(runtime, resolvedTarget)
                        );
                    }
                });
            } catch (MojangProfileLookupException exception) {
                context.logger().warning("Failed to query Mojang profile for whitelist status: " + exception.getMessage());
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "admin.status-lookup-failed"));
            } catch (SQLException exception) {
                context.logger().log(Level.SEVERE, "Failed to query whitelist status.", exception);
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

    private void sendStatus(
            CommandContext context,
            WhitelistRecord record,
            LookupTarget target
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = context.runtime();
        runtime.lang().send(context.sender(), "admin.status-whitelisted", Map.of(
                "player", context.messages().value(runtime, record.playerName()),
                "uuid", context.messages().value(runtime, record.playerUuid()),
                "lookup_input", context.messages().value(runtime, target.input()),
                "lookup_type", context.messages().statusLookupType(runtime, target),
                "code", context.messages().value(runtime, record.code()),
                "issue_date", context.messages().value(runtime, record.issueDate()),
                "used_at", context.messages().format(runtime, record.usedAt()),
                "source_server", context.messages().value(runtime, record.sourceServer()),
                "last_seen_at", context.messages().format(runtime, record.lastSeenAt())
        ));
    }
}
