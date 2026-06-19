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

    @Test
    void notFoundReturnsEmptyProfile() throws Exception {
        MojangProfileService service = service(new AtomicReference<>(), 404, "");

        Optional<MojangProfile> profile = service.lookupByName("MissingPlayer");

        assertTrue(profile.isEmpty());
    }

    @Test
    void serverErrorThrowsLookupException() {
        MojangProfileService service = service(new AtomicReference<>(), 503, "service unavailable");

        assertThrows(MojangProfileLookupException.class, () -> service.lookupByName("Notch"));
    }

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

    @Test
    void invalidProfileNameThrowsLookupException() {
        MojangProfileService service = service(
                new AtomicReference<>(),
                200,
                profileJson("Bad-Name", NOTCH_DASHLESS_UUID)
        );

        assertThrows(MojangProfileLookupException.class, () -> service.lookupByName("Notch"));
    }

    @Test
    void invalidLookupNameThrowsBeforeRequest() {
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        MojangProfileService service = service(requestedUri, 200, profileJson("Notch", NOTCH_DASHLESS_UUID));

        assertThrows(MojangProfileLookupException.class, () -> service.lookupByName("Bad-Name"));
        assertEquals(null, requestedUri.get());
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
