package yeow.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * gzip 分块压缩器（流式管道节点）：`write(chunk)` 返回该块产生的压缩输出
 * （可能为空——deflater 内部窗口未满时输出延迟到后续块/finish），
 * `finish()` 返回剩余输出（含 GZIP 尾）。
 *
 * 线程模型：单线程使用（每块一次显式响应——背压基于调用方等待返回值）。
 * 非 syncFlush：分块 write+finish 的拼接输出与一次性 gzip **字节级一致**
 * （syncFlush 会在块边界插入 7 字节 flush marker，破坏该一致性）。
 * 建议块大小 ≥256 KiB（deflater 窗口 32 KiB，块过小 write 可能返回空）。
 */
public final class GzipCompressor implements AutoCloseable {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
    private final java.io.OutputStream gz;
    private final boolean raw;
    private boolean finished = false;

    /** 兼容构造：gzip 模式（raw=false）。 */
    public GzipCompressor(int level) throws IOException {
        this(level, false);
    }

    /**
     * @param level 0-9；-1 = Deflater 默认级别
     * @param raw   true = 原始 deflate（无 GZIP 头/尾/CRC）
     */
    public GzipCompressor(int level, boolean raw) throws IOException {
        this.raw = raw;
        this.gz = raw
            ? new java.util.zip.DeflaterOutputStream(buf, new java.util.zip.Deflater(level, true))
            : new GZIPOutputStream(buf) {
                { def.setLevel(level); }
            };
    }

    /** 压缩一块输入，返回输出块（可能为空；调用方决定块大小——建议 ≥256 KiB 摊销跨线程往返）。 */
    public byte[] write(byte[] in) throws IOException {
        if (finished) throw new IllegalStateException("compressor finished");
        if (in.length == 0) return new byte[0];
        gz.write(in);
        gz.flush(); // 非 sync flush：推出 deflater 当前可用输出，不插 flush marker
        var out = buf.toByteArray();
        buf.reset();
        return out;
    }

    /** 结束压缩：返回剩余输出（gzip 含 GZIP 尾；raw 为 deflate 流尾）；此后 write 不可再调用。 */
    public byte[] finish() throws IOException {
        if (finished) return new byte[0];
        finished = true;
        gz.close(); // DeflaterOutputStream.close 写剩余 + end；GZIPOutputStream.close 写 GZIP 尾
        var out = buf.toByteArray();
        buf.reset();
        return out;
    }

    @Override public void close() throws IOException {
        gz.close();
    }
}
