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
            BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState();
            if (hasPermission(runtime, sender, "baymcwhitelist.admin")) {
                runtime.lang().send(sender, "usage.admin");
            }
            return true;
        }

        BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState();
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "generate" -> handleGenerate(runtime, sender, args);
            case "status" -> handleStatus(runtime, sender, args);
            case "remove" -> handleRemove(runtime, sender, args);
            case "reload" -> handleReload(runtime, sender, args);
            case "info" -> handleInfo(runtime, sender, args);
            default -> {
                if (hasPermission(runtime, sender, "baymcwhitelist.admin")) {
                    runtime.lang().send(sender, "common.unknown-command");
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
            return CommandBoundaries.visibleAdminSubcommands(sender::hasPermission, args[0]);
        }
        if (args.length == 2 && List.of("generate", "status", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
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
     * 生成绑定玩家的邀请码, 但不写入任何数据库状态
     */
    private void handleGenerate(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.generate")) {
            return;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 2)) {
            runtime.lang().send(sender, "usage.generate");
            return;
        }

        Optional<PlayerIdentity> identity = resolveGenerationIdentity(runtime, sender, args[1]);
        if (identity.isEmpty()) {
            return;
        }

        GeneratedCode generatedCode = runtime.inviteCodeService().generate(identity.get().key());
        runtime.lang().send(sender, "admin.generate-success", Map.of(
                "player", identity.get().name(),
                "player_key", identity.get().key(),
                "code", generatedCode.code(),
                "expire_time", DATE_TIME_FORMATTER.format(generatedCode.expiresAt())
        ));
    }

    /**
     * 异步查询 MySQL, 并反馈某个玩家的白名单状态
     */
    private void handleStatus(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.status")) {
            return;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 2)) {
            runtime.lang().send(sender, "usage.status");
            return;
        }
        LookupTarget target = resolveStatusTarget(runtime, sender, args[1]);
        if (target == null || !ensureDatabaseReady(runtime, sender)) {
            return;
        }

        // SQL 在异步线程执行; 命令反馈通过感知发送者的调度器切回
        // 以保持 Folia 兼容
        runtime.scheduler().runAsync(() -> {
            try {
                LookupResult result = findRecord(runtime, target);
                runtime.scheduler().runForSender(sender, () -> {
                    if (result.record().isPresent()) {
                        if (result.matchedByNameFallback()) {
                            runtime.lang().send(sender, "admin.status-name-fallback", Map.of("player", target.input()));
                        }
                        sendStatus(runtime, sender, result.record().get());
                    } else {
                        runtime.lang().send(sender, "admin.status-not-whitelisted", Map.of("player", target.input()));
                    }
                });
            } catch (SQLException exception) {
                plugin.getLogger().severe("Failed to query whitelist status.");
                exception.printStackTrace();
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "mysql.operation-failed"));
            }
        });
    }

    /**
     * 移除某个玩家的白名单记录, 并记录管理员操作日志
     */
    private void handleRemove(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.remove")) {
            return;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 2)) {
            runtime.lang().send(sender, "usage.remove");
            return;
        }
        LookupTarget target = resolveRemoveTarget(runtime, sender, args[1]);
        if (target == null || !ensureDatabaseReady(runtime, sender)) {
            return;
        }

        // 移除操作会访问 MySQL, 完成后再回到发送者对应的调度器发送消息
        runtime.scheduler().runAsync(() -> {
            try {
                LookupResult result = findRecord(runtime, target);
                if (result.record().isEmpty()) {
                    runtime.scheduler().runForSender(sender, () ->
                            runtime.lang().send(sender, "admin.remove-not-found", Map.of("player", target.input())));
                    return;
                }
                WhitelistRecord record = result.record().get();

                boolean removed = runtime.repository().removeByKey(record.playerKey());
                if (removed) {
                    runtime.repository().log(new WhitelistLogEntry(
                            record.playerKey(),
                            record.playerName(),
                            "ADMIN_REMOVE",
                            null,
                            runtime.config().server().name(),
                            null,
                            sender.getName(),
                            LocalDateTime.now(runtime.config().code().zoneId())
                    ));
                }

                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        removed ? "admin.remove-success" : "admin.remove-not-found",
                        Map.of("player", record.playerName())
                ));
            } catch (SQLException exception) {
                plugin.getLogger().severe("Failed to remove whitelist record.");
                exception.printStackTrace();
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "mysql.operation-failed"));
            }
        });
    }

    /**
     * 重载配置, 语言文件和数据库运行期状态
     */
    private void handleReload(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.reload")) {
            return;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 1)) {
            runtime.lang().send(sender, "usage.admin");
            return;
        }
        runtime.lang().send(sender, "admin.reload-started");
        boolean success = plugin.reloadBayMcWhiteList();
        plugin.runtimeState().lang().send(sender, success ? "admin.reload-success" : "admin.reload-failed");
    }

    /**
     * 显示插件模式, 邀请码配置, 身份模式和数据库状态
     */
    private void handleInfo(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.info")) {
            return;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 1)) {
            runtime.lang().send(sender, "usage.admin");
            return;
        }
        PluginConfig config = runtime.config();
        runtime.lang().send(sender, "admin.info", Map.of(
                "version", plugin.getPluginMeta().getVersion(),
                "server_name", config.server().name(),
                "server_mode", state(runtime, config.server().mode() == PluginConfig.ServerMode.LOGIN ? "state.mode-login" : "state.mode-protected"),
                "code_prefix", config.code().prefix(),
                "valid_days", String.valueOf(config.code().validDays()),
                "id_type", state(runtime, config.player().idType() == PluginConfig.PlayerIdType.UUID ? "state.id-type-uuid" : "state.id-type-name"),
                "database_status", state(runtime, runtime.databaseReady() ? "state.database-ready" : "state.database-unavailable")
        ));
    }

    /**
     * 为邀请码生成操作解析目标身份
     */
    private Optional<PlayerIdentity> resolveGenerationIdentity(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            String input
    ) {
        PluginConfig.PlayerIdType idType = runtime.config().player().idType();
        if (idType == PluginConfig.PlayerIdType.NAME) {
            if (!isValidPlayerName(input)) {
                runtime.lang().send(sender, "common.invalid-player-identifier");
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
            runtime.lang().send(sender, "admin.player-not-online-for-uuid-mode");
            return Optional.empty();
        }
        return Optional.of(PlayerIdentity.fromPlayer(onlinePlayer, idType));
    }

    /**
     * 为状态命令解析查询目标, UUID 模式下允许离线玩家名回查历史记录
     */
    private LookupTarget resolveStatusTarget(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String input) {
        PluginConfig.PlayerIdType idType = runtime.config().player().idType();
        Optional<UUID> uuid = parseUuid(input);
        if (uuid.isPresent()) {
            return new LookupTarget(input, uuid.get().toString(), false);
        }

        if (!isValidPlayerName(input)) {
            runtime.lang().send(sender, "common.invalid-player-identifier");
            return null;
        }

        if (idType == PluginConfig.PlayerIdType.NAME) {
            return new LookupTarget(input, input.toLowerCase(Locale.ROOT), false);
        }

        Player onlinePlayer = Bukkit.getPlayerExact(input);
        if (onlinePlayer != null) {
            return new LookupTarget(input, onlinePlayer.getUniqueId().toString(), true);
        }
        return new LookupTarget(input, null, true);
    }

    /**
     * UUID 模式下移除白名单必须使用标准 UUID, 避免离线名称误删同名或改名后的记录
     */
    private LookupTarget resolveRemoveTarget(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String input) {
        PluginConfig.PlayerIdType idType = runtime.config().player().idType();
        Optional<UUID> uuid = parseUuid(input);
        boolean validName = isValidPlayerName(input);
        Player onlinePlayer = uuid.isEmpty() && validName ? Bukkit.getPlayerExact(input) : null;
        CommandBoundaries.RemoveTargetDecision decision = CommandBoundaries.removeTargetDecision(
                idType,
                uuid.isPresent(),
                validName,
                onlinePlayer != null
        );
        return switch (decision) {
            case UUID_INPUT -> new LookupTarget(input, uuid.orElseThrow().toString(), false);
            case NAME_MODE_NAME -> new LookupTarget(input, input.toLowerCase(Locale.ROOT), false);
            case ONLINE_UUID_NAME -> new LookupTarget(input, onlinePlayer.getUniqueId().toString(), false);
            case INVALID_IDENTIFIER -> {
                runtime.lang().send(sender, "common.invalid-player-identifier");
                yield null;
            }
            case UUID_MODE_OFFLINE_NAME_REQUIRES_UUID -> {
                runtime.lang().send(sender, "admin.remove-requires-uuid-in-uuid-mode");
                yield null;
            }
        };
    }

    /**
     * 优先按标准键查询; 只有目标允许名称回退时才按玩家名查询历史记录
     */
    private LookupResult findRecord(BayMcWhiteListPlugin.RuntimeState runtime, LookupTarget target) throws SQLException {
        if (target.playerKey() != null) {
            Optional<WhitelistRecord> byKey = runtime.repository().findByKey(target.playerKey());
            if (byKey.isPresent()) {
                return new LookupResult(byKey, false);
            }
        }
        if (!target.allowNameFallback()) {
            return new LookupResult(Optional.empty(), false);
        }
        Optional<WhitelistRecord> byName = runtime.repository().findByName(target.input());
        return new LookupResult(byName, byName.isPresent());
    }

    /**
     * 为一条已存储记录发送完整管理员状态视图
     */
    private void sendStatus(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, WhitelistRecord record) {
        runtime.lang().send(sender, "admin.status-whitelisted", Map.of(
                "player", value(runtime, record.playerName()),
                "player_key", value(runtime, record.playerKey()),
                "uuid", value(runtime, record.playerUuid()),
                "code", value(runtime, record.code()),
                "issue_date", value(runtime, record.issueDate()),
                "used_at", format(runtime, record.usedAt()),
                "source_server", value(runtime, record.sourceServer()),
                "last_seen_at", format(runtime, record.lastSeenAt())
        ));
    }

    /**
     * 在进入仓库调用前拦截数据库未就绪的命令
     */
    private boolean ensureDatabaseReady(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender) {
        if (runtime.databaseReady()) {
            return true;
        }
        runtime.lang().send(sender, "mysql.not-ready");
        return false;
    }

    /**
     * 集中处理权限失败提示, 避免 plugin.yml 硬编码提示文本
     */
    private boolean hasPermission(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        runtime.lang().send(sender, "common.no-permission");
        return false;
    }

    /**
     * 用配置中的"无"翻译渲染数据库空值
     */
    private String value(BayMcWhiteListPlugin.RuntimeState runtime, Object value) {
        return value == null ? state(runtime, "state.none") : String.valueOf(value);
    }

    /**
     * 为管理员输出格式化时间戳
     */
    private String format(BayMcWhiteListPlugin.RuntimeState runtime, LocalDateTime dateTime) {
        return dateTime == null ? state(runtime, "state.none") : DATE_TIME_FORMATTER.format(dateTime);
    }

    /**
     * 从语言文件解析简短状态标签
     */
    private String state(BayMcWhiteListPlugin.RuntimeState runtime, String key) {
        return runtime.lang().plain(key);
    }

    /**
     * 校验玩家名模式操作中使用的 Minecraft 风格账号名
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
    private record LookupTarget(String input, String playerKey, boolean allowNameFallback) {
    }

    /**
     * 仓库查询结果, 同时标记该结果是否来自 UUID 模式下的玩家名回退查询
     */
    private record LookupResult(Optional<WhitelistRecord> record, boolean matchedByNameFallback) {
    }
}
