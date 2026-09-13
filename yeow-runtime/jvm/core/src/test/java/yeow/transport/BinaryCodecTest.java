package yeow.transport;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trip tests for {@link BinaryCodec} (layout must match binary.c). */
class BinaryCodecTest {

    private static ByteBuffer buf() {
        return ByteBuffer.allocateDirect(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void roundTripsObject() {
        Gson gson = new Gson();
        String json = "{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,"
                + "\"e\":[1,2,3],\"f\":{\"g\":\"h\"},\"n\":3.5,\"u\":\"\\uD83D\\uDE00\"}";
        JsonObject src = gson.fromJson(json, JsonObject.class);
        ByteBuffer b = buf();

        assertTrue(BinaryCodec.encodeBinary(b, "task", src, gson));
        assertEquals("task", BinaryCodec.decodeChannel(b));
        JsonElement back = BinaryCodec.decodeBinary(b);
        assertEquals(src.toString(), back.toString());
    }

    @Test
    void emptyContainers() {
        Gson gson = new Gson();
        JsonObject src = gson.fromJson("{\"ea\":[],\"eo\":{}}", JsonObject.class);
        ByteBuffer b = buf();
        assertTrue(BinaryCodec.encodeBinary(b, "c", src, gson));
        assertEquals(src.toString(), BinaryCodec.decodeBinary(b).toString());
    }

    /**
     * Regression: a bare varint key length collides with T_OBJ_END whenever its low
     * 7 bits equal 9. Covers a 9-byte key (e.g. {@code blockType}), a 137-byte key
     * (varint 0x89 0x01) and a 265-byte key (varint 0x89 0x02).
     */
    @Test
    void keysCollidingWithObjectEndMarker() {
        Gson gson = new Gson();
        JsonObject src = new JsonObject();
        src.addProperty("blockType", "minecraft:air");
        src.addProperty("a".repeat(137), 1);
        src.addProperty("b".repeat(265), 2);
        ByteBuffer b = buf();
        assertTrue(BinaryCodec.encodeBinary(b, "task", src, gson));
        assertEquals("task", BinaryCodec.decodeChannel(b));
        assertEquals(src.toString(), BinaryCodec.decodeBinary(b).toString());
    }

    @Test
    void overflowFallsBack() {
        Gson gson = new Gson();
        JsonObject src = new JsonObject();
        src.addProperty("s", "x".repeat(40000));
        assertFalse(BinaryCodec.encodeBinary(buf(), "c", src, gson));
    }

    @Test
    void jsonModeRoundTrip() {
        ByteBuffer b = buf();
        String json = "{\"t\":\"cb\",\"p\":\"1\",\"r\":42}";
        assertTrue(BinaryCodec.encodeJson(b, json));
        assertEquals(BinaryCodec.MODE_JSON, BinaryCodec.mode(b));
        assertEquals(json, BinaryCodec.decodeJson(b));
    }

    /**
     * Downlink producer shape (cbMessageObject-ish): a Map with a null value and no channel.
     * Map.of would NPE on the null; Gson omits null map values — identical on the JSON fallback
     * path, so binary/JSON stay byte-for-byte consistent.
     */
    @Test
    void nullValueRawObject() {
        Gson gson = new Gson();
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("t", "cb");
        m.put("p", "1");
        m.put("r", null);
        ByteBuffer b = buf();
        assertTrue(BinaryCodec.encodeBinary(b, null, m, gson));
        assertEquals("", BinaryCodec.decodeChannel(b));
        assertEquals("{\"t\":\"cb\",\"p\":\"1\"}", BinaryCodec.decodeBinary(b).toString());
    }
}
