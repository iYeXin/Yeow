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
 * - 主线程自旋期间 {@link #drainDuringWait} **同时代行调度线程泵职责**：排空优先级池
 *   （08-14 事件死锁修复——事件在任务执行内被触发时，event.complete 等回执在池中，
 *   若泵线程正阻塞在 waitMain 上等当前任务，回执无人泵 → 循环等待，见 drainDuringWait 注释）
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

    /**
     * 当前生效的 tick 预算：BudgetScaler 动态扩容时返回扩容后的预算
     * （原先 drainRound/mainTickPump 直接读 config，scaler 计算出的扩容从未生效）。
     */
    private long effectiveBudgetNs() {
        BudgetScaler sc = budgetScaler;
        return sc != null ? sc.currentBudgetNs() : config.tickBudgetNs();
    }

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
        long budget = effectiveBudgetNs();
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
                var pending = new Pending(t.taskType(), t.params(), fut, pluginName);
                mainQueue.add(pending);
                result = waitMain(fut, t.taskType());
                // 幽灵执行防护：同步等待超时（future 未被主线程 pump 完成）时，任务
                // 仍留在 mainQueue 中，主线程恢复后照常执行——调用方已收到超时错误，
                // 副作用却仍会发生（重复/错位副作用）。超时后按引用移除未执行的任务。
                // 竞态窗口（pump 恰在超时与移除之间取走并执行）仅存在于主线程停滞
                // >taskSyncTimeoutMs 后恢复的瞬间，且副作用已不可避免，属可接受。
                if (!fut.isDone()) mainQueue.removeIf(p -> p == pending);
            }
            finish(t, result, startNs);
        } catch (Exception e) {
            fail(t, e);
        }
    }

    /**
     * 主线程自旋（{@link #drainDuringWait}）代行调度线程泵职责：就地执行池任务。
     * 与 {@link #executeOne} 相同的完成语义（指标 + future/callback 完成）。
     * 调用方必为主线程——任务就地执行无需 mainQueue 往返。
     */
    private void executeNow(PendingTask t) {
        try {
            var startNs = System.nanoTime();
            finish(t, Tasks.execute(t.taskType(), t.params()), startNs);
        } catch (Exception e) {
            fail(t, e);
        }
    }

    private void finish(PendingTask t, Object result, long startNs) {
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
    }

    private void fail(PendingTask t, Exception e) {
        var err = errObject(e, t.taskType());
        if (t.isAsync()) t.callback().accept(err);
        else t.future().complete(gson.toJson(err));
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
        long deadline = System.nanoTime() + effectiveBudgetNs();
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
     * 事件/补全自旋时调用（调用方必为主线程）：排空主线程队列 + **优先级池**。
     * 等待期间完成任务（event.complete 等）不被饿死。
     *
     * 08-14 死锁修复：仅排空 mainQueue 不够——调度线程 {@link #executeOne} 对路由任务执行
     * {@code waitMain}（fut.get 阻塞）期间，若被等待的主线程任务**同步触发事件**（如
     * player.teleport → PlayerTeleportEvent），事件自旋期间 JS 回复的 event.complete 经
     * submitGameSync 进入优先级池；池只有调度线程能泵，而调度线程正阻塞在 waitMain 上
     * 等待当前任务 → 循环等待直至事件 5s 超时（传送完成但服务器卡顿 5s）。
     * 修复：主线程自旋期间代行泵职责，按优先级顺序把池中任务就地执行（{@link #executeNow}，
     * 与 executeOne 相同完成语义）。poll 原子性保证与调度线程无竞态；FIFO 顺序不变。
     */
    public void drainDuringWait() {
        drainPool(highPool);
        drainPool(normalPool);
        drainPool(lowPool);
        drain(Long.MAX_VALUE);
    }

    /** 排空单个优先级池（自旋期间代行泵职责）。 */
    private void drainPool(ConcurrentLinkedQueue<PendingTask> pool) {
        while (true) {
            var t = pool.poll();
            if (t == null) break;
            executeNow(t);
        }
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
