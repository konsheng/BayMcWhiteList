package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.subcommand.AddSubcommand;
import com.baymc.whitelist.command.subcommand.BayMcSubcommand;
import com.baymc.whitelist.command.subcommand.GenerateSubcommand;
import com.baymc.whitelist.command.subcommand.HelpSubcommand;
import com.baymc.whitelist.command.subcommand.InfoSubcommand;
import com.baymc.whitelist.command.subcommand.ReloadSubcommand;
import com.baymc.whitelist.command.subcommand.RemoveSubcommand;
import com.baymc.whitelist.command.subcommand.StatusSubcommand;
import com.baymc.whitelist.command.target.CommandTargetResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 处理 /baymcwhitelist 主命令入口和子命令分发
 *
 * <p>本类只保留 Bukkit 命令入口, Tab 补全和运行期快照释放判断;
 * 具体业务动作交给 subcommand 包中的命令动作类处理
 */
public final class BayMcWhiteListCommand implements TabExecutor {
    /**
     * 这些子命令的第二个参数都是玩家名或 UUID, 因此可以共用在线玩家补全
     */
    private static final Set<String> TARGET_SUBCOMMANDS = Set.of("add", "generate", "status", "remove");

    private final BayMcWhiteListPlugin plugin;
    private final CommandMessages messages;
    private final CommandTargetResolver targetResolver;
    private final Map<String, BayMcSubcommand> subcommands;

    /**
     * 创建主命令分发器, 并按固定顺序注册所有一级子命令
     */
    public BayMcWhiteListCommand(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
        this.messages = new CommandMessages();
        this.targetResolver = new CommandTargetResolver(messages);
        this.subcommands = subcommandsByName(List.of(
                new AddSubcommand(),
                new GenerateSubcommand(),
                new StatusSubcommand(),
                new RemoveSubcommand(),
                new ReloadSubcommand(),
                new InfoSubcommand(),
                new HelpSubcommand()
        ));
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState();
        CommandExecution execution = CommandExecution.FINISHED;
        try {
            /*
             * 无参数时沿用原行为显示 info, 其他输入按一级子命令分发;
             * 异步子命令会接管 runtime, 同步子命令由 finally 释放
             */
            CommandContext context = new CommandContext(plugin, runtime, sender, messages, targetResolver);
            BayMcSubcommand subcommand = args.length == 0
                    ? subcommands.get("info")
                    : subcommands.get(args[0].toLowerCase(Locale.ROOT));
            if (subcommand == null) {
                runtime.lang().send(sender, "common.unknown-command");
                return true;
            }
            execution = subcommand.execute(context, args);
        } finally {
            if (execution != CommandExecution.ASYNC_RUNNING) {
                runtime.close();
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return CommandBoundaries.visibleBayMcSubcommands(sender::hasPermission, args[0]);
        }
        if (args.length == 2 && TARGET_SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            if (!sender.hasPermission(CommandBoundaries.permissionFor(args[0]))) {
                return List.of();
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    /**
     * 将子命令列表转换为按名称查询的不可变映射
     */
    private static Map<String, BayMcSubcommand> subcommandsByName(List<BayMcSubcommand> commands) {
        Map<String, BayMcSubcommand> byName = new LinkedHashMap<>();
        for (BayMcSubcommand command : commands) {
            byName.put(command.name(), command);
        }
        return Map.copyOf(byName);
    }
}
