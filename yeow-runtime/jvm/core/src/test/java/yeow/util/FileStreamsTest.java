package yeow.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** FileStreams：start/end 偏移、flags（w/a/wx）、分块读写。 */
class FileStreamsTest {

    private static byte[] readAll(FileStreams.Reader r) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        byte[] b;
        while ((b = r.read(1024 * 1024)) != null) out.write(b);
        r.close();
        return out.toByteArray();
    }

    @Test
    void readerFullFile(@TempDir Path dir) throws Exception {
        var f = dir.resolve("a.bin");
        var data = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        Files.write(f, data);
        assertArrayEquals(data, readAll(new FileStreams.Reader(f, 0, -1)));
    }

    @Test
    void readerStartEndOffsets(@TempDir Path dir) throws Exception {
        var f = dir.resolve("a.bin");
        Files.write(f, "0123456789".getBytes(StandardCharsets.UTF_8));
        // start=2, end=5（含）→ "2345"
        assertArrayEquals("2345".getBytes(StandardCharsets.UTF_8), readAll(new FileStreams.Reader(f, 2, 5)));
        // 仅 start=7 → "789"
        assertArrayEquals("789".getBytes(StandardCharsets.UTF_8), readAll(new FileStreams.Reader(f, 7, -1)));
        // end 超出文件 → 到 EOF
        assertArrayEquals("456789".getBytes(StandardCharsets.UTF_8), readAll(new FileStreams.Reader(f, 4, 999)));
    }

    @Test
    void readerStartBeyondEofFails(@TempDir Path dir) throws Exception {
        var f = dir.resolve("a.bin");
        Files.write(f, "abc".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> new FileStreams.Reader(f, 100, -1));
    }

    @Test
    void writerOverwriteAndAppend(@TempDir Path dir) throws Exception {
        var f = dir.resolve("a.txt");
        // w：覆盖
        var w1 = new FileStreams.Writer(f, "w");
        w1.write("hello ".getBytes(StandardCharsets.UTF_8));
        w1.end();
        assertEquals("hello ", Files.readString(f));
        // a：追加
        var w2 = new FileStreams.Writer(f, "a");
        w2.write("world".getBytes(StandardCharsets.UTF_8));
        w2.end();
        assertEquals("hello world", Files.readString(f));
        // w：再次覆盖
        var w3 = new FileStreams.Writer(f, "w");
        w3.write("x".getBytes(StandardCharsets.UTF_8));
        w3.end();
        assertEquals("x", Files.readString(f));
    }

    @Test
    void writerExclusiveCreate(@TempDir Path dir) throws Exception {
        var f = dir.resolve("x.txt");
        var w1 = new FileStreams.Writer(f, "wx");
        w1.write("first".getBytes(StandardCharsets.UTF_8));
        w1.end();
        assertEquals("first", Files.readString(f));
        // 已存在 → 排他创建报错
        assertThrows(Exception.class, () -> new FileStreams.Writer(f, "wx"));
        // 未知 flags 报错
        assertThrows(IllegalArgumentException.class, () -> new FileStreams.Writer(dir.resolve("y.txt"), "r"));
    }
}
