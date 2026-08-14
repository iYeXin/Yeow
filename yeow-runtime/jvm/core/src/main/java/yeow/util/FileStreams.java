package yeow.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件流句柄（fs 通道流式读写）：有状态句柄保持文件位置，分块读写。
 * 缓冲 256 KiB——降低跨线程往返的 syscall 开销（块大小由调用方决定，建议 ≥256 KiB）。
 *
 * 背压：显式响应——每次 read/write 返回后调用方才发起下一块。
 */
public final class FileStreams {
    private FileStreams() {}

    /** 读句柄：顺序读取；EOF 返回 null。 */
    public static final class Reader implements AutoCloseable {
        private final InputStream in;

        public Reader(Path p) throws IOException {
            this.in = new BufferedInputStream(Files.newInputStream(p), 256 * 1024);
        }

        /** 读取最多 maxBytes 字节；EOF 返回 null。 */
        public byte[] read(int maxBytes) throws IOException {
            int len = Math.min(maxBytes > 0 ? maxBytes : 1024 * 1024, 1024 * 1024);
            var buf = new byte[len];
            int n = in.read(buf);
            if (n == -1) return null;
            return n == len ? buf : java.util.Arrays.copyOf(buf, n);
        }

        @Override public void close() throws IOException {
            in.close();
        }
    }

    /** 写句柄：顺序写入；end() 冲刷并关闭。 */
    public static final class Writer implements AutoCloseable {
        private final OutputStream out;

        public Writer(Path p) throws IOException {
            Files.createDirectories(p.getParent());
            this.out = new BufferedOutputStream(Files.newOutputStream(p), 256 * 1024);
        }

        public void write(byte[] b) throws IOException {
            out.write(b);
        }

        /** 冲刷缓冲并关闭（调用后句柄不可再用）。 */
        public void end() throws IOException {
            out.flush();
            out.close();
        }

        @Override public void close() throws IOException {
            out.close();
        }
    }
}
