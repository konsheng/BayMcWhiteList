package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;

/**
 * /baymcwhitelist 一级子命令的统一执行接口
 */
public interface BayMcSubcommand {
    String name();

    String permission();

    CommandExecution execute(CommandContext context, String[] args);
}
