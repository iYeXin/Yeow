package yeow.paper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskFrequencyTracker {
    private static final int BUCKET_COUNT = 50;
    private static final long WINDOW_MS = 1000;
    private static final long SLOT_MS = WINDOW_MS / BUCKET_COUNT;

    private static class FreqGroup {
        final int[] buckets = new int[BUCKET_COUNT];
        final long[] bucketTime = new long[BUCKET_COUNT];
        boolean checkAndIncrement(int threshold) {
            long now = System.currentTimeMillis();
            int idx = (int)((now % WINDOW_MS) / SLOT_MS);
            if (now - bucketTime[idx] >= SLOT_MS) { buckets[idx] = 0; bucketTime[idx] = now; }
            buckets[idx]++;
            int total = 0; long cutoff = now - WINDOW_MS;
            for (int i = 0; i < BUCKET_COUNT; i++) if (bucketTime[i] >= cutoff) total += buckets[i];
            return total > threshold;
        }
    }
    private final Map<String, FreqGroup> groups = new ConcurrentHashMap<>();
    private volatile int threshold;

    public TaskFrequencyTracker(int threshold) { this.threshold = threshold; }
    public void setThreshold(int t) { this.threshold = t; }
    public int getThreshold() { return threshold; }

    public boolean shouldDemote(String pluginName, String taskType) {
        return groups.computeIfAbsent(pluginName + ":" + taskType, k -> new FreqGroup()).checkAndIncrement(threshold);
    }

    /** Drop all frequency groups of a plugin (called on unload/reload). */
    public void removePlugin(String pluginName) {
        var prefix = pluginName + ":";
        groups.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
