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

    /** UTF-8 字符串 → 字节。 */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 字节 → UTF-8 字符串（非法序列替换为 U+FFFD，不抛错）。 */
    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
