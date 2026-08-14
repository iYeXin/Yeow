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

    // ── 流式 gzip（分块压缩/解压往返）──────────────────────────────

    private static byte[] repeat(String s, int n) {
        var sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void gzipStreamChunkedRoundTrip() throws IOException {
        var data = repeat("compress me 压缩数据 ", 20000); // ~400 KiB 可压缩
        // 压缩：分块写入（64 KiB/块）
        var c = new GzipCompressor(6);
        var packed = new java.io.ByteArrayOutputStream();
        for (int off = 0; off < data.length; off += 64 * 1024) {
            packed.write(c.write(java.util.Arrays.copyOfRange(data, off, Math.min(data.length, off + 64 * 1024))));
        }
        packed.write(c.finish());
        c.close();
        // 解压：分块喂入（32 KiB/块）
        var compressed = packed.toByteArray();
        var d = new GzipDecompressor();
        var out = new java.io.ByteArrayOutputStream();
        for (int off = 0; off < compressed.length; off += 32 * 1024) {
            out.write(d.write(java.util.Arrays.copyOfRange(compressed, off, Math.min(compressed.length, off + 32 * 1024))));
        }
        out.write(d.finish());
        d.close();
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    void gzipStreamSingleChunkMatchesOneShot() throws IOException {
        var data = repeat("hello stream 流式 ", 5000);
        // 分块（单块 + finish）结果应与一次性压缩完全一致（同一 Deflater 配置）
        var streamed = new java.io.ByteArrayOutputStream();
        var c = new GzipCompressor(-1);
        streamed.write(c.write(data));
        streamed.write(c.finish());
        c.close();
        var oneShot = UtilCodec.gzip(data, -1);
        assertArrayEquals(oneShot, streamed.toByteArray());
        // 解压一致（write 输出 + finish 剩余拼接）
        var d = new GzipDecompressor();
        var dec = new java.io.ByteArrayOutputStream();
        dec.write(d.write(oneShot));
        dec.write(d.finish());
        d.close();
        assertArrayEquals(data, dec.toByteArray());
    }

    @Test
    void gzipStreamEmptyInput() throws IOException {
        var c = new GzipCompressor(6);
        var packed = c.finish(); // 无输入直接结束（GZIP 头 + 尾）
        c.close();
        var d = new GzipDecompressor();
        d.write(packed);
        assertArrayEquals(new byte[0], d.finish());
        d.close();
    }

    @Test
    void gzipStreamInterleavedEmptyWrites() throws IOException {
        var data = repeat("interleaved ", 3000);
        var c = new GzipCompressor(6);
        var packed = new java.io.ByteArrayOutputStream();
        packed.write(c.write(new byte[0]));       // 空块
        for (int off = 0; off < data.length; off += 4096) {
            packed.write(c.write(java.util.Arrays.copyOfRange(data, off, Math.min(data.length, off + 4096))));
            packed.write(c.write(new byte[0]));   // 穿插空块
        }
        packed.write(c.finish());
        c.close();
        var d = new GzipDecompressor();
        var out = new java.io.ByteArrayOutputStream();
        var compressed = packed.toByteArray();
        for (int off = 0; off < compressed.length; off += 2048) {
            out.write(d.write(java.util.Arrays.copyOfRange(compressed, off, Math.min(compressed.length, off + 2048))));
        }
        out.write(d.finish());
        d.close();
        assertArrayEquals(data, out.toByteArray());
    }
}
