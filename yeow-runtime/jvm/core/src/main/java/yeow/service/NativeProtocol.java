package yeow.service;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Native Service TCP 帧协议（v2，**破坏性替换**旧的 JSON line）：
 *
 * <pre>
 *   [u32 headerLen][header JSON (UTF-8)] [u32 chunkLen][chunk bytes] ... [u32 0]
 * </pre>
 *
 * <ul>
 *   <li><b>header</b> 是 JSON：承载类型与元数据（`type` / `id` / `path` /
 *       `contentType` / `eventPath` / `reason` 等）。</li>
 *   <li><b>body</b> 是 0 结尾的分块序列：可承载 raw 二进制（无 base64），
 *       且支持流式发送（发送方无需预先知道总长）；空 body 即单个 0 长度块。</li>
 *   <li>长度一律为大端 u32；读取受 {@link #MAX_HEADER_BYTES} /
 *       {@link #MAX_BODY_BYTES} 限制。</li>
 * </ul>
 *
 * 运行时侧发送的 body 通常已知（来自 JS，已 base64 承载），写为单块即可；
 * 该实现按 256 KiB 切片以控制单次分配。
 */
final class NativeProtocol {
    static final int MAX_HEADER_BYTES = 64 * 1024;
    static final int MAX_BODY_BYTES = 256 * 1024 * 1024;
    private static final int CHUNK = 256 * 1024;

    private NativeProtocol() {}

    /** 一条消息：header JSON 文本 + 聚合后的 body 字节。 */
    record Message(String header, byte[] body) {}

    /** 写入一条完整消息（header JSON + body 字节）。调用方负责并发同步。 */
    static void write(OutputStream out, String headerJson, byte[] body) throws IOException {
        byte[] header = headerJson.getBytes(StandardCharsets.UTF_8);
        out.write(intToBytes(header.length));
        out.write(header);
        if (body != null && body.length > 0) {
            int off = 0;
            while (off < body.length) {
                int n = Math.min(CHUNK, body.length - off);
                out.write(intToBytes(n));
                out.write(body, off, n);
                off += n;
            }
        }
        out.write(intToBytes(0));
        out.flush();
    }

    /** 写入一条消息，body 从 stream 流式读取（读到 EOF 为止）。调用方负责并发同步。 */
    static void write(OutputStream out, String headerJson, InputStream bodyStream) throws IOException {
        byte[] header = headerJson.getBytes(StandardCharsets.UTF_8);
        out.write(intToBytes(header.length));
        out.write(header);
        byte[] buf = new byte[CHUNK];
        int n;
        while ((n = bodyStream.read(buf)) > 0) {
            out.write(intToBytes(n));
            out.write(buf, 0, n);
        }
        out.write(intToBytes(0));
        out.flush();
    }

    /** 读取一条消息（header + 聚合 body）。流正常结束（EOF）返回 null。 */
    static Message read(InputStream in) throws IOException {
        int headerLen = readInt(in);
        if (headerLen < 0) return null; // clean EOF
        if (headerLen > MAX_HEADER_BYTES) throw new IOException("header too large: " + headerLen);
        String header = new String(readN(in, headerLen), StandardCharsets.UTF_8);

        var body = new ByteArrayOutputStream();
        while (true) {
            int len = readInt(in);
            if (len < 0) throw new EOFException("truncated body (missing terminator)");
            if (len == 0) break;
            if (len > MAX_BODY_BYTES || body.size() + (long) len > MAX_BODY_BYTES) {
                throw new IOException("body too large (> " + MAX_BODY_BYTES + " bytes)");
            }
            body.write(readN(in, len));
        }
        return new Message(header, body.toByteArray());
    }

    private static byte[] intToBytes(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    /** 读 4 字节大端 u32；若一个字节都没读到（干净 EOF）返回 -1。 */
    static int readInt(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return -1;
        int b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b1 | b2 | b3) < 0) throw new EOFException("truncated length field");
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("truncated data (" + off + "/" + n + ")");
            off += r;
        }
        return buf;
    }
}
