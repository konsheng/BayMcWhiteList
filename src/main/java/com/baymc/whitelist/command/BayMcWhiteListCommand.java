package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.GeneratedCode;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.identity.PlayerIdentityResolver;
import com.baymc.whitelist.mojang.MojangProfile;
import com.baymc.whitelist.mojang.MojangProfileLookupException;
import com.baymc.whitelist.storage.WhitelistLogEntry;
import com.baymc.whitelist.storage.WhitelistRecord;
import net.kyori.adventure.text.Component;
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
import java.util.logging.Level;

/**
 * 处理 /baymcwhitelist 管理员命令
 *
 * <p>本类只把命令输入解析到明确的 PlayerIdentity 或 LookupTarget。
 * 真正入库查询和删除始终按 UUID 执行, 避免玩家改名后按历史名称误删记录。
 */
public final class BayMcWhiteListCommand implements TabExecutor {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BayMcWhiteListPlugin plugin;

    /**
     * 保存所有命令分支都会使用的插件门面对象
     *
     * @param plugin 当前插件实例
     */
    public BayMcWhiteListCommand(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 分发管理员子命令, 并让权限失败提示走语言文件
     *
     * @param sender 命令发送者
     * @param command Bukkit 命令对象
     * @param label 管理员使用的命令标签
     * @param args 命令参数
     * @return Bukkit 命令处理结果, 始终返回 true 以使用语言文件提示
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
     *
     * @param sender 命令发送者
     * @param command Bukkit 命令对象
     * @param label 管理员使用的命令标签
     * @param args 当前参数
     * @return 发送者有权限看到的补全结果
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
     * 按当前 UUID 来源解析目标后由管理员直接写入白名单
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

        TargetInput targetInput = parseTargetInput(runtime, sender, args[1]);
        if (targetInput == null) {
            return false;
        }

        AddTarget localTarget = resolveLocalAddTarget(runtime, sender, targetInput);
        if (localTarget == null && runtime.config().player().uuidSource() != PluginConfig.UuidSource.MOJANG) {
            return false;
        }
        if (localTarget != null) {
            runtime.lang().send(sender, "admin.add-identity-resolved", addPlaceholders(runtime, localTarget));
            runtime.lang().send(sender, "admin.add-write-start");
        } else if (targetInput.uuid().isPresent()) {
            runtime.lang().send(sender, "admin.add-lookup-uuid-start", Map.of("uuid", targetInput.uuid().get().toString()));
        } else {
            runtime.lang().send(sender, "admin.add-lookup-name-start", Map.of("player", targetInput.text()));
        }

        runtime.scheduler().runAsync(() -> {
            try {
                AddTarget target = localTarget == null
                        ? resolveMojangAddTarget(runtime, sender, targetInput)
                        : localTarget;
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
                if (runtime.repository().isWhitelisted(identity.uuidText())) {
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
                plugin.getLogger().log(Level.SEVERE, "Failed to manually add whitelist record.", exception);
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "database.operation-failed"));
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

        TargetInput targetInput = parseTargetInput(runtime, sender, args[1]);
        if (targetInput == null) {
            return false;
        }

        IdentityTarget localTarget = resolveLocalGenerationTarget(runtime, sender, targetInput);
        if (localTarget != null) {
            runtime.lang().send(
                    sender,
                    localTarget.source() == TargetSource.ONLINE
                            ? "admin.generate-online-found"
                            : "admin.generate-identity-resolved",
                    identityPlaceholders(runtime, localTarget.identity())
            );
            sendGeneratedCode(runtime, sender, localTarget.identity());
            return false;
        }
        if (runtime.config().player().uuidSource() != PluginConfig.UuidSource.MOJANG) {
            return false;
        }

        if (targetInput.uuid().isPresent()) {
            runtime.lang().send(
                    sender,
                    "admin.generate-lookup-uuid-start",
                    Map.of("uuid", targetInput.uuid().get().toString())
            );
        } else {
            runtime.lang().send(
                    sender,
                    "admin.generate-lookup-name-start",
                    Map.of("player", targetInput.text())
            );
        }

        runtime.scheduler().runAsync(() -> {
            try {
                PlayerIdentity identity = resolveMojangGenerationIdentity(runtime, sender, targetInput);
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
     * 异步查询数据库, 并反馈某个玩家的白名单状态
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

        // 数据库查询在异步线程执行; 命令反馈通过感知发送者的调度器切回
        // 以保持 Folia 兼容
        runtime.scheduler().runAsync(() -> {
            try {
                LookupTarget resolvedTarget = resolveMojangStatusTarget(runtime, sender, target);
                if (resolvedTarget == null) {
                    return;
                }

                LookupResult result = findRecord(runtime, resolvedTarget);
                runtime.scheduler().runForSender(sender, () -> {
                    if (result.record().isPresent()) {
                        sendStatus(runtime, sender, result.record().get(), resolvedTarget);
                    } else {
                        runtime.lang().send(sender, "admin.status-not-whitelisted",
                                statusLookupPlaceholders(runtime, resolvedTarget));
                    }
                });
            } catch (MojangProfileLookupException exception) {
                plugin.getLogger().warning("Failed to query Mojang profile for whitelist status: " + exception.getMessage());
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "admin.status-lookup-failed"));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to query whitelist status.", exception);
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "database.operation-failed"));
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

        // 移除操作会访问数据库, 完成后再回到发送者对应的调度器发送消息
        runtime.scheduler().runAsync(() -> {
            try {
                LookupTarget resolvedTarget = resolveMojangRemoveTarget(runtime, sender, target);
                if (resolvedTarget == null) {
                    return;
                }

                LookupResult result = findRecord(runtime, resolvedTarget);
                if (result.record().isEmpty()) {
                    runtime.scheduler().runForSender(sender, () ->
                            runtime.lang().send(sender, "admin.remove-not-found", Map.of("player", resolvedTarget.input())));
                    return;
                }
                WhitelistRecord record = result.record().get();

                boolean removed = runtime.repository().removeByUuid(record.playerUuid());
                if (removed) {
                    runtime.repository().log(new WhitelistLogEntry(
                            record.playerUuid(),
                            record.playerName(),
                            "ADMIN_REMOVE",
                            null,
                            runtime.config().server().name(),
                            null,
                            sender.getName(),
                            LocalDateTime.now(runtime.config().code().zoneId())
                    ));
                }

                if (!removed) {
                    runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                            sender,
                            "admin.remove-not-found",
                            Map.of("player", record.playerName())
                    ));
                    return;
                }

                completeRemoval(runtime, sender, record);
            } catch (MojangProfileLookupException exception) {
                plugin.getLogger().warning("Failed to query Mojang profile for whitelist removal: " + exception.getMessage());
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "admin.remove-lookup-failed"));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove whitelist record.", exception);
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(sender, "database.operation-failed"));
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
                "uuid_source", state(runtime, uuidSourceStateKey(runtime)),
                "database_status", state(runtime, runtime.databaseReady() ? "state.database-ready" : "state.database-unavailable")
        ));
    }

    /**
     * 根据当前身份模式和管理员输入解析手动添加目标
     */
    private @Nullable AddTarget resolveLocalAddTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            TargetInput targetInput
    ) {
        PluginConfig.UuidSource uuidSource = runtime.config().player().uuidSource();
        if (uuidSource == PluginConfig.UuidSource.MOJANG) {
            return null;
        }
        if (targetInput.uuid().isPresent()) {
            return new AddTarget(PlayerIdentityResolver.fromUuidInput(targetInput.uuid().get()), false);
        }
        if (uuidSource == PluginConfig.UuidSource.OFFLINE_NAME) {
            return new AddTarget(PlayerIdentityResolver.fromOfflineName(targetInput.text()), false);
        }

        Player onlinePlayer = Bukkit.getPlayerExact(targetInput.text());
        if (onlinePlayer == null) {
            sendServerSourceOfflineNameUnsupported(runtime, sender);
            return null;
        }
        return new AddTarget(PlayerIdentityResolver.fromPlayer(onlinePlayer, uuidSource), false);
    }

    private AddTarget resolveMojangAddTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            TargetInput targetInput
    ) throws MojangProfileLookupException {
        if (targetInput.uuid().isPresent()) {
            UUID uuid = targetInput.uuid().get();
            Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByUuid(uuid);
            if (profile.isEmpty()) {
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.add-uuid-not-found",
                        Map.of("uuid", uuid.toString())
                ));
                return null;
            }
            return addTargetFromProfile(profile.get());
        }

        Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByName(targetInput.text());
        if (profile.isEmpty()) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.add-name-not-found",
                    Map.of("player", targetInput.text())
            ));
            return null;
        }
        return addTargetFromProfile(profile.get());
    }

    private AddTarget addTargetFromProfile(MojangProfile profile) {
        return new AddTarget(
                identityFromProfile(profile),
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
                    identity.uuidText(),
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
    private @Nullable IdentityTarget resolveLocalGenerationTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            TargetInput targetInput
    ) {
        PluginConfig.UuidSource uuidSource = runtime.config().player().uuidSource();
        Player onlinePlayer = findOnlinePlayer(targetInput);
        if (onlinePlayer != null) {
            return new IdentityTarget(PlayerIdentityResolver.fromPlayer(onlinePlayer, uuidSource), TargetSource.ONLINE);
        }
        if (uuidSource == PluginConfig.UuidSource.MOJANG) {
            return null;
        }
        if (targetInput.uuid().isPresent()) {
            return new IdentityTarget(PlayerIdentityResolver.fromUuidInput(targetInput.uuid().get()), TargetSource.LOCAL);
        }
        if (uuidSource == PluginConfig.UuidSource.OFFLINE_NAME) {
            return new IdentityTarget(PlayerIdentityResolver.fromOfflineName(targetInput.text()), TargetSource.LOCAL);
        }

        sendServerSourceOfflineNameUnsupported(runtime, sender);
        return null;
    }

    private @Nullable PlayerIdentity resolveMojangGenerationIdentity(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            TargetInput targetInput
    ) throws MojangProfileLookupException {
        if (targetInput.uuid().isPresent()) {
            UUID uuid = targetInput.uuid().get();
            Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByUuid(uuid);
            if (profile.isEmpty()) {
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.generate-uuid-not-found",
                        Map.of("uuid", uuid.toString())
                ));
                return null;
            }
            return identityFromProfile(profile.get());
        }

        Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByName(targetInput.text());
        if (profile.isEmpty()) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.generate-name-not-found",
                    Map.of("player", targetInput.text())
            ));
            return null;
        }
        return identityFromProfile(profile.get());
    }

    private void sendGeneratedCode(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            PlayerIdentity identity
    ) {
        GeneratedCode generatedCode = runtime.inviteCodeService().generate(identity.uuidText());
        runtime.lang().send(sender, "admin.generate-success", generatedCodePlaceholders(runtime, identity, generatedCode));
    }

    private Map<String, String> generatedCodePlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            GeneratedCode generatedCode
    ) {
        return Map.of(
                "player", identity.name(),
                "uuid", identity.uuidText(),
                "uuid_source", state(runtime, uuidSourceStateKey(runtime)),
                "code", generatedCode.code(),
                "expire_time", DATE_TIME_FORMATTER.format(generatedCode.expiresAt())
        );
    }

    private Map<String, String> identityPlaceholders(BayMcWhiteListPlugin.RuntimeState runtime, PlayerIdentity identity) {
        return Map.of(
                "player", identity.name(),
                "uuid", identity.uuidText()
        );
    }

    private PlayerIdentity identityFromProfile(MojangProfile profile) {
        return new PlayerIdentity(profile.uuid(), profile.name());
    }

    private @Nullable Player findOnlinePlayer(TargetInput targetInput) {
        if (targetInput.uuid().isPresent()) {
            UUID uuid = targetInput.uuid().get();
            return Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.getUniqueId().equals(uuid))
                    .findFirst()
                    .orElse(null);
        }
        return Bukkit.getPlayerExact(targetInput.text());
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 为状态命令按当前 UUID 来源解析查询目标
     *
     * <p>状态查询允许名称解析, 但进入仓库前仍会落到一个明确 UUID。
     * server UUID 模式下离线名称无法安全推断, 因此会被拒绝
     */
    private LookupTarget resolveStatusTarget(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String input) {
        TargetInput targetInput = parseTargetInput(runtime, sender, input);
        if (targetInput == null) {
            return null;
        }

        if (targetInput.uuid().isPresent()) {
            return new LookupTarget(targetInput.text(), targetInput.uuid().get().toString(), false);
        }

        Player onlinePlayer = findOnlinePlayer(targetInput);
        if (onlinePlayer != null) {
            PlayerIdentity identity = PlayerIdentityResolver.fromPlayer(
                    onlinePlayer,
                    runtime.config().player().uuidSource()
            );
            return new LookupTarget(targetInput.text(), identity.uuidText(), false);
        }

        return switch (runtime.config().player().uuidSource()) {
            case MOJANG -> {
                runtime.lang().send(sender, "admin.status-lookup-name-start", Map.of("player", targetInput.text()));
                yield new LookupTarget(targetInput.text(), null, true);
            }
            case OFFLINE_NAME -> new LookupTarget(
                    targetInput.text(),
                    PlayerIdentityResolver.fromOfflineName(targetInput.text()).uuidText(),
                    false
            );
            case SERVER -> {
                sendServerSourceOfflineNameUnsupported(runtime, sender);
                yield null;
            }
        };
    }

    /**
     * 按当前 UUID 来源解析移除目标
     *
     * <p>移除命令最终只删除解析出的 UUID 记录。
     * Mojang 模式的离线名称会先查正版档案, offline-name 模式才按离线名算法本地计算 UUID
     */
    private LookupTarget resolveRemoveTarget(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender, String input) {
        TargetInput targetInput = parseTargetInput(runtime, sender, input);
        if (targetInput == null) {
            return null;
        }

        if (targetInput.uuid().isPresent()) {
            return new LookupTarget(targetInput.text(), targetInput.uuid().get().toString(), false);
        }

        Player onlinePlayer = findOnlinePlayer(targetInput);
        if (onlinePlayer != null) {
            PlayerIdentity identity = PlayerIdentityResolver.fromPlayer(
                    onlinePlayer,
                    runtime.config().player().uuidSource()
            );
            return new LookupTarget(targetInput.text(), identity.uuidText(), false);
        }

        return switch (runtime.config().player().uuidSource()) {
            case MOJANG -> {
                runtime.lang().send(sender, "admin.remove-lookup-name-start", Map.of("player", targetInput.text()));
                yield new LookupTarget(targetInput.text(), null, true);
            }
            case OFFLINE_NAME -> new LookupTarget(
                    targetInput.text(),
                    PlayerIdentityResolver.fromOfflineName(targetInput.text()).uuidText(),
                    false
            );
            case SERVER -> {
                sendServerSourceOfflineNameUnsupported(runtime, sender);
                yield null;
            }
        };
    }

    private @Nullable LookupTarget resolveMojangRemoveTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            LookupTarget target
    ) throws MojangProfileLookupException {
        if (!target.resolveMojangName()) {
            return target;
        }

        Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByName(target.input());
        if (profile.isEmpty()) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.remove-name-not-found",
                    Map.of("player", target.input())
            ));
            return null;
        }
        return new LookupTarget(target.input(), profile.get().uuid().toString(), false);
    }

    private @Nullable LookupTarget resolveMojangStatusTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            LookupTarget target
    ) throws MojangProfileLookupException {
        if (!target.resolveMojangName()) {
            return target;
        }

        Optional<MojangProfile> profile = runtime.mojangProfileService().lookupByName(target.input());
        if (profile.isEmpty()) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.status-name-not-found",
                    Map.of("player", target.input())
            ));
            return null;
        }
        return new LookupTarget(target.input(), profile.get().uuid().toString(), false);
    }

    private void completeRemoval(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            WhitelistRecord record
    ) {
        if (!runtime.config().remove().kickOnlinePlayer()) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.remove-success",
                    removalPlaceholders(runtime, record)
            ));
            return;
        }
        if (!runtime.config().remove().shouldKickIn(runtime.config().server().mode())) {
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.remove-success-kick-skipped-mode",
                    removalPlaceholders(runtime, record)
            ));
            return;
        }

        runtime.scheduler().runGlobal(() -> {
            Player onlinePlayer = findOnlineRemovedPlayer(record);
            if (onlinePlayer == null) {
                runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                        sender,
                        "admin.remove-success-offline",
                        removalPlaceholders(runtime, record)
                ));
                return;
            }

            Component kickMessage = runtime.lang().joined(
                    "kick.whitelist-removed",
                    removalPlaceholders(runtime, record)
            );
            runtime.scheduler().runForPlayer(onlinePlayer, () -> onlinePlayer.kick(kickMessage));
            runtime.scheduler().runForSender(sender, () -> runtime.lang().send(
                    sender,
                    "admin.remove-success-kicked",
                    removalPlaceholders(runtime, record)
            ));
        });
    }

    private @Nullable Player findOnlineRemovedPlayer(WhitelistRecord record) {
        Optional<UUID> uuid = parseUuid(valueOrEmpty(record.playerUuid()));
        if (uuid.isPresent()) {
            Player player = Bukkit.getPlayer(uuid.get());
            if (player != null) {
                return player;
            }
        }
        String playerName = record.playerName();
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        return Bukkit.getPlayerExact(playerName);
    }

    private Map<String, String> removalPlaceholders(BayMcWhiteListPlugin.RuntimeState runtime, WhitelistRecord record) {
        return Map.of(
                "player", value(runtime, record.playerName()),
                "uuid", value(runtime, record.playerUuid()),
                "server_mode", state(
                        runtime,
                        runtime.config().server().mode() == PluginConfig.ServerMode.LOGIN
                                ? "state.mode-login"
                                : "state.mode-protected"
                )
        );
    }

    /**
     * 只按解析出的标准 UUID 查询仓库; Mojang 名称解析会在进入这里之前完成
     */
    private LookupResult findRecord(BayMcWhiteListPlugin.RuntimeState runtime, LookupTarget target) throws SQLException {
        if (target.playerUuid() == null) {
            return new LookupResult(Optional.empty());
        }
        return new LookupResult(runtime.repository().findByUuid(target.playerUuid()));
    }

    /**
     * 为一条已存储记录发送完整管理员状态视图
     */
    private void sendStatus(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            WhitelistRecord record,
            LookupTarget target
    ) {
        runtime.lang().send(sender, "admin.status-whitelisted", Map.of(
                "player", value(runtime, record.playerName()),
                "uuid", value(runtime, record.playerUuid()),
                "lookup_input", value(runtime, target.input()),
                "lookup_type", statusLookupType(runtime, target),
                "code", value(runtime, record.code()),
                "issue_date", value(runtime, record.issueDate()),
                "used_at", format(runtime, record.usedAt()),
                "source_server", value(runtime, record.sourceServer()),
                "last_seen_at", format(runtime, record.lastSeenAt())
        ));
    }

    private String statusLookupType(BayMcWhiteListPlugin.RuntimeState runtime, LookupTarget target) {
        return state(
                runtime,
                parseUuid(target.input()).isPresent() ? "state.lookup-uuid" : "state.lookup-name"
        );
    }

    private Map<String, String> statusLookupPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            LookupTarget target
    ) {
        return Map.of(
                "player", value(runtime, target.input()),
                "lookup_input", value(runtime, target.input()),
                "lookup_type", statusLookupType(runtime, target)
        );
    }

    /**
     * 在进入仓库调用前拦截数据库未就绪的命令
     */
    private boolean ensureDatabaseReady(BayMcWhiteListPlugin.RuntimeState runtime, CommandSender sender) {
        if (runtime.databaseReady()) {
            return true;
        }
        runtime.lang().send(sender, "database.not-ready");
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
     * 解析 UUID 输入, 避免异常穿透命令处理器
     */
    private static Optional<UUID> parseUuid(String input) {
        return PlayerIdentityResolver.parseUuid(input);
    }

    private @Nullable TargetInput parseTargetInput(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            String rawInput
    ) {
        String input = rawInput == null ? "" : rawInput.trim();
        Optional<UUID> uuid = PlayerIdentityResolver.parseUuid(input);
        if (uuid.isEmpty() && !PlayerIdentityResolver.isValidPlayerName(input)) {
            runtime.lang().send(sender, "common.invalid-player-identifier");
            return null;
        }
        return new TargetInput(input, uuid);
    }

    private void sendServerSourceOfflineNameUnsupported(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender
    ) {
        runtime.lang().send(sender, "admin.server-source-offline-name-unsupported", Map.of(
                "uuid_source", state(runtime, uuidSourceStateKey(runtime))
        ));
    }

    private String uuidSourceStateKey(BayMcWhiteListPlugin.RuntimeState runtime) {
        return PlayerIdentityResolver.uuidSourceLanguageKey(runtime.config().player().uuidSource());
    }

    /**
     * 管理员手动添加命令最终要写入白名单的身份和档案校验状态
     */
    private record AddTarget(PlayerIdentity identity, boolean profileVerified) {
    }

    /**
     * 生成邀请码时已解析出的目标身份及其来源
     */
    private record IdentityTarget(PlayerIdentity identity, TargetSource source) {
    }

    /**
     * 记录目标身份来自在线玩家实体还是本地输入解析
     */
    private enum TargetSource {
        ONLINE,
        LOCAL
    }

    /**
     * 管理员输入的原始目标文本, 以及可用时解析出的 UUID
     */
    private record TargetInput(String text, Optional<UUID> uuid) {
    }

    /**
     * 状态或移除命令的仓库查询目标; resolveMojangName 表示还需要线上档案解析
     */
    private record LookupTarget(String input, String playerUuid, boolean resolveMojangName) {
    }

    /**
     * 仓库查询结果
     */
    private record LookupResult(Optional<WhitelistRecord> record) {
    }
}
