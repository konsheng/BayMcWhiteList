package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.GeneratedCode;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.mojang.MojangProfile;
import com.baymc.whitelist.mojang.MojangProfileLookupException;
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
        BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState();
        boolean runtimeOwnedByAsync = false;
        try {
            if (args.length == 0) {
                handleInfo(runtime, sender, args);
                return true;
            }

            String subcommand = args[0].toLowerCase(Locale.ROOT);
            switch (subcommand) {
                case "add" -> runtimeOwnedByAsync = handleAdd(runtime, sender, args);
                case "generate" -> runtimeOwnedByAsync = handleGenerate(runtime, sender, args);
                case "status" -> runtimeOwnedByAsync = handleStatus(runtime, sender, args);
                case "remove" -> runtimeOwnedByAsync = handleRemove(runtime, sender, args);
                case "reload" -> handleReload(runtime, sender, args);
                case "info" -> handleInfo(runtime, sender, args);
                case "help" -> handleHelp(runtime, sender, args);
                default -> runtime.lang().send(sender, "common.unknown-command");
            }
        } finally {
            if (!runtimeOwnedByAsync) {
                runtime.close();
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
        if (args.length == 2 && List.of("add", "generate", "status", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
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
     * 通过 Mojang 档案校验后由管理员直接写入白名单
     */
    private boolean handleAdd(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.add")) {
            return false;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 2)) {
            runtime.lang().send(sender, "usage.add");
            return false;
        }
        if (!ensureDatabaseReady(runtime, sender)) {
            return false;
        }

        String input = args[1].trim();
        Optional<UUID> inputUuid = parseUuid(input);
        if (inputUuid.isEmpty() && !isValidPlayerName(input)) {
            runtime.lang().send(sender, "common.invalid-player-identifier");
            return false;
        }

        if (inputUuid.isPresent()) {
            runtime.lang().send(sender, "admin.add-lookup-uuid-start", Map.of("uuid", inputUuid.get().toString()));
        } else if (runtime.config().player().idType() == PluginConfig.PlayerIdType.UUID) {
            runtime.lang().send(sender, "admin.add-lookup-name-start", Map.of("player", input));
        } else {
            runtime.lang().send(sender, "admin.add-write-start");
        }

        runtime.scheduler().runAsync(() -> {
            try {
                AddTarget target = resolveAddTarget(runtime, sender, input, inputUuid);
                if (target == null) {
                    return;
                }

                if (target.profileVerified()) {
                    runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                            sender,
                            "admin.add-profile-found",
                            addPlaceholders(runtime, target)
                    ));
                    runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "admin.add-write-start"));
                }

                PlayerIdentity identity = target.identity();
                if (runtime.repository().isWhitelisted(identity.key())) {
                    runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                            sender,
                            "admin.add-already-whitelisted",
                            addPlaceholders(runtime, target)
                    ));
                    return;
                }

                LocalDateTime now = LocalDateTime.now(runtime.config().code().zoneId());
                boolean inserted = runtime.repository().insertManual(identity, now.toLocalDate(), now);
                if (!inserted) {
                    runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                            sender,
                            "admin.add-already-whitelisted",
                            addPlaceholders(runtime, target)
                    ));
                    return;
                }

                logManualAdd(runtime, sender, identity, now);
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.add-success",
                        addPlaceholders(runtime, target)
                ));
            } catch (MojangProfileLookupException exception) {
                plugin.getLogger().warning("Failed to query Mojang profile: " + exception.getMessage());
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "admin.add-lookup-failed"));
            } catch (SQLException exception) {
                plugin.getLogger().severe("Failed to manually add whitelist record.");
                exception.printStackTrace();
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "mysql.operation-failed"));
            } finally {
                runtime.close();
            }
        });
        return true;
    }

    /**
     * 生成绑定玩家的邀请码, 但不写入任何数据库状态
     */
    private boolean handleGenerate(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.generate")) {
            return false;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 2)) {
            runtime.lang().send(sender, "usage.generate");
            return false;
        }

        String input = args[1].trim();
        Optional<UUID> inputUuid = parseUuid(input);
        boolean validPlayerName = inputUuid.isEmpty() && isValidPlayerName(input);
        Player onlinePlayer = findOnlinePlayer(input, inputUuid);
        CommandBoundaries.GenerateTargetDecision decision = CommandBoundaries.generateTargetDecision(
                inputUuid.isPresent(),
                validPlayerName,
                onlinePlayer != null
        );

        switch (decision) {
            case ONLINE_PLAYER -> {
                PlayerIdentity identity = PlayerIdentity.fromPlayer(onlinePlayer, runtime.config().player().idType());
                runtime.lang().send(sender, "admin.generate-online-found", identityPlaceholders(runtime, identity));
                sendGeneratedCode(runtime, sender, identity);
                return false;
            }
            case INVALID_IDENTIFIER -> {
                runtime.lang().send(sender, "common.invalid-player-identifier");
                return false;
            }
            case UUID_LOOKUP -> runtime.lang().send(
                    sender,
                    "admin.generate-lookup-uuid-start",
                    Map.of("uuid", inputUuid.orElseThrow().toString())
            );
            case NAME_LOOKUP -> runtime.lang().send(
                    sender,
                    "admin.generate-lookup-name-start",
                    Map.of("player", input)
            );
        }

        runtime.scheduler().runAsync(() -> {
            try {
                PlayerIdentity identity = resolveOfflineGenerationIdentity(runtime, sender, input, inputUuid);
                if (identity == null) {
                    return;
                }
                runtime.scheduler().runForSender(sender, () -> {
                    runtime.lang().send(sender, "admin.generate-profile-found", identityPlaceholders(runtime, identity));
                    sendGeneratedCode(runtime, sender, identity);
                });
            } catch (MojangProfileLookupException exception) {
                plugin.getLogger().warning("Failed to query Mojang profile for invite generation: " + exception.getMessage());
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "admin.generate-lookup-failed"));
            } finally {
                runtime.close();
            }
        });
        return true;
    }

    /**
     * 异步查询 MySQL, 并反馈某个玩家的白名单状态
     */
    private boolean handleStatus(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.status")) {
            return false;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 2)) {
            runtime.lang().send(sender, "usage.status");
            return false;
        }
        LookupTarget target = resolveStatusTarget(runtime, sender, args[1]);
        if (target == null || !ensureDatabaseReady(runtime, sender)) {
            return false;
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
            } finally {
                runtime.close();
            }
        });
        return true;
    }

    /**
     * 移除某个玩家的白名单记录, 并记录管理员操作日志
     */
    private boolean handleRemove(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.remove")) {
            return false;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 2)) {
            runtime.lang().send(sender, "usage.remove");
            return false;
        }
        LookupTarget target = resolveRemoveTarget(runtime, sender, args[1]);
        if (target == null || !ensureDatabaseReady(runtime, sender)) {
            return false;
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
            } finally {
                runtime.close();
            }
        });
        return true;
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
        try (BayMcWhiteListPlugin.RuntimeState reloadedRuntime = plugin.runtimeState()) {
            reloadedRuntime.lang().send(sender, success ? "admin.reload-success" : "admin.reload-failed");
        }
    }

    /**
     * 显示主命令, 别名, 玩家命令和各独立权限说明
     */
    private void handleHelp(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.help")) {
            return;
        }
        if (!CommandBoundaries.hasExactArgumentCount(args, 1)) {
            runtime.lang().send(sender, "usage.help");
            return;
        }
        runtime.lang().send(sender, "admin.help");
    }

    /**
     * 显示插件模式, 邀请码配置, 身份模式和数据库状态
     */
    private void handleInfo(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String[] args) {
        if (!hasPermission(runtime, sender, "baymcwhitelist.info")) {
            return;
        }
        if (args.length > 1) {
            runtime.lang().send(sender, "usage.info");
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
     * 根据当前身份模式和管理员输入解析手动添加目标
     */
    private AddTarget resolveAddTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            String input,
            Optional<UUID> inputUuid
    ) throws MojangProfileLookupException {
        PluginConfig.PlayerIdType idType = runtime.config().player().idType();
        if (inputUuid.isPresent()) {
            Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByUuid(inputUuid.get());
            if (profile.isEmpty()) {
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.add-uuid-not-found",
                        Map.of("uuid", inputUuid.get().toString())
                ));
                return null;
            }
            return addTargetFromProfile(idType, profile.get());
        }

        if (idType == PluginConfig.PlayerIdType.UUID) {
            Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByName(input);
            if (profile.isEmpty()) {
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.add-name-not-found",
                        Map.of("player", input)
                ));
                return null;
            }
            return addTargetFromProfile(idType, profile.get());
        }

        return new AddTarget(PlayerIdentity.forName(input), false);
    }

    private AddTarget addTargetFromProfile(PluginConfig.PlayerIdType idType, MojangProfile profile) {
        return new AddTarget(
                identityFromProfile(idType, profile),
                true
        );
    }

    private void logManualAdd(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            PlayerIdentity identity,
            LocalDateTime createdAt
    ) {
        try {
            runtime.repository().log(new WhitelistLogEntry(
                    identity.key(),
                    identity.name(),
                    "ADMIN_ADD",
                    null,
                    runtime.config().server().name(),
                    null,
                    sender.getName(),
                    createdAt
            ));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to write manual whitelist add log: " + exception.getMessage());
        }
    }

    private Map<String, String> addPlaceholders(BayMcWhiteListPlugin.RuntimeState runtime, AddTarget target) {
        return identityPlaceholders(runtime, target.identity());
    }

    /**
     * 为邀请码生成操作解析目标身份
     */
    private @Nullable PlayerIdentity resolveOfflineGenerationIdentity(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            String input,
            Optional<UUID> inputUuid
    ) throws MojangProfileLookupException {
        if (inputUuid.isPresent()) {
            Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByUuid(inputUuid.get());
            if (profile.isEmpty()) {
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.generate-uuid-not-found",
                        Map.of("uuid", inputUuid.get().toString())
                ));
                return null;
            }
            return identityFromProfile(runtime.config().player().idType(), profile.get());
        }

        Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByName(input);
        if (profile.isEmpty()) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.generate-name-not-found",
                    Map.of("player", input)
            ));
            return null;
        }
        return identityFromProfile(runtime.config().player().idType(), profile.get());
    }

    private void sendGeneratedCode(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            PlayerIdentity identity
    ) {
        GeneratedCode generatedCode = runtime.inviteCodeService().generate(identity.key());
        runtime.lang().send(sender, "admin.generate-success", generatedCodePlaceholders(runtime, identity, generatedCode));
    }

    private Map<String, String> generatedCodePlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            GeneratedCode generatedCode
    ) {
        return Map.of(
                "player", identity.name(),
                "player_key", identity.key(),
                "uuid", uuidValue(runtime, identity.uuid()),
                "id_type", state(runtime, runtime.config().player().idType() == PluginConfig.PlayerIdType.UUID ? "state.id-type-uuid" : "state.id-type-name"),
                "code", generatedCode.code(),
                "expire_time", DATE_TIME_FORMATTER.format(generatedCode.expiresAt())
        );
    }

    private Map<String, String> identityPlaceholders(BayMcWhiteListPlugin.RuntimeState runtime, PlayerIdentity identity) {
        return Map.of(
                "player", identity.name(),
                "player_key", identity.key(),
                "uuid", uuidValue(runtime, identity.uuid())
        );
    }

    private PlayerIdentity identityFromProfile(PluginConfig.PlayerIdType idType, MojangProfile profile) {
        return new PlayerIdentity(
                PlayerIdentity.keyFor(idType, profile.uuid(), profile.name()),
                profile.uuid(),
                profile.name()
        );
    }

    private @Nullable Player findOnlinePlayer(String input, Optional<UUID> inputUuid) {
        if (inputUuid.isPresent()) {
            UUID uuid = inputUuid.get();
            return Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.getUniqueId().equals(uuid))
                    .findFirst()
                    .orElse(null);
        }
        return Bukkit.getPlayerExact(input);
    }

    private String uuidValue(BayMcWhiteListPlugin.RuntimeState runtime, UUID uuid) {
        return uuid == null ? state(runtime, "state.none") : uuid.toString();
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
            String normalized = input.replace("-", "");
            if (normalized.matches("[0-9a-fA-F]{32}")) {
                return Optional.of(UUID.fromString(normalized.replaceFirst(
                        "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                        "$1-$2-$3-$4-$5"
                )));
            }
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 管理员手动添加命令最终要写入白名单的身份和档案校验状态
     */
    private record AddTarget(PlayerIdentity identity, boolean profileVerified) {
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
