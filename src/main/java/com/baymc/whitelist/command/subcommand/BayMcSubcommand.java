package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;

/**
 * /baymcwhitelist 一级子命令的统一执行接口
 */
public interface BayMcSubcommand {
    /**
     * 子命令名称, 也就是 /baymcwhitelist 后面的第一个参数
     */
    String name();

    /**
     * 执行该子命令需要检查的权限节点
     */
    String permission();

    /**
     * 执行子命令, 并返回运行期快照是否已经被异步任务接管
     */
    CommandExecution execute(CommandContext context, String[] args);
}
