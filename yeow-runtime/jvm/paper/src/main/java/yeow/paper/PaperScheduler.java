package yeow.paper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import yeow.TaskScheduler;
import yeow.YeowConfig;
import yeow.profile.instrumentation.ProfileSink;
import yeow.profile.instrumentation.TaskMetric;
import yeow.profile.instrumentation.TaskPriority;
import yeow.profile.instrumentation.TickMetric;
import yeow.task.Tasks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Paper 平台调度器：三级优先级队列 + 调度线程串行派发 + 主线程 pump。
 *
 * 架构（平台特异）：
 * - 调度线程持有任务队列，串行取出任务后**路由**到主线程执行（前一个结果返回才派发下一个）
 * - 主线程 pump 分两种：事件/补全自旋时 {@link #drainDuringWait} 就地执行（调用方必为主线程）；
 *   正常情形由 {@link #mainTickPump}（runTaskTimer 每 tick）预算内执行
 * - 主线程正在自旋时路由进来的任务由 pump 执行；其余情形排队等下一个 tick
 *
 * 预算/自动降级/缩放沿用时间片模型（以"轮"为单位计 tick）。
 */
public class PaperScheduler implements TaskScheduler {
    private static final Logger LOG = Logger.getLogger("Yeow");
    static final Gson gson = new Gson();

    private final ConcurrentLinkedQueue<PendingTask> highPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> normalPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> lowPool = new ConcurrentLinkedQueue<>();
    /** 路由到主线程等待执行的队列（调度线程投递，主线程 pump 消费）。 */
    private final ConcurrentLinkedQueue<Pending> mainQueue = new ConcurrentLinkedQueue<>();

    private final YeowConfig config;
    private final TaskFrequencyTracker freqTracker;
    private final YeowRuntime runtime;
    private volatile ProfileSink sink;
    private volatile BudgetScaler budgetScaler;
    private long lastLowQueueWarnMs;
    private static final long LOW_QUEUE_WARN_INTERVAL_MS = 60_000;
    private static final long LOW_QUEUE_WARN_THRESHOLD = 100_000;

    // 调度线程
    private final Object idleMonitor = new Object();
    private volatile boolean running = false;
    private Thread dispatchThread;

    record PendingTask(String taskType, JsonObject params, CompletableFuture<String> future, Consumer<Object> callback, Priority priority, String pluginName) {
        boolean isAsync() { return callback != null; }
    }

    /** 主线程队列条目：路由方等待 future 完成。 */
    record Pending(String taskType, JsonObject params, CompletableFuture<Object> future, String pluginName) {}

    public PaperScheduler(YeowConfig config, YeowRuntime runtime) {
        this.config = config;
        this.runtime = runtime;
        this.freqTracker = new TaskFrequencyTracker(config.demoteThreshold());
        var pc = yeow.profile.ProfileConfig.from(config);
        if (pc.scalerEnabled()) {
            this.budgetScaler = new BudgetScaler(config.tickBudgetNs(),
                pc.scalerFactor(), pc.scalerMax(), pc.backlogThreshold(), pc.backlogWindowTicks());
        }
    }

    /** 注入 Profile 插桩接口（null 表示关闭，零开销）。 */
    public void setProfileSink(ProfileSink s) { this.sink = s; }

    public TaskFrequencyTracker freqTracker() { return freqTracker; }

    @Override public void start() {
        if (running) return;
        running = true;
        dispatchThread = new Thread(this::dispatchLoop, "yeow-sched");
        dispatchThread.setDaemon(true);
        dispatchThread.start();
    }

    @Override public void shutdown() {
        running = false;
        synchronized (idleMonitor) { idleMonitor.notifyAll(); }
    }

    private void wake() {
        synchronized (idleMonitor) { idleMonitor.notifyAll(); }
    }

    // ── 提交 ───────────────────────────────────────────────────────

    @Override
    public void submitGameSync(String taskType, JsonObject params, CompletableFuture<String> future, Priority priority, String pluginName) {
        var effective = effectivePriority(priority, pluginName, taskType);
        pool(effective).add(new PendingTask(taskType, params, future, null, effective, pluginName));
        wake();
    }

    @Override
    public void submitGameAsync(String taskType, JsonObject params, Consumer<Object> callback, Priority priority, String pluginName) {
        var effective = effectivePriority(priority, pluginName, taskType);
        pool(effective).add(new PendingTask(taskType, params, null, callback, effective, pluginName));
        wake();
    }

    private Priority effectivePriority(Priority priority, String pluginName, String taskType) {
        if (!config.autoDemote() || priority != Priority.NORMAL || pluginName == null) return priority;
        if (freqTracker.shouldDemote(pluginName, taskType)) return Priority.LOW;
        return priority;
    }

    private ConcurrentLinkedQueue<PendingTask> pool(Priority p) {
        return switch (p) { case HIGH -> highPool; case LOW -> lowPool; default -> normalPool; };
    }

    // ── 调度线程循环（串行派发） ───────────────────────────────────

    private void dispatchLoop() {
        while (running) {
            try {
                drainRound();
                if (highPool.isEmpty() && normalPool.isEmpty() && lowPool.isEmpty()) {
                    synchronized (idleMonitor) {
                        if (running) idleMonitor.wait(50);
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                LOG.warning("[Yeow] scheduler dispatch error: " + e.getMessage());
            }
        }
    }

    private void drainRound() {
        long t0 = System.nanoTime();
        long budget = config.tickBudgetNs();
        long deadline = System.nanoTime() + budget;
        var ratios = config.priorityRatios();
        long highBud = (long)(budget * ratios[0]);
        long normBud = (long)(budget * ratios[1]);
        long lowBud  = (long)(budget * ratios[2]);
        drainTier(highPool, highBud, deadline);
        drainTier(normalPool, normBud, deadline);
        drainTier(lowPool, lowBud, deadline);
        if (System.nanoTime() < deadline) greedyDrain(deadline);

        ProfileSink s = sink;
        if (s != null) {
            s.onTick(new TickMetric(System.currentTimeMillis(), System.nanoTime() - t0,
                highPool.size(), normalPool.size(), lowPool.size()));
        }
        BudgetScaler sc = budgetScaler;
        if (sc != null) sc.onTick(!highPool.isEmpty() || !normalPool.isEmpty());

        int lowSize = lowPool.size();
        if (lowSize > LOW_QUEUE_WARN_THRESHOLD && System.currentTimeMillis() - lastLowQueueWarnMs > LOW_QUEUE_WARN_INTERVAL_MS) {
            lastLowQueueWarnMs = System.currentTimeMillis();
            LOG.warning("[Yeow] LOW priority queue is critically backed up: " + lowSize
                + " pending tasks (>100k) - a plugin is flooding low-priority async tasks;"
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

    private void drainTier(ConcurrentLinkedQueue<PendingTask> pool, long budgetNs, long deadline) {
        long tierEnd = System.nanoTime() + budgetNs;
        while (!pool.isEmpty()) {
            if (System.nanoTime() >= deadline || System.nanoTime() >= tierEnd) return;
            var t = pool.poll(); if (t == null) break;
            executeOne(t);
        }
    }

    /** 串行派发：任务必须在主线程执行--投递 mainQueue 等待 pump 执行。 */
    private void executeOne(PendingTask t) {
        try {
            var startNs = System.nanoTime();
            Object result;
            if (Bukkit.isPrimaryThread()) {
                result = Tasks.execute(t.taskType(), t.params()); // 调度线程意外在主线程时直接执行
            } else {
                var fut = new CompletableFuture<Object>();
                var pluginName = t.pluginName();
                mainQueue.add(new Pending(t.taskType(), t.params(), fut, pluginName));
                result = waitMain(fut, t.taskType());
            }
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
            var err = errObject(e, t.taskType());
            if (t.isAsync()) t.callback().accept(err);
            else t.future().complete(gson.toJson(err));
        }
    }

    private Object waitMain(CompletableFuture<Object> fut, String taskType) {
        try {
            return fut.get(config.taskSyncTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return errObject(e, taskType);
        }
    }

    private static Map<String, Object> errObject(Exception e, String taskType) {
        var err = new LinkedHashMap<String, Object>();
        err.put("err", e.getMessage() != null ? e.getMessage() : e.toString());
        err.put("type", e.getClass().getSimpleName());
        err.put("task", taskType);
        // 完整 Java 堆栈（JS 侧 task.ts post() reject 时附到错误上：'--- runtime executer error ---'）
        var sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        err.put("stack", sw.toString());
        return err;
    }

    // ── 主线程 pump ────────────────────────────────────────────────

    /** 主线程每 tick 调用（runTaskTimer）：预算内执行队列任务；预算有剩余时空闲自旋。 */
    public void mainTickPump() {
        long deadline = System.nanoTime() + config.tickBudgetNs();
        drain(deadline);
        idleSpin(deadline);
    }

    /**
     * 空闲自旋（08-10 Scheduler.idleSpin 恢复）：队列排空后，若预算仍有剩余，
     * 以 idle-spin-us（默认 100µs）窗口自旋等待新任务——JS 同步调用的提交间隙
     * （complete→JS 唤醒→循环体→submit，空闲机器上 ~5µs）落在窗口内即被就地执行，
     * 热循环（for await）无需等下一 tick（否则 1 task/tick ≈ 20/s）。
     * 窗口内持续有新任务到达则持续处理，直至 tick 预算 deadline。
     */
    private void idleSpin(long deadline) {
        long spinNs = config.idleSpinUs() * 1000L;
        if (spinNs <= 0) return;
        while (System.nanoTime() < deadline) {
            if (!mainQueue.isEmpty()) { drain(deadline); continue; }
            long spinEnd = System.nanoTime() + spinNs;
            while (System.nanoTime() < spinEnd && System.nanoTime() < deadline) {
                if (!mainQueue.isEmpty()) break;
                Thread.onSpinWait();
            }
        }
    }

    /**
     * 事件/补全自旋时调用（调用方必为主线程）：排空主线程队列--
     * 等待期间完成任务（event.complete 等）不被饿死。
     */
    public void drainDuringWait() {
        drain(Long.MAX_VALUE);
    }

    private void drain(long deadline) {
        while (System.nanoTime() < deadline) {
            var p = mainQueue.poll();
            if (p == null) return;
            try {
                p.future().complete(Tasks.execute(p.taskType(), p.params()));
            } catch (Exception e) {
                p.future().complete(errObject(e, p.taskType()));
            }
        }
    }

    @Override
    public void purgePluginTasks(String pluginName) {
        purgePool(highPool, pluginName);
        purgePool(normalPool, pluginName);
        purgePool(lowPool, pluginName);
        freqTracker.removePlugin(pluginName);
        mainQueue.removeIf(p -> {
            if (!pluginName.equals(p.pluginName())) return false;
            p.future().complete(Map.of("err", "plugin unloaded"));
            return true;
        });
    }

    private void purgePool(ConcurrentLinkedQueue<PendingTask> pool, String pluginName) {
        pool.removeIf(t -> {
            if (!pluginName.equals(t.pluginName())) return false;
            // Release sync callers (JS threads blocked in future.get) immediately
            // instead of leaving them to wait out the timeout.
            if (!t.isAsync()) t.future().complete(gson.toJson(Map.of("err", "plugin unloaded")));
            return true;
        });
    }
}
