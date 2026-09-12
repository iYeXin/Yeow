package yeow;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** yeow.json native 元数据解析：serviceId + 打包后路径 → SHA-256。 */
class NativeManifestTest {

    @Test
    void parseAndQuery() {
        var json = JsonParser.parseString("""
            [
              { "serviceId": "image-svc", "files": [ { "assets/a1b2/image.exe": "deadbeef" } ], "source": "https://x" },
              { "serviceId": "audio-svc", "files": [ { "assets/c3d4/audio.exe": "cafe" } ] }
            ]
            """);
        var m = NativeManifest.parse(json);
        assertFalse(m.isEmpty());
        assertTrue(m.declaresService("image-svc"));
        assertTrue(m.declaresService("audio-svc"));
        assertFalse(m.declaresService("other"));
        assertEquals("deadbeef", m.hashFor("assets/a1b2/image.exe"));
        assertNull(m.hashFor("assets/a1b2/missing.exe"));
        assertEquals(2, m.files().size());
        assertEquals(2, m.serviceIds().size());
    }

    @Test
    void missingOrInvalidYieldsEmpty() {
        assertTrue(NativeManifest.parse(null).isEmpty());
        assertTrue(NativeManifest.parse(JsonParser.parseString("{}")).isEmpty());
        assertTrue(NativeManifest.parse(JsonParser.parseString("[1, 2]")).isEmpty());
    }
}
