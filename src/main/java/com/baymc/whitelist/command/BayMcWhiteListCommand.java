package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.GeneratedCode;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.storage.WhitelistLogEntry;
import com.baymc.whitelist.storage.WhitelistRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 处理 /baymcwhitelist 管理员命令
 */
public final class BayMcWhiteListCommand implements TabExecutor {
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> SUBCOMMANDS = List.of("generate", "status", "remove", "reload", "info");

    private final BayMcWhiteListPlugin plugin;

    /**
     * 保存所有命令分支都会使用的插件门面对象
     */
    public BayMcWhiteListCommand(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 分发管理员子命令, 并让权限失败提示走语言文件
     */
    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            if (hasPermission(sender, "baymcwhitelist.admin")) {
                plugin.lang().send(sender, "usage.admin");
            }
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "generate" -> handleGenerate(sender, args);
            case "status" -> handleStatus(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "reload" -> handleReload(sender);
            case "info" -> handleInfo(sender);
            default -> {
                if (hasPermission(sender, "baymcwhitelist.admin")) {
                    plugin.lang().send(sender, "common.unknown-command");
                }
            }
        }
        return true;
    }

    /**
     * 为管理员命令提供子命令和在线玩家补全
     */
    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(subcommand -> subcommand.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && List.of("generate", "status", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    /**
     * 生成绑定玩家的邀请码, 但不写入任何数据库状态
     */
    private void handleGenerate(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "baymcwhitelist.generate")) {
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(sender, "usage.generate");
            return;
        }

        Optional<PlayerIdentity> identity = resolveGenerationIdentity(sender, args[1]);
        if (identity.isEmpty()) {
            return;
        }

        GeneratedCode generatedCode = plugin.inviteCodeService().generate(identity.get().key());
        plugin.lang().send(sender, "admin.generate-success", Map.of(
                "player", identity.get().name(),
                "player_key", identity.get().key(),
                "code", generatedCode.code(),
                "expire_time", DATE_TIME_FORMATTER.format(generatedCode.expiresAt())
        ));
    }

    /**
     * 异步查询 MySQL, 并反馈某个玩家的白名单状态
     */
    private void handleStatus(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "baymcwhitelist.status")) {
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(sender, "usage.status");
            return;
        }
        LookupTarget target = resolveLookupTarget(sender, args[1]);
        if (target == null || !ensureDatabaseReady(sender)) {
            return;
        }

        // SQL 在异步线程执行; 命令反馈通过感知发送者的调度器切回
        // 以保持 Folia 兼容
        plugin.scheduler().runAsync(() -> {
            try {
                Optional<WhitelistRecord> record = findRecord(target);
                plugin.scheduler().runForSender(sender, () -> {
                    if (record.isPresent()) {
                        sendStatus(sender, record.get());
                    } else {
                        plugin.lang().send(sender, "admin.status-not-whitelisted", Map.of("player", target.input()));
                    }
                });
            } catch (SQLException exception) {
                plugin.getLogger().severe("Failed to query whitelist status.");
                exception.printStackTrace();
                plugin.scheduler().runForSender(sender, () -> plugin.lang().send(sender, "mysql.operation-failed"));
            }
        });
    }

    /**
     * 移除某个玩家的白名单记录, 并记录管理员操作日志
     */
    private void handleRemove(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "baymcwhitelist.remove")) {
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(sender, "usage.remove");
            return;
        }
        LookupTarget target = resolveLookupTarget(sender, args[1]);
        if (target == null || !ensureDatabaseReady(sender)) {
            return;
        }

        // 移除操作会访问 MySQL, 完成后再回到发送者对应的调度器发送消息
        plugin.scheduler().runAsync(() -> {
            try {
                Optional<WhitelistRecord> record = findRecord(target);
                if (record.isEmpty()) {
                    plugin.scheduler().runForSender(sender, () ->
                            plugin.lang().send(sender, "admin.remove-not-found", Map.of("player", target.input())));
                    return;
                }

                boolean removed = plugin.repository().removeByKey(record.get().playerKey());
                if (removed) {
                    plugin.repository().log(new WhitelistLogEntry(
                            record.get().playerKey(),
                            record.get().playerName(),
                            "ADMIN_REMOVE",
                            null,
                            plugin.pluginConfig().server().name(),
                            null,
                            sender.getName(),
                            LocalDateTime.now(plugin.pluginConfig().code().zoneId())
                    ));
                }

                plugin.scheduler().runForSender(sender, () -> plugin.lang().send(
                        sender,
                        removed ? "admin.remove-success" : "admin.remove-not-found",
                        Map.of("player", record.get().playerName())
                ));
            } catch (SQLException exception) {
                plugin.getLogger().severe("Failed to remove whitelist record.");
                exception.printStackTrace();
                plugin.scheduler().runForSender(sender, () -> plugin.lang().send(sender, "mysql.operation-failed"));
            }
        });
    }

    /**
     * 重载配置, 语言文件和数据库运行期状态
     */
    private void handleReload(CommandSender sender) {
        if (!hasPermission(sender, "baymcwhitelist.reload")) {
            return;
        }
        plugin.lang().send(sender, "admin.reload-started");
        boolean success = plugin.reloadBayMcWhiteList();
        plugin.lang().send(sender, success ? "admin.reload-success" : "admin.reload-failed");
    }

    /**
     * 显示插件模式, 邀请码配置, 身份模式和数据库状态
     */
    private void handleInfo(CommandSender sender) {
        if (!hasPermission(sender, "baymcwhitelist.info")) {
            return;
        }
        PluginConfig config = plugin.pluginConfig();
        plugin.lang().send(sender, "admin.info", Map.of(
                "version", plugin.getPluginMeta().getVersion(),
                "server_name", config.server().name(),
                "server_mode", state(config.server().mode() == PluginConfig.ServerMode.LOGIN ? "state.mode-login" : "state.mode-protected"),
                "prefix", config.code().prefix(),
                "valid_days", String.valueOf(config.code().validDays()),
                "id_type", state(config.player().idType() == PluginConfig.PlayerIdType.UUID ? "state.id-type-uuid" : "state.id-type-name"),
                "database_status", state(plugin.isDatabaseReady() ? "state.database-ready" : "state.database-unavailable")
        ));
    }

    /**
     * 为邀请码生成操作解析目标身份
     */
    private Optional<PlayerIdentity> resolveGenerationIdentity(CommandSender sender, String input) {
        PluginConfig.PlayerIdType idType = plugin.pluginConfig().player().idType();
        if (idType == PluginConfig.PlayerIdType.NAME) {
            if (!isValidPlayerName(input)) {
                plugin.lang().send(sender, "common.invalid-player-identifier");
                return Optional.empty();
            }
            return Optional.of(PlayerIdentity.forName(input));
        }

        Optional<UUID> uuid = parseUuid(input);
        if (uuid.isPresent()) {
            return Optional.of(PlayerIdentity.forUuid(uuid.get(), input));
        }

        Player onlinePlayer = Bukkit.getPlayerExact(input);
        if (onlinePlayer == null) {
            plugin.lang().send(sender, "admin.player-not-online-for-uuid-mode");
            return Optional.empty();
        }
        return Optional.of(PlayerIdentity.fromPlayer(onlinePlayer, idType));
    }

    /**
     * 为 status/remove 命令解析查询目标
     */
    private LookupTarget resolveLookupTarget(CommandSender sender, String input) {
        PluginConfig.PlayerIdType idType = plugin.pluginConfig().player().idType();
        Optional<UUID> uuid = parseUuid(input);
        if (uuid.isPresent()) {
            return new LookupTarget(input, uuid.get().toString());
        }

        if (!isValidPlayerName(input)) {
            plugin.lang().send(sender, "common.invalid-player-identifier");
            return null;
        }

        if (idType == PluginConfig.PlayerIdType.NAME) {
            return new LookupTarget(input, input.toLowerCase(Locale.ROOT));
        }

        Player onlinePlayer = Bukkit.getPlayerExact(input);
        if (onlinePlayer != null) {
            return new LookupTarget(input, onlinePlayer.getUniqueId().toString());
        }
        return new LookupTarget(input, null);
    }

    /**
     * 优先按标准键查询; UUID 模式下玩家离线时再回退到玩家名查询
     */
    private Optional<WhitelistRecord> findRecord(LookupTarget target) throws SQLException {
        if (target.playerKey() != null) {
            Optional<WhitelistRecord> byKey = plugin.repository().findByKey(target.playerKey());
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        return plugin.repository().findByName(target.input());
    }

    /**
     * 为一条已存储记录发送完整管理员状态视图
     */
    private void sendStatus(CommandSender sender, WhitelistRecord record) {
        plugin.lang().send(sender, "admin.status-whitelisted", Map.of(
                "player", value(record.playerName()),
                "player_key", value(record.playerKey()),
                "uuid", value(record.playerUuid()),
                "code", value(record.code()),
                "issue_date", value(record.issueDate()),
                "used_at", format(record.usedAt()),
                "source_server", value(record.sourceServer()),
                "last_seen_at", format(record.lastSeenAt())
        ));
    }

    /**
     * 在进入仓库调用前拦截数据库未就绪的命令
     */
    private boolean ensureDatabaseReady(CommandSender sender) {
        if (plugin.isDatabaseReady()) {
            return true;
        }
        plugin.lang().send(sender, "mysql.not-ready");
        return false;
    }

    /**
     * 集中处理权限失败提示, 避免 plugin.yml 硬编码提示文本
     */
    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.lang().send(sender, "common.no-permission");
        return false;
    }

    /**
     * 用配置中的"无"翻译渲染数据库空值
     */
    private String value(Object value) {
        return value == null ? state("state.none") : String.valueOf(value);
    }

    /**
     * 为管理员输出格式化时间戳
     */
    private String format(LocalDateTime dateTime) {
        return dateTime == null ? state("state.none") : DATE_TIME_FORMATTER.format(dateTime);
    }

    /**
     * 从语言文件解析简短状态标签
     */
    private String state(String key) {
        return plugin.lang().plain(key);
    }

    /**
     * 校验 name 模式操作中使用的 Minecraft 风格账号名
     */
    private static boolean isValidPlayerName(String input) {
        return PLAYER_NAME_PATTERN.matcher(input).matches();
    }

    /**
     * 解析 UUID 输入, 避免异常穿透命令处理器
     */
    private static Optional<UUID> parseUuid(String input) {
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 用户输入的查询文本, 以及可用时解析出的标准键
     */
    private record LookupTarget(String input, String playerKey) {
    }
}
