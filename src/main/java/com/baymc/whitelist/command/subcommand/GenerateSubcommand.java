package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.GeneratedCode;
import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;
import com.baymc.whitelist.command.target.IdentityTarget;
import com.baymc.whitelist.command.target.TargetInput;
import com.baymc.whitelist.command.target.TargetSource;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.mojang.MojangProfileLookupException;

import java.util.Map;

/**
 * 处理 /baymcwhitelist generate 参数为玩家名或 UUID
 *
 * <p>该命令只生成绑定目标 UUID 的邀请码, 不查询也不写入白名单数据库状态
 */
public final class GenerateSubcommand implements BayMcSubcommand {
    @Override
    public String name() {
        return "generate";
    }

    @Override
    public String permission() {
        return "baymcwhitelist.generate";
    }

    @Override
    public CommandExecution execute(CommandContext context, String[] args) {
        if (!context.requirePermission(permission())) {
            return CommandExecution.FINISHED;
        }
        if (!context.requireExactArgs(args, 2, "usage.generate")) {
            return CommandExecution.FINISHED;
        }

        BayMcWhiteListPlugin.RuntimeState runtime = context.runtime();
        TargetInput targetInput = context.targetResolver().parseTargetInput(runtime, context.sender(), args[1]);
        if (targetInput == null) {
            return CommandExecution.FINISHED;
        }

        IdentityTarget localTarget = context.targetResolver().resolveLocalGenerationTarget(
                runtime,
                context.sender(),
                targetInput
        );
        if (localTarget != null) {
            runtime.lang().send(
                    context.sender(),
                    localTarget.source() == TargetSource.ONLINE
                            ? "admin.generate-online-found"
                            : "admin.generate-identity-resolved",
                    context.messages().identityPlaceholders(runtime, localTarget.identity())
            );
            sendGeneratedCode(context, localTarget.identity());
            return CommandExecution.FINISHED;
        }
        if (runtime.config().player().uuidSource() != PluginConfig.UuidSource.MOJANG) {
            return CommandExecution.FINISHED;
        }

        if (targetInput.uuid().isPresent()) {
            runtime.lang().send(
                    context.sender(),
                    "admin.generate-lookup-uuid-start",
                    Map.of("uuid", targetInput.uuid().get().toString())
            );
        } else {
            runtime.lang().send(
                    context.sender(),
                    "admin.generate-lookup-name-start",
                    Map.of("player", targetInput.text())
            );
        }

        return context.runAsyncClosing(() -> {
            try {
                /*
                 * 只有 Mojang 模式的离线名称或 UUID 需要外部档案查询
                 * 查询完成后再回到发送者调度器输出生成结果
                 */
                PlayerIdentity identity = context.targetResolver().resolveMojangGenerationIdentity(
                        runtime,
                        context.sender(),
                        targetInput
                );
                if (identity == null) {
                    return;
                }
                runtime.scheduler().runForSender(context.sender(), () -> {
                    runtime.lang().send(
                            context.sender(),
                            "admin.generate-profile-found",
                            context.messages().identityPlaceholders(runtime, identity)
                    );
                    sendGeneratedCode(context, identity);
                });
            } catch (MojangProfileLookupException exception) {
                context.logger().warning("Failed to query Mojang profile for invite generation: " + exception.getMessage());
                runtime.scheduler().runForSender(
                        context.sender(),
                        () -> runtime.lang().send(context.sender(), "admin.generate-lookup-failed")
                );
            }
        });
    }

    private void sendGeneratedCode(CommandContext context, PlayerIdentity identity) {
        GeneratedCode generatedCode = context.runtime().inviteCodeService().generate(identity.uuidText());
        context.runtime().lang().send(
                context.sender(),
                "admin.generate-success",
                context.messages().generatedCodePlaceholders(context.runtime(), identity, generatedCode)
        );
    }
}
