package yeow.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** UtilCodec：gzip 往返 / UTF-8 转换 / 边界与错误。 */
class UtilCodecTest {

    @Test
    void gzipRoundTrip() throws IOException {
        var data = "hello yeow 你好 🌍".repeat(100).getBytes(StandardCharsets.UTF_8);
        var compressed = UtilCodec.gzip(data, 6);
        assertTrue(compressed.length < data.length, "compressible data must shrink");
        assertArrayEquals(data, UtilCodec.gunzip(compressed, Integer.MAX_VALUE));
    }

    @Test
    void gzipLevels() throws IOException {
        var data = new byte[4096];
        new Random(42).nextBytes(data); // 随机数据：各级别均可往返
        for (int level = 0; level <= 9; level++) {
            var compressed = UtilCodec.gzip(data, level);
            assertArrayEquals(data, UtilCodec.gunzip(compressed, Integer.MAX_VALUE), "level=" + level);
        }
    }

    @Test
    void gzipEmptyInput() throws IOException {
        var compressed = UtilCodec.gzip(new byte[0], 6);
        assertArrayEquals(new byte[0], UtilCodec.gunzip(compressed, Integer.MAX_VALUE));
    }

    @Test
    void gzipBadMagicFails() {
        assertThrows(IOException.class, () -> UtilCodec.gunzip("not gzip".getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE));
    }

    @Test
    void gunzipOutputLimit() throws IOException {
        var data = new byte[512 * 1024];
        new Random(7).nextBytes(data);
        var compressed = UtilCodec.gzip(data, 1);
        // 输出上限小于实际解压大小 → 报错
        assertThrows(IOException.class, () -> UtilCodec.gunzip(compressed, data.length / 2));
        // 上限足够 → 正常
        assertArrayEquals(data, UtilCodec.gunzip(compressed, data.length));
    }

    @Test
    void utf8RoundTrip() {
        var text = "中文 & ascii 😀 \u0000 边界";
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), UtilCodec.utf8(text));
        assertEquals(text, UtilCodec.utf8(UtilCodec.utf8(text)));
    }

    @Test
    void utf8InvalidSequenceReplacedNotThrown() {
        // 0xFF 0xFE 不是合法 UTF-8 序列 → 替换为 U+FFFD，不抛错
        var s = UtilCodec.utf8(new byte[]{ (byte) 0xFF, (byte) 0xFE, 'a' });
        assertTrue(s.contains("\uFFFD"));
    }

    @Test
    void utf8Empty() {
        assertArrayEquals(new byte[0], UtilCodec.utf8(""));
        assertEquals("", UtilCodec.utf8(new byte[0]));
    }
}
