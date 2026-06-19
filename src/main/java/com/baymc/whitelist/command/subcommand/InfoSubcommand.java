package com.baymc.whitelist.command.subcommand;

import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;
import com.baymc.whitelist.config.PluginConfig;

import java.util.Map;

/**
 * 处理 /baymcwhitelist info 以及无参数主命令的信息展示
 */
public final class InfoSubcommand implements BayMcSubcommand {
    @Override
    public String name() {
        return "info";
    }

    @Override
    public String permission() {
        return "baymcwhitelist.info";
    }

    @Override
    public CommandExecution execute(CommandContext context, String[] args) {
        if (!context.requirePermission(permission())) {
            return CommandExecution.FINISHED;
        }
        if (args.length > 1) {
            context.send("usage.info");
            return CommandExecution.FINISHED;
        }

        PluginConfig config = context.runtime().config();
        context.send("admin.info", Map.of(
                "version", context.plugin().getPluginMeta().getVersion(),
                "server_name", config.server().name(),
                "server_mode", context.messages().state(
                        context.runtime(),
                        config.server().mode() == PluginConfig.ServerMode.LOGIN
                                ? "state.mode-login"
                                : "state.mode-protected"
                ),
                "code_prefix", config.code().prefix(),
                "valid_days", String.valueOf(config.code().validDays()),
                "uuid_source", context.messages().state(
                        context.runtime(),
                        context.messages().uuidSourceStateKey(context.runtime())
                ),
                "database_status", context.messages().state(
                        context.runtime(),
                        context.runtime().databaseReady() ? "state.database-ready" : "state.database-unavailable"
                )
        ));
        return CommandExecution.FINISHED;
    }
}
