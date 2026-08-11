package yeow.profile.collector;

/**
 * 对数桶直方图：O(1) 记录，无排序，可估算分位数。
 * 桶边界按 2 倍递增：1µs, 2µs, 4µs, ... 4.2s+（共 24 桶）。
 * 分位数在桶内线性插值（近似，足够定位问题）。
 */
public final class LogHistogram {
    private static final long BASE_NS = 1_000; // 1µs
    private static final int BUCKETS = 24;

    private final long[] counts = new long[BUCKETS];
    private long total;
    private long sumNs;
    private long maxNs;

    public void record(long ns) {
        long v = Math.max(ns, 0L);
        int i = 0;
        long lo = BASE_NS;
        while (i < BUCKETS - 1 && v >= lo) {
            lo <<= 1;
            i++;
        }
        counts[i]++;
        total++;
        sumNs += v;
        if (v > maxNs) maxNs = v;
    }

    public long count() { return total; }
    public long sumNs() { return sumNs; }
    public long maxNs() { return maxNs; }
    public double avgNs() { return total > 0 ? (double) sumNs / total : 0; }

    /** 分位数估算（0~1），桶内按中点取值。 */
    public double percentile(double p) {
        if (total == 0) return 0;
        long target = (long) Math.ceil(p * total);
        if (target < 1) target = 1;
        long acc = 0;
        long lo = BASE_NS;
        for (int i = 0; i < BUCKETS; i++) {
            acc += counts[i];
            if (acc >= target) {
                long hi = i == BUCKETS - 1 ? lo * 4 : lo << 1;
                return (lo + hi) / 2.0;
            }
            lo <<= 1;
        }
        return lo;
    }

    /** 桶计数快照（长度 {@link #bucketCount()}）。 */
    public long[] buckets() {
        return counts.clone();
    }

    public int bucketCount() {
        return BUCKETS;
    }

    /** 按桶数组重建（跨窗口合并用）。 */
    public void mergeBuckets(long[] other) {
        int n = Math.min(BUCKETS, other.length);
        for (int i = 0; i < n; i++) {
            counts[i] += other[i];
            total += other[i];
        }
    }

    public void merge(LogHistogram other) {
        for (int i = 0; i < BUCKETS; i++) counts[i] += other.counts[i];
        total += other.total;
        sumNs += other.sumNs;
        if (other.maxNs > maxNs) maxNs = other.maxNs;
    }

    public void reset() {
        java.util.Arrays.fill(counts, 0);
        total = 0;
        sumNs = 0;
        maxNs = 0;
    }

    /** 桶的上界描述（用于输出标签）。 */
    public static String bucketLabel(int i) {
        long lo = i == 0 ? 0 : BASE_NS << (i - 1);
        long hi = i == BUCKETS - 1 ? Long.MAX_VALUE : BASE_NS << i;
        return (lo < 1_000_000 ? (lo / 1_000) + "µs" : (lo / 1_000_000) + "ms")
            + "-" + (hi >= Long.MAX_VALUE ? "∞" : (hi < 1_000_000 ? (hi / 1_000) + "µs" : (hi / 1_000_000) + "ms"));
    }
}
