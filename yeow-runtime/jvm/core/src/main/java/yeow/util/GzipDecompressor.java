package yeow.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * gzip 分块解压器（流式管道节点）：`write(chunk)` 喂入一块压缩数据并返回
 * 该块可解出的输出（可能为空），`finish()` 标记输入结束并返回剩余输出。
 *
 * 线程模型：单线程使用（write/finish 与内部读取同线程）。
 * 输入缓冲按块累积；`write` 只解"本次喂入产生的输出"（hasPending 控制），
 * `finish` 解至 GZIP 流 EOF。
 */
public final class GzipDecompressor implements AutoCloseable {
    private final ChunkInput in = new ChunkInput();
    private final boolean raw;
    private java.io.InputStream gz; // 懒创建：构造时输入流为空（GZIPInputStream 会立即读头 → EOF）
    private boolean finished = false;

    /** 兼容构造：gzip 模式（raw=false）。 */
    public GzipDecompressor() {
        this(false);
    }

    /** @param raw true = 原始 deflate（无 GZIP 头/尾/CRC 校验） */
    public GzipDecompressor(boolean raw) {
        this.raw = raw;
    }

    private java.io.InputStream gz() throws IOException {
        if (gz == null) {
            gz = raw
                ? new java.util.zip.InflaterInputStream(in, new java.util.zip.Inflater(true))
                : new GZIPInputStream(in);
        }
        return gz;
    }

    /** 喂入一块压缩数据，返回解压输出块（可能为空）。 */
    public byte[] write(byte[] chunk) throws IOException {
        if (finished) throw new IllegalStateException("decompressor finished");
        if (chunk.length > 0) in.feed(chunk);
        var out = new ByteArrayOutputStream(64 * 1024);
        var tmp = new byte[16 * 1024];
        while (in.hasPending()) {        // 仅解本次喂入可产生的输出
            int n = gz().read(tmp);
            if (n == -1) break;          // GZIP 流提前结束（数据不完整）
            if (n == 0) break;           // 防御：无进展
            out.write(tmp, 0, n);
        }
        return out.toByteArray();
    }

    /** 标记输入结束并返回剩余解压输出（读至 GZIP 流 EOF；数据不完整会抛 IOException）。 */
    public byte[] finish() throws IOException {
        if (finished) return new byte[0];
        finished = true;
        in.markEof();
        var out = new ByteArrayOutputStream(64 * 1024);
        var tmp = new byte[16 * 1024];
        int n;
        while ((n = gz().read(tmp)) != -1) out.write(tmp, 0, n);
        return out.toByteArray();
    }

    @Override public void close() throws IOException {
        if (gz != null) gz.close();
    }

    /** 累积式输入流：write 与 read 同线程；无数据且未 EOF 时 read 返回 -1（调用方用 hasPending 控制）。 */
    private static final class ChunkInput extends InputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private byte[] cur;
        private int pos;
        private boolean eof;

        void feed(byte[] b) throws IOException { pending.write(b); }

        void markEof() { eof = true; }

        boolean hasPending() {
            return (cur != null && pos < cur.length) || pending.size() > 0;
        }

        @Override public int read(byte[] b, int off, int len) {
            if (cur == null || pos >= cur.length) {
                if (pending.size() == 0) return -1; // 调用方保证 hasPending 才读（此处仅 EOF 时合法）
                var all = pending.toByteArray();
                pending.reset();
                cur = all;
                pos = 0;
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override public int read() {
            var one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : (one[0] & 0xff);
        }
    }
}
