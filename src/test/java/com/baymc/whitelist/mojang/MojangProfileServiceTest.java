package com.baymc.whitelist.mojang;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对 Mojang 档案查询服务的响应解析和状态码处理测试
 */
class MojangProfileServiceTest {
    private static final String NOTCH_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String NOTCH_DASHLESS_UUID = "069a79f444e94726a5befca90e38aaf5";

    /**
     * 玩家名查询成功时应解析 UUID 和 Mojang 返回的规范玩家名
     */
    @Test
    void lookupByNameParsesProfile() throws Exception {
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        MojangProfileService service = service(requestedUri, 200, profileJson("Notch", NOTCH_DASHLESS_UUID));

        Optional<MojangProfile> profile = service.lookupByName("Notch");

        assertTrue(profile.isPresent());
        assertEquals(UUID.fromString(NOTCH_UUID), profile.get().uuid());
        assertEquals("Notch", profile.get().name());
        assertEquals(
                URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/Notch"),
                requestedUri.get()
        );
    }

    /**
     * UUID 查询成功时应使用 sessionserver profile 接口并解析玩家名
     */
    @Test
    void lookupByUuidParsesProfile() throws Exception {
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        MojangProfileService service = service(requestedUri, 200, profileJson("Notch", NOTCH_DASHLESS_UUID));

        Optional<MojangProfile> profile = service.lookupByUuid(UUID.fromString(NOTCH_UUID));

        assertTrue(profile.isPresent());
        assertEquals("Notch", profile.get().name());
        assertEquals(
                URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + NOTCH_DASHLESS_UUID),
                requestedUri.get()
        );
    }

    /**
     * Mojang 返回未找到时应转换为空结果, 由命令层展示未找到提示
     */
    @Test
    void notFoundReturnsEmptyProfile() throws Exception {
        MojangProfileService service = service(new AtomicReference<>(), 404, "");

        Optional<MojangProfile> profile = service.lookupByName("MissingPlayer");

        assertTrue(profile.isEmpty());
    }

    /**
     * 非成功且非未找到状态码应作为查询失败处理
     */
    @Test
    void serverErrorThrowsLookupException() {
        MojangProfileService service = service(new AtomicReference<>(), 503, "service unavailable");

        assertThrows(MojangProfileLookupException.class, () -> service.lookupByName("Notch"));
    }

    /**
     * UUID 查询返回的档案 ID 与输入不一致时应拒绝结果
     */
    @Test
    void mismatchedUuidReturnsEmptyProfile() throws Exception {
        MojangProfileService service = service(
                new AtomicReference<>(),
                200,
                profileJson("Steve", "8667ba71b85a4004af54457a9734eed7")
        );

        Optional<MojangProfile> profile = service.lookupByUuid(UUID.fromString(NOTCH_UUID));

        assertTrue(profile.isEmpty());
    }

    /**
     * Mojang 响应里的玩家名仍然属于外部输入, 写库前必须符合插件支持的玩家名边界
     */
    @Test
    void invalidProfileNameThrowsLookupException() {
        MojangProfileService service = service(
                new AtomicReference<>(),
                200,
                profileJson("Bad-Name", NOTCH_DASHLESS_UUID)
        );

        assertThrows(MojangProfileLookupException.class, () -> service.lookupByName("Notch"));
    }

    private static MojangProfileService service(AtomicReference<URI> requestedUri, int statusCode, String body) {
        return new MojangProfileService(uri -> {
            requestedUri.set(uri);
            return new MojangProfileService.ProfileResponse(statusCode, body);
        });
    }

    private static String profileJson(String name, String uuid) {
        return """
                {
                  "id": "%s",
                  "name": "%s"
                }
                """.formatted(uuid, name);
    }
}
