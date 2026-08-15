package yeow.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件流句柄（fs 通道流式读写）：有状态句柄保持文件位置，分块读写。
 * 缓冲 256 KiB——降低跨线程往返的 syscall 开销（块大小由调用方决定，建议 ≥256 KiB）。
 *
 * 背压：显式响应——每次 read/write 返回后调用方才发起下一块。
 * 常用选项：读流 start/end 偏移（start 含、end 含）；写流 flags（w 覆盖 / a 追加 / wx 排他创建）。
 */
public final class FileStreams {
    private FileStreams() {}

    /** 读句柄：顺序读取；EOF 返回 null。start/end 为字节偏移（start 含、end 含；end < 0 = 不限制）。 */
    public static final class Reader implements AutoCloseable {
        private final InputStream in;
        private final long end;      // 含；< 0 = 不限制
        private long pos;            // 已读字节数

        /** @param start 起始偏移（含），≥0；@param end 结束偏移（含），<0 表示不限制 */
        public Reader(Path p, long start, long end) throws IOException {
            this.in = new BufferedInputStream(Files.newInputStream(p), 256 * 1024);
            this.end = end;
            if (start > 0) {
                long skipped = in.skip(start);
                if (skipped < start) throw new IOException("start offset " + start + " beyond EOF (skipped " + skipped + ")");
            }
            this.pos = start;
        }

        /** 读取最多 maxBytes 字节；EOF 返回 null。 */
        public byte[] read(int maxBytes) throws IOException {
            if (end >= 0 && pos > end) return null; // 已越过 end
            int len = Math.min(maxBytes > 0 ? maxBytes : 1024 * 1024, 1024 * 1024);
            if (end >= 0) len = (int) Math.min(len, end - pos + 1);
            if (len <= 0) return null;
            var buf = new byte[len];
            int n = in.read(buf);
            if (n == -1) return null;
            pos += n;
            return n == len ? buf : java.util.Arrays.copyOf(buf, n);
        }

        @Override public void close() throws IOException {
            in.close();
        }
    }

    /** 写句柄：顺序写入；end() 冲刷并关闭。flags：w 覆盖（默认）/ a 追加 / wx 排他创建。 */
    public static final class Writer implements AutoCloseable {
        private final OutputStream out;

        /** @param flags "w"（覆盖，默认）、"a"（追加）、"wx"（排他创建，已存在报错） */
        public Writer(Path p, String flags) throws IOException {
            Files.createDirectories(p.getParent());
            var opt = flags == null ? "w" : flags;
            this.out = new BufferedOutputStream(switch (opt) {
                case "a" -> Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                case "wx" -> Files.newOutputStream(p, StandardOpenOption.CREATE_NEW);
                case "w" -> Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                default -> throw new IllegalArgumentException("unsupported write flags: " + opt + " (w/a/wx)");
            }, 256 * 1024);
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
