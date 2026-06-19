package com.baymc.whitelist.mojang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 通过 Mojang / Minecraft Services API 查询正版玩家档案
 *
 * <p>外部响应只在通过 UUID 格式和玩家名格式校验后才会返回给命令层。
 * 查询失败和响应格式异常会转为 MojangProfileLookupException, 由命令层给管理员明确反馈。
 */
public final class MojangProfileService {
    private static final String NAME_LOOKUP_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String UUID_LOOKUP_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern DASHLESS_UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final ProfileTransport transport;

    /**
     * 使用 Java 内置 HttpClient 创建线上查询服务
     */
    public MojangProfileService() {
        this(new JavaNetProfileTransport(HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()));
    }

    MojangProfileService(ProfileTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 按当前正版玩家名查询 UUID 和规范玩家名
     *
     * @param playerName 需要查询的正版玩家名
     * @return 查询到的 Mojang 档案, 玩家不存在时为空
     * @throws MojangProfileLookupException 当网络请求, 状态码或响应解析失败时抛出
     */
    public Optional<MojangProfile> lookupByName(String playerName) throws MojangProfileLookupException {
        if (playerName == null || !PLAYER_NAME_PATTERN.matcher(playerName).matches()) {
            throw new MojangProfileLookupException("Mojang profile lookup name is invalid");
        }
        String encodedName = URLEncoder.encode(playerName, StandardCharsets.UTF_8).replace("+", "%20");
        return requestProfile(URI.create(NAME_LOOKUP_URL + encodedName), Optional.empty());
    }

    /**
     * 按 UUID 校验 Mojang 档案是否存在, 并返回对应规范玩家名
     *
     * @param uuid 需要查询的正版 UUID
     * @return 查询到且 UUID 匹配的 Mojang 档案, 不存在或不匹配时为空
     * @throws MojangProfileLookupException 当网络请求, 状态码或响应解析失败时抛出
     */
    public Optional<MojangProfile> lookupByUuid(UUID uuid) throws MojangProfileLookupException {
        return requestProfile(URI.create(UUID_LOOKUP_URL + withoutDashes(uuid)), Optional.of(uuid));
    }

    private Optional<MojangProfile> requestProfile(URI uri, Optional<UUID> expectedUuid) throws MojangProfileLookupException {
        ProfileResponse response;
        try {
            response = transport.get(uri);
        } catch (IOException exception) {
            throw new MojangProfileLookupException("Unable to query Mojang profile", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MojangProfileLookupException("Interrupted while querying Mojang profile", exception);
        }

        int statusCode = response.statusCode();
        if (statusCode == 204 || statusCode == 404) {
            return Optional.empty();
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw new MojangProfileLookupException("Unexpected Mojang profile status: " + statusCode);
        }

        MojangProfile profile = parseProfile(response.body());
        if (expectedUuid.isPresent() && !expectedUuid.get().equals(profile.uuid())) {
            return Optional.empty();
        }
        return Optional.of(profile);
    }

    private MojangProfile parseProfile(String body) throws MojangProfileLookupException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new MojangProfileLookupException("Mojang profile response is not an object");
            }
            JsonObject object = parsed.getAsJsonObject();
            String id = requiredString(object, "id");
            String name = requiredPlayerName(object);
            return new MojangProfile(uuidFromDashless(id), name);
        } catch (JsonSyntaxException | IllegalArgumentException exception) {
            throw new MojangProfileLookupException("Unable to parse Mojang profile response", exception);
        }
    }

    private static String requiredPlayerName(JsonObject object) throws MojangProfileLookupException {
        String name = requiredString(object, "name");
        if (!PLAYER_NAME_PATTERN.matcher(name).matches()) {
            throw new MojangProfileLookupException("Mojang profile response contains invalid player name");
        }
        return name;
    }

    private static String requiredString(JsonObject object, String key) throws MojangProfileLookupException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new MojangProfileLookupException("Mojang profile response is missing field: " + key);
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new MojangProfileLookupException("Mojang profile response field is blank: " + key);
        }
        return value;
    }

    static UUID uuidFromDashless(String rawUuid) {
        String normalized = rawUuid.replace("-", "");
        if (!DASHLESS_UUID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid Mojang UUID: " + rawUuid);
        }
        return UUID.fromString(normalized.replaceFirst(
                "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5"
        ));
    }

    private static String withoutDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    interface ProfileTransport {
        ProfileResponse get(URI uri) throws IOException, InterruptedException;
    }

    record ProfileResponse(int statusCode, String body) {
    }

    private record JavaNetProfileTransport(HttpClient client) implements ProfileTransport {
        /**
         * {@inheritDoc}
         */
        @Override
        public ProfileResponse get(URI uri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ProfileResponse(response.statusCode(), response.body());
        }
    }
}
