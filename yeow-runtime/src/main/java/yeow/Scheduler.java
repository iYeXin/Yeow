package yeow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import yeow.profile.instrumentation.ProfileSink;
import yeow.profile.instrumentation.TaskMetric;
import yeow.profile.instrumentation.TaskPriority;
import yeow.profile.instrumentation.TickMetric;
import yeow.task.Tasks;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class Scheduler {
    private static final Logger LOG = Logger.getLogger("Yeow");
    static final Gson gson = new Gson();

    public enum Priority { HIGH, NORMAL, LOW }

    private final ConcurrentLinkedQueue<PendingTask> highPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> normalPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> lowPool = new ConcurrentLinkedQueue<>();

    private long tickBudgetNs;
    private double[] priorityRatios;
    private boolean autoDemote;
    private long idleSpinNs;
    private final TaskFrequencyTracker freqTracker;
    private volatile ProfileSink sink;
    private volatile BudgetScaler budgetScaler;
    private long lastLowQueueWarnMs;
    private static final long LOW_QUEUE_WARN_INTERVAL_MS = 60_000;
    private static final int LOW_QUEUE_WARN_THRESHOLD = 100_000;

    public Scheduler(YeowConfig config) {
        this.freqTracker = new TaskFrequencyTracker(config.demoteThreshold());
        applyConfig(config);
    }

    /** 注入 Profile 插桩接口（null 表示关闭，零开销）。 */
    public void setProfileSink(ProfileSink s) { this.sink = s; }

    /** 注入预算缩放器（null 表示不启用）。 */
    public void setBudgetScaler(BudgetScaler sc) { this.budgetScaler = sc; }

    public void setTickBudgetNs(long ns) { this.tickBudgetNs = ns; }
    public long tickBudgetNs() { return tickBudgetNs; }

    public void applyConfig(YeowConfig config) {
        this.tickBudgetNs = config.tickBudgetNs();
        this.priorityRatios = config.priorityRatios();
        this.autoDemote = config.autoDemote();
        this.idleSpinNs = config.idleSpinUs() * 1000L;
        this.freqTracker.setThreshold(config.demoteThreshold());
    }

    public TaskFrequencyTracker freqTracker() { return freqTracker; }

    record PendingTask(String taskType, JsonObject params, CompletableFuture<String> future, Consumer<Object> callback, Priority priority, String pluginName) {
        boolean isAsync() { return callback != null; }
    }

    public void submitGameSync(String taskType, JsonObject params, CompletableFuture<String> future, Priority priority, String pluginName) {
        var effective = effectivePriority(priority, pluginName, taskType);
        pool(effective).add(new PendingTask(taskType, params, future, null, effective, pluginName));
    }

    public void submitGameAsync(String taskType, JsonObject params, Consumer<Object> callback, Priority priority, String pluginName) {
        var effective = effectivePriority(priority, pluginName, taskType);
        pool(effective).add(new PendingTask(taskType, params, null, callback, effective, pluginName));
    }

    private Priority effectivePriority(Priority priority, String pluginName, String taskType) {
        if (!autoDemote || priority != Priority.NORMAL || pluginName == null) return priority;
        if (freqTracker.shouldDemote(pluginName, taskType)) return Priority.LOW;
        return priority;
    }

    private ConcurrentLinkedQueue<PendingTask> pool(Priority p) {
        return switch (p) { case HIGH -> highPool; case LOW -> lowPool; default -> normalPool; };
    }

    public void tick() {
        long t0 = System.nanoTime();
        long deadline = System.nanoTime() + tickBudgetNs;
        long highBud = (long)(tickBudgetNs * priorityRatios[0]);
        long normBud = (long)(tickBudgetNs * priorityRatios[1]);
        long lowBud  = (long)(tickBudgetNs * priorityRatios[2]);
        drainTier(highPool, highBud, deadline);
        drainTier(normalPool, normBud, deadline);
        drainTier(lowPool, lowBud, deadline);
        if (System.nanoTime() < deadline) greedyDrain(deadline);
        if (idleSpinNs > 0) idleSpin(deadline);

        // 插桩：tick 状态 + 预算缩放（HIGH/NORMAL 积压信号）
        ProfileSink s = sink;
        if (s != null) {
            s.onTick(new TickMetric(System.currentTimeMillis(), System.nanoTime() - t0,
                highPool.size(), normalPool.size(), lowPool.size()));
        }
        BudgetScaler sc = budgetScaler;
        if (sc != null) sc.onTick(!highPool.isEmpty() || !normalPool.isEmpty());

        // LOW 批量队列异常膨胀（>100k 任务）→ 告警（60s 冷却）
        int lowSize = lowPool.size();
        if (lowSize > LOW_QUEUE_WARN_THRESHOLD && System.currentTimeMillis() - lastLowQueueWarnMs > LOW_QUEUE_WARN_INTERVAL_MS) {
            lastLowQueueWarnMs = System.currentTimeMillis();
            LOG.warning("[Yeow] LOW priority queue is critically backed up: " + lowSize
                + " pending tasks (>100k) — a plugin is flooding low-priority async tasks;"
                + " check scheduler saturation and plugin health");
        }
    }

    private void greedyDrain(long deadline) {
        while (System.nanoTime() < deadline) {
            if (!highPool.isEmpty()) { drainOne(highPool, deadline); continue; }
            if (!normalPool.isEmpty()) { drainOne(normalPool, deadline); continue; }
            if (!lowPool.isEmpty()) { drainOne(lowPool, deadline); continue; }
            break;
        }
    }

    private void drainOne(ConcurrentLinkedQueue<PendingTask> pool, long deadline) {
        while (System.nanoTime() < deadline) { var t = pool.poll(); if (t == null) break; executeOne(t); }
    }

    private void idleSpin(long deadline) {
        while (System.nanoTime() < deadline) {
            if (!highPool.isEmpty() || !normalPool.isEmpty() || !lowPool.isEmpty()) { greedyDrain(deadline); continue; }
            long spinEnd = System.nanoTime() + idleSpinNs;
            while (System.nanoTime() < spinEnd && System.nanoTime() < deadline) {
                if (!highPool.isEmpty() || !normalPool.isEmpty() || !lowPool.isEmpty()) break;
                Thread.onSpinWait();
            }
        }
    }

    private void drainTier(ConcurrentLinkedQueue<PendingTask> pool, long budgetNs, long deadline) {
        long tierEnd = System.nanoTime() + budgetNs;
        while (!pool.isEmpty()) {
            if (System.nanoTime() >= deadline || System.nanoTime() >= tierEnd) return;
            var t = pool.poll(); if (t == null) break;
            executeOne(t);
        }
    }

    private void executeOne(PendingTask t) {
        try {
            var startNs = System.nanoTime();
            var result = Tasks.execute(t.taskType(), t.params());
            var elapsed = System.nanoTime() - startNs;
            ProfileSink s = sink;
            if (s != null && s.taskSampled()) {
                s.onTask(new TaskMetric(t.pluginName(), t.taskType(),
                    switch (t.priority()) {
                        case HIGH -> TaskPriority.HIGH;
                        case NORMAL -> TaskPriority.NORMAL;
                        case LOW -> TaskPriority.LOW;
                    },
                    elapsed));
            }
            if (t.isAsync()) t.callback().accept(result);
            else t.future().complete(gson.toJson(result));
        } catch (Exception e) {
            var sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            var err = new LinkedHashMap<String, Object>();
            err.put("err", e.getMessage() != null ? e.getMessage() : e.toString());
            err.put("type", e.getClass().getSimpleName());
            err.put("task", t.taskType());
            err.put("stack", sw.toString());
            if (t.isAsync()) t.callback().accept(err);
            else t.future().complete(gson.toJson(err));
        }
    }

    public void purgePluginTasks(String pluginName) {
        purgePool(highPool, pluginName);
        purgePool(normalPool, pluginName);
        purgePool(lowPool, pluginName);
        freqTracker.removePlugin(pluginName);
    }

    private void purgePool(ConcurrentLinkedQueue<PendingTask> pool, String pluginName) {
        pool.removeIf(t -> {
            if (!pluginName.equals(t.pluginName())) return false;
            // Release sync callers (JS threads blocked in future.get) immediately
            // instead of leaving them to wait out the 5s timeout.
            if (!t.isAsync()) t.future().complete(gson.toJson(Map.of("err", "plugin unloaded")));
            return true;
        });
    }

    public void shutdown() {}
}
