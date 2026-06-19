package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;

/**
 * 处理 /baymcwhitelist reload
 *
 * <p>重载会替换配置, 语言文件和数据库运行期状态; 结果消息使用重载后的语言文件发送
 */
public final class ReloadSubcommand implements BayMcSubcommand {
    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "baymcwhitelist.reload";
    }

    @Override
    public CommandExecution execute(CommandContext context, String[] args) {
        if (!context.requirePermission(permission())) {
            return CommandExecution.FINISHED;
        }
        if (!context.requireExactArgs(args, 1, "usage.admin")) {
            return CommandExecution.FINISHED;
        }
        context.send("admin.reload-started");
        boolean success = context.plugin().reloadBayMcWhiteList();
        try (BayMcWhiteListPlugin.RuntimeState reloadedRuntime = context.plugin().runtimeState()) {
            reloadedRuntime.lang().send(context.sender(), success ? "admin.reload-success" : "admin.reload-failed");
        }
        return CommandExecution.FINISHED;
    }
}
