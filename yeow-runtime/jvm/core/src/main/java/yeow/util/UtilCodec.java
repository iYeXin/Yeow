package yeow.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * util 通道底层编解码（平台无关，纯 JDK，静态可单测）。
 *
 * 字节数据在通道协议上以 base64 字符串承载（JS 侧引擎原生
 * `Uint8Array.toBase64()/fromBase64()` 负责转换，API 层不暴露 base64）；
 * encode/decode 的语义是 **buffer ↔ UTF-8 字符串** 转换，base64 只是承载形式。
 *
 * 无流式接口（一次性整体处理）；输入大小上限由通道层（PluginThread）校验。
 */
public final class UtilCodec {
    private UtilCodec() {}

    /**
     * gzip 压缩。level：0-9（0=仅存储，9=最大压缩），-1=Deflater 默认级别。
     *
     * @throws IOException 压缩失败
     */
    public static byte[] gzip(byte[] in, int level) throws IOException {
        var bos = new ByteArrayOutputStream(Math.max(64, in.length / 2));
        try (var gz = new GZIPOutputStream(bos) {
            { def.setLevel(level); }
        }) {
            gz.write(in);
        }
        return bos.toByteArray();
    }

    /**
     * gzip 解压（带输出上限，防压缩炸弹）。
     *
     * @param maxOutBytes 解压输出上限，超出抛 IOException
     * @throws IOException 非 GZIP 数据 / CRC 校验失败 / 输出超限
     */
    public static byte[] gunzip(byte[] in, int maxOutBytes) throws IOException {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(in));
             var bos = new ByteArrayOutputStream(Math.min(Math.max(64, in.length * 4), maxOutBytes))) {
            var buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) != -1) {
                if (bos.size() + n > maxOutBytes)
                    throw new IOException("gunzip output exceeds " + maxOutBytes + " bytes");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 原始 deflate 压缩（无 GZIP 头/尾/CRC；对应 {@link java.util.zip.Deflater} nowrap）。
     * level：0-9，-1 = 默认级别。
     */
    public static byte[] deflate(byte[] in, int level) throws IOException {
        var def = new java.util.zip.Deflater(level, true); // nowrap
        try {
            def.setInput(in);
            def.finish();
            var out = new java.io.ByteArrayOutputStream(Math.max(64, in.length / 2));
            var buf = new byte[8192];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n == 0) break; // 防御
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            def.end();
        }
    }

    /**
     * 原始 deflate 解压（无 GZIP 头/尾/CRC 校验；对应 {@link java.util.zip.Inflater} nowrap）。
     * 输出上限 maxOutBytes（防压缩炸弹）；deflate 流无完整性校验——截断静默结束，调用方负责完整性。
     */
    public static byte[] inflate(byte[] in, int maxOutBytes) throws IOException {
        var inf = new java.util.zip.Inflater(true); // nowrap
        try {
            inf.setInput(in);
            var out = new java.io.ByteArrayOutputStream(Math.min(Math.max(64, in.length * 4), maxOutBytes));
            var buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.finished() || inf.needsInput()) break; // 输入耗尽（截断/正常结束）
                    if (inf.needsDictionary()) throw new IOException("deflate needs dictionary");
                    break;
                }
                if (out.size() + n > maxOutBytes) throw new IOException("inflate output exceeds " + maxOutBytes + " bytes");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("invalid deflate data: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }

    /** UTF-8 字符串 → 字节。 */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 字节 → UTF-8 字符串（非法序列替换为 U+FFFD，不抛错）。 */
    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
