package yeow.folia;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NORMAL 任务自动降级频率追踪（移植自 Paper 侧实现）。
 *
 * 滑动窗口算法：1 秒 = 50 个时间槽（每槽 20ms），提交 NORMAL 任务时递增当前槽
 * 计数并统计过去 1 秒总次数；超过阈值（demote-threshold，默认 200 次/秒）→ 该
 * plugin:taskType 本次提交降为 LOW。不保留历史数据，内存占用恒定（50 int + 50 long）。
 * 仅影响提交时行为；频率降低后自然恢复到 NORMAL 池。
 */
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

    /** 插件卸载时清掉其全部频率组。 */
    public void removePlugin(String pluginName) {
        var prefix = pluginName + ":";
        groups.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
