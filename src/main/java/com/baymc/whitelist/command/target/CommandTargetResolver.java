package com.baymc.whitelist.command.target;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.CommandMessages;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.identity.PlayerIdentityResolver;
import com.baymc.whitelist.mojang.MojangProfile;
import com.baymc.whitelist.mojang.MojangProfileLookupException;
import com.baymc.whitelist.storage.WhitelistRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * /baymcwhitelist 子命令共用的玩家目标解析器
 *
 * <p>本类负责把管理员输入的玩家名或 UUID 转换成明确的 PlayerIdentity
 * 或 LookupTarget; 仓库查询和删除始终基于 UUID, 避免玩家改名后误查或误删
 */
public final class CommandTargetResolver {
    private final CommandMessages messages;

    public CommandTargetResolver(CommandMessages messages) {
        this.messages = messages;
    }

    public @Nullable AddTarget resolveLocalAddTarget(
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

    public @Nullable AddTarget resolveMojangAddTarget(
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

    public @Nullable IdentityTarget resolveLocalGenerationTarget(
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

    public @Nullable PlayerIdentity resolveMojangGenerationIdentity(
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

    public @Nullable LookupTarget resolveStatusTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            String input
    ) {
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

    public @Nullable LookupTarget resolveRemoveTarget(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            String input
    ) {
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

    public @Nullable LookupTarget resolveMojangRemoveTarget(
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

    public @Nullable LookupTarget resolveMojangStatusTarget(
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

    public @Nullable Player findOnlineRemovedPlayer(WhitelistRecord record) {
        // 根据已存储记录查找本服在线玩家, 用于移除白名单后的可选踢出
        Optional<UUID> uuid = PlayerIdentityResolver.parseUuid(valueOrEmpty(record.playerUuid()));
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

    public @Nullable TargetInput parseTargetInput(
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

    public @Nullable Player findOnlinePlayer(TargetInput targetInput) {
        if (targetInput.uuid().isPresent()) {
            UUID uuid = targetInput.uuid().get();
            return Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.getUniqueId().equals(uuid))
                    .findFirst()
                    .orElse(null);
        }
        return Bukkit.getPlayerExact(targetInput.text());
    }

    private AddTarget addTargetFromProfile(MojangProfile profile) {
        return new AddTarget(
                identityFromProfile(profile),
                true
        );
    }

    private PlayerIdentity identityFromProfile(MojangProfile profile) {
        return new PlayerIdentity(profile.uuid(), profile.name());
    }

    private void sendServerSourceOfflineNameUnsupported(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender
    ) {
        runtime.lang().send(sender, "admin.server-source-offline-name-unsupported", Map.of(
                "uuid_source", messages.state(runtime, messages.uuidSourceStateKey(runtime))
        ));
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
