package yeow.transport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Yeow binary transport codec (Java side), the mirror of
 * {@code quickjs-wrapper/native/src/binary.c}.
 *
 * <p>Layout (little-endian, no alignment, no hashing, raw UTF-8 keys):
 * <pre>
 *   header: u32 magic | u16 version | u8 mode | u8 reserved | u32 bodyLen
 *   mode 0: u16 channelLen | channel | value
 *   mode 1: varint len | utf8
 *   value : tag [payload]
 *     T_NULL/T_FALSE/T_TRUE | T_I32(4B) | T_I64(8B) | T_F64(8B)
 *     T_STR/T_BYTES: varint len | bytes
 *     T_OBJ (T_KEY varint len | key bytes | value)* T_OBJ_END
 *     T_ARR value*       T_ARR_END
 * </pre>
 *
 * Objects/arrays use explicit start/end markers (no counts); lengths are LEB128
 * varints. Object entries carry an explicit {@code T_KEY} tag because a bare
 * varint key length would be ambiguous with the {@code T_OBJ_END} marker when
 * its low 7 bits equal 9 (e.g. a 9-byte key such as "blockType"). The shared
 * buffer is touched only by the JS thread, so no synchronization is needed.
 */
public final class BinaryCodec {

    /**
     * Transport switch — the single source of truth, injected into JS as {@code globalThis.$binary}.
     *
     * <p>{@code false} (current default): the runtime transports messages as JSON only. The binary
     * codec below is retained, tested and isolated — nothing calls it. Set to {@code true} to
     * re-enable the resident-buffer transport (see {@code docs/.../advanced/binary-transport.md}).
     */
    public static final boolean ENABLED = false;

    public static final int MAGIC = 0x59454F42;
    public static final int VERSION = 2;
    public static final int MODE_BIN = 0;
    public static final int MODE_JSON = 1;
    public static final int MODE_NULL = 2;
    public static final int HEADER = 12;

    private static final int T_NULL = 0, T_FALSE = 1, T_TRUE = 2, T_I32 = 3, T_I64 = 4, T_F64 = 5;
    private static final int T_STR = 6, T_BYTES = 7, T_OBJ = 8, T_OBJ_END = 9, T_ARR = 10, T_ARR_END = 11;
    private static final int T_KEY = 12;

    private static final JsonPrimitive TRUE = new JsonPrimitive(true);
    private static final JsonPrimitive FALSE = new JsonPrimitive(false);

    /**
     * Reusable per-thread scratch for {@code byte[] -> String} decoding. The transport is
     * JS-thread confined, and each key/value is copied out immediately by the String/Base64
     * constructors, so one buffer can serve every node without keeping references. ThreadLocal
     * keeps the codec safe if called from other threads (e.g. tests).
     */
    private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[1024]);

    private static byte[] scratch(int n) {
        byte[] a = SCRATCH.get();
        if (a.length < n) {
            a = new byte[Math.max(n, a.length * 2)];
            SCRATCH.set(a);
        }
        return a;
    }

    private BinaryCodec() {}

    // ── encode ──────────────────────────────────────────────────────

    /** Encodes {@code value} (normalized via {@code gson}) as mode 0. Returns false on overflow/unsupported. */
    public static boolean encodeBinary(ByteBuffer b, String channel, Object value, Gson gson) {
        JsonElement e = (value instanceof JsonElement je) ? je : gson.toJsonTree(value);
        try {
            b.clear();
            b.position(HEADER);
            byte[] ch = channel == null ? new byte[0] : channel.getBytes(StandardCharsets.UTF_8);
            b.putShort((short) ch.length);
            b.put(ch);
            encValue(b, e);
            putHeader(b, MODE_BIN, b.position() - HEADER);
            return true;
        } catch (RuntimeException overflow) {
            return false;
        }
    }

    /** Encodes a JSON string as mode 1. Returns false if it does not fit. */
    public static boolean encodeJson(ByteBuffer b, String json) {
        byte[] bytes = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        b.clear();
        b.position(HEADER);
        try {
            putVarint(b, bytes.length);
            b.put(bytes);
        } catch (RuntimeException overflow) {
            return false;
        }
        putHeader(b, MODE_JSON, b.position() - HEADER);
        return true;
    }

    private static void putHeader(ByteBuffer b, int mode, int body) {
        b.putInt(0, MAGIC);
        b.putShort(4, (short) VERSION);
        b.put(6, (byte) mode);
        b.put(7, (byte) 0);
        b.putInt(8, body);
    }

    private static void putVarint(ByteBuffer b, long v) {
        while ((v & ~0x7FL) != 0) {
            b.put((byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        b.put((byte) v);
    }

    private static void encValue(ByteBuffer b, JsonElement e) {
        if (e == null || e.isJsonNull()) {
            b.put((byte) T_NULL);
            return;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                b.put((byte) (p.getAsBoolean() ? T_TRUE : T_FALSE));
                return;
            }
            if (p.isNumber()) {
                Number n = p.getAsNumber();
                if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
                    long v = n.longValue();
                    if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                        b.put((byte) T_I32);
                        b.putInt((int) v);
                    } else {
                        b.put((byte) T_I64);
                        b.putLong(v);
                    }
                    return;
                }
                if (!(n instanceof Double) && !(n instanceof Float)) {
                    // LazilyParsedNumber / BigInteger / BigDecimal: decide integralness from the literal
                    String s = p.getAsString();
                    if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                        try {
                            long v = Long.parseLong(s);
                            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                                b.put((byte) T_I32);
                                b.putInt((int) v);
                            } else {
                                b.put((byte) T_I64);
                                b.putLong(v);
                            }
                            return;
                        } catch (NumberFormatException ignore) {
                            // fall through to double
                        }
                    }
                }
                b.put((byte) T_F64);
                b.putDouble(p.getAsDouble());
                return;
            }
            byte[] sb = p.getAsString().getBytes(StandardCharsets.UTF_8);
            b.put((byte) T_STR);
            putVarint(b, sb.length);
            b.put(sb);
            return;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            b.put((byte) T_ARR);
            for (JsonElement c : a) encValue(b, c);
            b.put((byte) T_ARR_END);
            return;
        }
        JsonObject o = e.getAsJsonObject();
        b.put((byte) T_OBJ);
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            byte[] kb = en.getKey().getBytes(StandardCharsets.UTF_8);
            b.put((byte) T_KEY);
            putVarint(b, kb.length);
            b.put(kb);
            encValue(b, en.getValue());
        }
        b.put((byte) T_OBJ_END);
    }

    // ── decode ──────────────────────────────────────────────────────

    public static int mode(ByteBuffer b) {
        if (b.capacity() < HEADER) return MODE_NULL;
        if (b.getInt(0) != MAGIC) return MODE_NULL;
        return b.get(6) & 0xff;
    }

    public static String decodeChannel(ByteBuffer b) {
        if (mode(b) != MODE_BIN) return null;
        b.position(HEADER);
        int n = b.getShort() & 0xffff;
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    /** Decodes mode 0 into a JsonElement (object/array/primitive), or null if malformed. */
    public static JsonElement decodeBinary(ByteBuffer b) {
        if (mode(b) != MODE_BIN) return null;
        try {
            b.position(HEADER);
            int n = b.getShort() & 0xffff;
            b.position(b.position() + n);
            return decValue(b);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public static String decodeJson(ByteBuffer b) {
        if (mode(b) != MODE_JSON) return null;
        try {
            b.position(HEADER);
            int n = (int) getVarint(b);
            byte[] s = new byte[n];
            b.get(s);
            return new String(s, StandardCharsets.UTF_8);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static long getVarint(ByteBuffer b) {
        long v = 0;
        int shift = 0;
        for (;;) {
            int c = b.get() & 0xff;
            v |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
            if (shift > 63) throw new IllegalStateException("varint too long");
        }
        return v;
    }

    private static int peek(ByteBuffer b) {
        return b.get(b.position()) & 0xff;
    }

    private static JsonElement decValue(ByteBuffer b) {
        int t = b.get() & 0xff;
        switch (t) {
            case T_NULL: return JsonNull.INSTANCE;
            case T_FALSE: return FALSE;
            case T_TRUE: return TRUE;
            case T_I32: return new JsonPrimitive(b.getInt());
            case T_I64: return new JsonPrimitive(b.getLong());
            case T_F64: return new JsonPrimitive(b.getDouble());
            case T_STR: {
                int n = (int) getVarint(b);
                byte[] s = scratch(n);
                b.get(s, 0, n);
                return new JsonPrimitive(new String(s, 0, n, StandardCharsets.UTF_8));
            }
            case T_BYTES: {
                int n = (int) getVarint(b);
                byte[] s = new byte[n];
                b.get(s);
                return new JsonPrimitive(Base64.getEncoder().encodeToString(s));
            }
            case T_OBJ: {
                JsonObject o = new JsonObject();
                for (;;) {
                    int nt = peek(b);
                    if (nt == T_OBJ_END) {
                        b.get(); // T_OBJ_END
                        return o;
                    }
                    if (nt != T_KEY) throw new IllegalStateException("object entry: expected T_KEY, got " + nt);
                    b.get(); // T_KEY
                    int kl = (int) getVarint(b);
                    byte[] kb = scratch(kl);
                    b.get(kb, 0, kl);
                    // NB: the key String is built before decValue() so the scratch may be reused.
                    o.add(new String(kb, 0, kl, StandardCharsets.UTF_8), decValue(b));
                }
            }
            case T_ARR: {
                JsonArray a = new JsonArray();
                while (peek(b) != T_ARR_END) a.add(decValue(b));
                b.get(); // T_ARR_END
                return a;
            }
            default:
                throw new IllegalStateException("unknown binary tag " + t);
        }
    }
}
