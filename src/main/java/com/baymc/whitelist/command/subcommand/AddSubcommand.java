package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;
import com.baymc.whitelist.command.target.AddTarget;
import com.baymc.whitelist.command.target.TargetInput;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.mojang.MojangProfileLookupException;
import com.baymc.whitelist.storage.WhitelistLogEntry;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.logging.Level;

/**
 * 处理 /baymcwhitelist add 参数为玩家名或 UUID
 *
 * <p>该命令会按当前 UUID 来源解析目标身份, 异步写入白名单记录
 * 并记录管理员手动添加审计日志
 */
public final class AddSubcommand implements BayMcSubcommand {
    @Override
    public String name() {
        return "add";
    }

    @Override
    public String permission() {
        return "baymcwhitelist.add";
    }

    @Override
    public CommandExecution execute(CommandContext context, String[] args) {
        if (!context.requirePermission(permission())) {
            return CommandExecution.FINISHED;
        }
        if (!context.requireExactArgs(args, 2, "usage.add")) {
            return CommandExecution.FINISHED;
        }
        if (!context.requireDatabaseReady()) {
            return CommandExecution.FINISHED;
        }

        BayMcWhiteListPlugin.RuntimeState runtime = context.runtime();
        TargetInput targetInput = context.targetResolver().parseTargetInput(runtime, context.sender(), args[1]);
        if (targetInput == null) {
            return CommandExecution.FINISHED;
        }

        AddTarget localTarget = context.targetResolver().resolveLocalAddTarget(runtime, context.sender(), targetInput);
        if (localTarget == null && runtime.config().player().uuidSource() != PluginConfig.UuidSource.MOJANG) {
            return CommandExecution.FINISHED;
        }
        if (localTarget != null) {
            runtime.lang().send(
                    context.sender(),
                    "admin.add-identity-resolved",
                    context.messages().addPlaceholders(runtime, localTarget)
            );
            runtime.lang().send(context.sender(), "admin.add-write-start");
        } else if (targetInput.uuid().isPresent()) {
            runtime.lang().send(
                    context.sender(),
                    "admin.add-lookup-uuid-start",
                    Map.of("uuid", targetInput.uuid().get().toString())
            );
        } else {
            runtime.lang().send(
                    context.sender(),
                    "admin.add-lookup-name-start",
                    Map.of("player", targetInput.text())
            );
        }

        return context.runAsyncClosing(() -> {
            try {
                /*
                 * Mojang 模式下离线名称需要异步查询正版档案
                 * 其他 UUID 来源在进入异步任务前已经完成本地解析
                 */
                AddTarget target = localTarget == null
                        ? context.targetResolver().resolveMojangAddTarget(runtime, context.sender(), targetInput)
                        : localTarget;
                if (target == null) {
                    return;
                }

                if (target.profileVerified()) {
                    runtime.scheduler().runForSender(context.sender(), () -> runtime.lang().send(
                            context.sender(),
                            "admin.add-profile-found",
                            context.messages().addPlaceholders(runtime, target)
                    ));
                    runtime.scheduler().runForSender(
                            context.sender(),
                            () -> runtime.lang().send(context.sender(), "admin.add-write-start")
                    );
                }

                PlayerIdentity identity = target.identity();
                if (runtime.repository().isWhitelisted(identity.uuidText())) {
                    runtime.scheduler().runForSender(context.sender(), () -> runtime.lang().send(
                            context.sender(),
                            "admin.add-already-whitelisted",
                            context.messages().addPlaceholders(runtime, target)
                    ));
                    return;
                }

                LocalDateTime now = LocalDateTime.now(runtime.config().code().zoneId());
                boolean inserted = runtime.repository().insertManual(identity, now.toLocalDate(), now);
                if (!inserted) {
                    runtime.scheduler().runForSender(context.sender(), () -> runtime.lang().send(
                            context.sender(),
                            "admin.add-already-whitelisted",
                            context.messages().addPlaceholders(runtime, target)
                    ));
                    return;
                }

                logManualAdd(context, identity, now);
                runtime.scheduler().runForSender(context.sender(), () -> runtime.lang().send(
                        context.sender(),
                        "admin.add-success",
                        context.messages().addPlaceholders(runtime, target)
                ));
            } catch (MojangProfileLookupException exception) {
                context.logger().warning("Failed to query Mojang profile: " + exception.getMessage());
                runtime.scheduler().runForSender(
                        context.sender(),
                        () -> runtime.lang().send(context.sender(), "admin.add-lookup-failed")
                );
            } catch (SQLException exception) {
                context.logger().log(Level.SEVERE, "Failed to manually add whitelist record.", exception);
                runtime.scheduler().runForSender(
                        context.sender(),
                        () -> runtime.lang().send(context.sender(), "database.operation-failed")
                );
            }
        });
    }

    private void logManualAdd(
            CommandContext context,
            PlayerIdentity identity,
            LocalDateTime createdAt
    ) {
        try {
            context.runtime().repository().log(new WhitelistLogEntry(
                    identity.uuidText(),
                    identity.name(),
                    "ADMIN_ADD",
                    null,
                    context.runtime().config().server().name(),
                    null,
                    context.sender().getName(),
                    createdAt
            ));
        } catch (SQLException exception) {
            context.logger().warning("Failed to write manual whitelist add log: " + exception.getMessage());
        }
    }
}
