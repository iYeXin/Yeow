package yeow;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 插件包内存镜像：插件 {@code .yeow.zip} / JAR 的 zip 部分常驻内存。
 *
 * <p>动机：{@code handleAssets} 此前每次读取都 {@code new ZipFile(jarPath)}——
 * 每次 open 都要重新解析中央目录 + 打开文件句柄，高频资源读取（worker 代码、
 * 文本/图片资源、原生二进制解压）下开销显著。本类在插件加载时（
 * {@link RuntimeCore#registerPlugin}）<b>一步到位</b>：一次性读入文件字节、
 * 预解析中央目录；后续 assets 读取 / 原生二进制解压全部走内存，零 open/close。
 *
 * <p>开关：{@code config.yml} 的 {@code assets.cache-enabled}（默认
 * {@code true}）。关闭、加载失败（ZIP64 / 非 zip 包）或 dev 模式时回退到
 * {@code ZipFile(jarPath)} 直读路径——调用方（{@code PluginThread} /
 * {@code ServiceManager}）均保留回退分支。
 *
 * <p>实现说明：最小中央目录解析器，只支持 STORED(0) / DEFLATED(8)；
 * 数据起始位置按本地头（local header）的 name/extra 长度定位，因此天然兼容
 * data-descriptor 写法（中央目录的 sizes 为准）。线程安全：底层 {@code byte[]}
 * 不可变，解压用的 {@code Inflater} 每次调用新建。
 */
public final class PluginPackage {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int LF_SIG = 0x04034b50;
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Charset CP437 = Charset.forName("IBM437");
    private static final int FLAG_UTF8 = 0x800;

    private final byte[] data;
    private final Map<String, Entry> index; // 条目名 → 中央目录项（插入顺序）

    private record Entry(int method, int flags, long compSize, long uncompSize, long localOffset) {}

    private PluginPackage(byte[] data, Map<String, Entry> index) {
        this.data = data;
        this.index = index;
    }

    /**
     * 一次性读入文件并预解析中央目录。
     *
     * @throws IOException 非 zip / ZIP64 / 中央目录损坏时抛出，调用方应回退直读
     */
    public static PluginPackage load(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        return new PluginPackage(data, parseCentralDirectory(data));
    }

    /** 是否包含该条目（含目录条目）。 */
    public boolean contains(String name) {
        return index.containsKey(name);
    }

    /** 全部条目名（中央目录顺序，含目录条目）。 */
    public List<String> names() {
        return new ArrayList<>(index.keySet());
    }

    /** 底层文件字节数（内存占用参考）。 */
    public long fileSize() {
        return data.length;
    }

    /**
     * 读取条目解压后的字节；条目不存在返回 {@code null}。
     * 目录条目返回空数组。
     */
    public byte[] read(String name) throws IOException {
        var e = index.get(name);
        if (e == null) return null;
        if (name.endsWith("/")) return new byte[0];
        if (e.uncompSize > Integer.MAX_VALUE) throw new IOException("entry too large: " + name);
        long dataOff = locateData(e);
        if (e.method == 0) {
            // STORED：直接拷贝
            if (dataOff + e.uncompSize > data.length) throw new IOException("truncated entry: " + name);
            var out = new byte[(int) e.uncompSize];
            System.arraycopy(data, (int) dataOff, out, 0, out.length);
            return out;
        } else if (e.method == 8) {
            if (dataOff + e.compSize > data.length) throw new IOException("truncated entry: " + name);
            var inf = new Inflater(true); // zip deflate 为 raw 流（nowrap）
            try {
                inf.setInput(data, (int) dataOff, (int) e.compSize);
                var out = new byte[(int) e.uncompSize];
                int total = 0;
                while (!inf.finished()) {
                    int n;
                    try {
                        n = inf.inflate(out, total, out.length - total);
                    } catch (DataFormatException ex) {
                        throw new IOException("deflate error in entry: " + name, ex);
                    }
                    if (n == 0) {
                        if (inf.finished() || inf.needsInput()) break;
                        throw new IOException("deflate stalled in entry: " + name);
                    }
                    total += n;
                }
                if (total != out.length) throw new IOException("size mismatch in entry: " + name);
                return out;
            } finally {
                inf.end();
            }
        }
        throw new IOException("unsupported method " + e.method + " for entry: " + name);
    }

    /** 由中央目录项定位本地头之后的数据起始偏移。 */
    private long locateData(Entry e) throws IOException {
        long off = e.localOffset;
        if (off + 30 > data.length) throw new IOException("bad local header offset");
        int o = (int) off;
        if (getIntLE(data, o) != LF_SIG) throw new IOException("bad local header signature");
        int nameLen = getShortLE(data, o + 26);
        int extraLen = getShortLE(data, o + 28);
        return off + 30 + nameLen + extraLen;
    }

    private static Map<String, Entry> parseCentralDirectory(byte[] data) throws IOException {
        // EOCD 在文件尾部 [len-22-65557, len-22] 范围内搜索
        int searchFrom = Math.max(0, data.length - 22 - 65557);
        int eocd = -1;
        for (int i = data.length - 22; i >= searchFrom; i--) {
            if (getIntLE(data, i) == EOCD_SIG) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("not a zip file (EOCD not found)");
        int cdCount = getShortLE(data, eocd + 10);
        long cdSize = getIntLE(data, eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = getIntLE(data, eocd + 16) & 0xFFFFFFFFL;
        if (cdCount == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw new IOException("zip64 not supported, fallback to direct read");
        }
        if (cdOffset + cdSize > data.length) throw new IOException("central directory out of range");

        var out = new LinkedHashMap<String, Entry>();
        long p = cdOffset;
        for (int i = 0; i < cdCount; i++) {
            if (p + 46 > data.length) throw new IOException("truncated central directory");
            int o = (int) p;
            if (getIntLE(data, o) != CD_SIG) throw new IOException("bad central directory signature");
            int flags = getShortLE(data, o + 8);
            int method = getShortLE(data, o + 10);
            long compSize = getIntLE(data, o + 20) & 0xFFFFFFFFL;
            long uncompSize = getIntLE(data, o + 24) & 0xFFFFFFFFL;
            int nameLen = getShortLE(data, o + 28);
            int extraLen = getShortLE(data, o + 30);
            int commentLen = getShortLE(data, o + 32);
            long localOffset = getIntLE(data, o + 42) & 0xFFFFFFFFL;
            long nameOff = p + 46;
            if (nameOff + nameLen + extraLen + commentLen > data.length) {
                throw new IOException("truncated central directory entry");
            }
            var cs = ((flags & FLAG_UTF8) != 0) ? UTF8 : CP437;
            String name = new String(data, (int) nameOff, nameLen, cs);
            out.put(name, new Entry(method, flags, compSize, uncompSize, localOffset));
            p = nameOff + nameLen + extraLen + commentLen;
        }
        return out;
    }

    private static int getIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int getShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }
}
