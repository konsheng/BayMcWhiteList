package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;

/**
 * 处理 /baymcwhitelist help
 */
public final class HelpSubcommand implements BayMcSubcommand {
    @Override
    public String name() {
        return "help";
    }

    @Override
    public String permission() {
        return "baymcwhitelist.help";
    }

    @Override
    public CommandExecution execute(CommandContext context, String[] args) {
        if (!context.requirePermission(permission())) {
            return CommandExecution.FINISHED;
        }
        if (!context.requireExactArgs(args, 1, "usage.help")) {
            return CommandExecution.FINISHED;
        }
        context.send("admin.help");
        return CommandExecution.FINISHED;
    }
}
