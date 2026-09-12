package yeow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 插件包内存镜像：一次性读入 + 中央目录预解析，与 ZipFile 语义一致。 */
class PluginPackageTest {

    @TempDir
    Path tmp;

    private Path makeZip() throws Exception {
        var zip = tmp.resolve("p-1.0.0.yeow.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            // deflated 文本
            out.putNextEntry(new ZipEntry(".yeow/main.js"));
            out.write("console.log('hi')".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            // stored 二进制
            var bin = new byte[]{0, 1, 2, 3, (byte) 200, (byte) 255};
            var e = new ZipEntry("assets/bin.dat");
            e.setMethod(ZipEntry.STORED);
            e.setSize(bin.length);
            e.setCompressedSize(bin.length);
            var crc = new java.util.zip.CRC32();
            crc.update(bin);
            e.setCrc(crc.getValue());
            out.putNextEntry(e);
            out.write(bin);
            out.closeEntry();
            // 嵌套目录 + 中文名
            out.putNextEntry(new ZipEntry("assets/dir/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/dir/a.txt"));
            out.write("A".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/中文.txt"));
            out.write("中".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            // 空文件
            out.putNextEntry(new ZipEntry("assets/empty.txt"));
            out.closeEntry();
            // yeow.json（含版本/作者，供 __plugin 注入回归）
            out.putNextEntry(new ZipEntry("yeow.json"));
            out.write("{\"name\":\"p\",\"version\":\"1.2.3\",\"author\":\"me\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    @Test
    void readRoundTrip() throws Exception {
        var pkg = PluginPackage.load(makeZip());
        assertEquals("console.log('hi')",
            new String(pkg.read(".yeow/main.js"), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{0, 1, 2, 3, (byte) 200, (byte) 255},
            pkg.read("assets/bin.dat"));
        assertEquals("A", new String(pkg.read("assets/dir/a.txt"), StandardCharsets.UTF_8));
        assertEquals("中", new String(pkg.read("assets/中文.txt"), StandardCharsets.UTF_8));
        assertEquals(0, pkg.read("assets/empty.txt").length);
        assertTrue(pkg.contains("assets/dir/"));
        assertEquals(0, pkg.read("assets/dir/").length);
    }

    @Test
    void missingReturnsNull() throws Exception {
        var pkg = PluginPackage.load(makeZip());
        assertNull(pkg.read("no/such.txt"));
        assertFalse(pkg.contains("no/such.txt"));
    }

    @Test
    void namesPreserveOrder() throws Exception {
        var pkg = PluginPackage.load(makeZip());
        var names = pkg.names();
        assertTrue(names.contains(".yeow/main.js"));
        assertTrue(names.contains("assets/dir/a.txt"));
        assertTrue(names.indexOf("assets/bin.dat") < names.indexOf("assets/dir/a.txt"));
    }

    @Test
    void notAZipThrows() throws Exception {
        var f = tmp.resolve("plain.txt");
        Files.writeString(f, "hello");
        assertThrows(java.io.IOException.class, () -> PluginPackage.load(f));
    }

    @Test
    void matchesZipFileBytes() throws Exception {
        // 内存包解压结果必须与 ZipFile 逐字节一致（含中文名条目）
        var zipPath = makeZip();
        var pkg = PluginPackage.load(zipPath);
        try (var zip = new java.util.zip.ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var ze = entries.nextElement();
                if (ze.isDirectory()) continue;
                var expected = zip.getInputStream(ze).readAllBytes();
                assertArrayEquals(expected, pkg.read(ze.getName()), ze.getName());
            }
        }
    }
}
