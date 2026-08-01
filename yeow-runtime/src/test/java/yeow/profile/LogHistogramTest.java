package yeow.profile;

import org.junit.jupiter.api.Test;
import yeow.profile.collector.LogHistogram;

import static org.junit.jupiter.api.Assertions.*;

class LogHistogramTest {

    @Test
    void percentileApproximation() {
        var h = new LogHistogram();
        for (int i = 0; i < 1000; i++) h.record(1_000_000L);       // 1ms
        for (int i = 0; i < 900; i++) h.record(100_000L);           // 0.1ms
        assertEquals(1900, h.count());
        assertTrue(h.percentile(0.50) >= 0 && h.percentile(0.50) <= 1_000_000 * 2);
        assertTrue(h.percentile(0.95) >= 100_000 && h.percentile(0.95) <= 1_000_000 * 2);
        assertEquals(1_000_000, h.maxNs());
    }

    @Test
    void mergeBucketsAcrossWindows() {
        var a = new LogHistogram();
        a.record(5_000_000);
        var b = new LogHistogram();
        b.record(500_000);
        b.record(500_000_000L);
        var merged = new LogHistogram();
        merged.mergeBuckets(a.buckets());
        merged.mergeBuckets(b.buckets());
        assertEquals(3, merged.count());
        // mergeBuckets 只合并桶计数；sum/max 由上层从 TierMetrics 单独累加
        assertTrue(merged.percentile(0.95) >= 5_000_000 && merged.percentile(0.95) <= 500_000_000 * 2);
    }
}
