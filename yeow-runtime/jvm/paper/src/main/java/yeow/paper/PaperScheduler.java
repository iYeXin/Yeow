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
 * Paper 平台调度器：三级优先级队列 + **主线程直接消费**（08-14 恢复拆分前模型）。
 *
 * 架构（平台特异）：
 * - **无独立调度线程**：任务从 JS 线程入池后，由主线程每 tick 的 {@link #mainTickPump}
 *   预算内就地执行（tier 分配 → 贪婪 → 空闲自旋盯池）——与拆分前一致，零跨线程握手
 * - 拆分时引入的 yeow-sched 泵线程 + mainQueue 中转在 Paper 上是**纯开销**（主线程本就是
 *   执行者）：每任务多两次线程切换 + future park/unpark + 队列 hop，热循环（setBlock 等）
 *   吞吐减半；且造成"任务内触发事件 → event.complete 回池 → 泵被 waitMain 阻塞"的
 *   5s 死锁（08-14 修复）——已移除
 * - 事件/补全自旋时 {@link #drainDuringWait} 就地执行（调用方必为主线程）——同时排空
 *   优先级池与 mainQueue（死锁修复，主线程自旋期间不依赖 tick）
 * - mainQueue/waitMain 路径仅保留为兜底（{@link #executeOne} 非主线程分支，正常情况下不触发）
 *
 * 预算/自动降级/缩放沿用时间片模型（以"轮"为单位计 tick）。
 */
public class PaperScheduler implements TaskScheduler {
    private static final Logger LOG = Logger.getLogger("Yeow");
    static final Gson gson = new Gson();

    private final ConcurrentLinkedQueue<PendingTask> highPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> normalPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> lowPool = new ConcurrentLinkedQueue<>();
    /** 兜底路由队列（executeOne 非主线程分支投递，主线程 pump 消费；正常情况为空）。 */
    private final ConcurrentLinkedQueue<Pending> mainQueue = new ConcurrentLinkedQueue<>();

    private final YeowConfig config;
    private final TaskFrequencyTracker freqTracker;
    private final YeowRuntime runtime;
    private volatile ProfileSink sink;
    private volatile BudgetScaler budgetScaler;
    private long lastLowQueueWarnMs;
    private static final long LOW_QUEUE_WARN_INTERVAL_MS = 60_000;
    private static final long LOW_QUEUE_WARN_THRESHOLD = 100_000;

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
     * （mainTickPump 使用；scaler 计算出的扩容始终生效）。
     */
    private long effectiveBudgetNs() {
        BudgetScaler sc = budgetScaler;
        return sc != null ? sc.currentBudgetNs() : config.tickBudgetNs();
    }

    public TaskFrequencyTracker freqTracker() { return freqTracker; }

    @Override public void start() {}

    @Override public void shutdown() {}

    // ── 提交 ───────────────────────────────────────────────────────

    @Override
    public void submitGameSync(String taskType, JsonObject params, CompletableFuture<String> future, Priority priority, String pluginName) {
        var effective = effectivePriority(priority, pluginName, taskType);
        pool(effective).add(new PendingTask(taskType, params, future, null, effective, pluginName));
    }

    @Override
    public void submitGameAsync(String taskType, JsonObject params, Consumer<Object> callback, Priority priority, String pluginName) {
        var effective = effectivePriority(priority, pluginName, taskType);
        pool(effective).add(new PendingTask(taskType, params, null, callback, effective, pluginName));
    }

    private Priority effectivePriority(Priority priority, String pluginName, String taskType) {
        if (!config.autoDemote() || priority != Priority.NORMAL || pluginName == null) return priority;
        if (freqTracker.shouldDemote(pluginName, taskType)) return Priority.LOW;
        return priority;
    }

    private ConcurrentLinkedQueue<PendingTask> pool(Priority p) {
        return switch (p) { case HIGH -> highPool; case LOW -> lowPool; default -> normalPool; };
    }

    // ── 主线程消费（恢复拆分前模型；无独立调度线程） ───────────────

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

    /** 执行单个任务：主线程上就地执行；非主线程（兜底路径）投递 mainQueue 等待 pump 执行。 */
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

    /** 主线程就地执行池任务（mainTickPump/drainDuringWait 使用）。与 executeOne 相同完成语义。 */
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

    /**
     * 主线程每 tick 调用（runTaskTimer）：预算内消费优先级池（tier 分配 → 贪婪）+
     * 兜底 mainQueue；预算有剩余时空闲自旋（盯三池 + mainQueue）。tick 末输出指标。
     */
    public void mainTickPump() {
        long t0 = System.nanoTime();
        long budget = effectiveBudgetNs();
        long deadline = System.nanoTime() + budget;
        drainPools(budget, deadline);
        drain(deadline);
        idleSpin(deadline);

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

    /** 预算内按比例消费优先级池（恢复拆分前 tick 模型）：tier 分配 → 贪婪。 */
    private void drainPools(long budget, long deadline) {
        var ratios = config.priorityRatios();
        long highBud = (long)(budget * ratios[0]);
        long normBud = (long)(budget * ratios[1]);
        long lowBud  = (long)(budget * ratios[2]);
        drainTier(highPool, highBud, deadline);
        drainTier(normalPool, normBud, deadline);
        drainTier(lowPool, lowBud, deadline);
        if (System.nanoTime() < deadline) greedyDrain(deadline);
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
            if (!highPool.isEmpty() || !normalPool.isEmpty() || !lowPool.isEmpty()) { greedyDrain(deadline); continue; }
            if (!mainQueue.isEmpty()) { drain(deadline); continue; }
            long spinEnd = System.nanoTime() + spinNs;
            while (System.nanoTime() < spinEnd && System.nanoTime() < deadline) {
                if (!highPool.isEmpty() || !normalPool.isEmpty() || !lowPool.isEmpty()) break;
                if (!mainQueue.isEmpty()) break;
                Thread.onSpinWait();
            }
        }
    }

    /**
     * 事件/补全自旋时调用（调用方必为主线程）：排空**优先级池** + 主线程队列。
     * 等待期间完成任务（event.complete 等）不被饿死。
     *
     * 主线程是任务的唯一消费方：事件在任务执行内被触发（如 player.teleport →
     * PlayerTeleportEvent）时，JS 回复的 event.complete 经 submitGameSync 进入优先级池；
     * 自旋期间若不消费池，回执无人执行 → 事件 5s 超时（08-14 死锁修复）。
     * 按优先级顺序就地执行（{@link #executeNow}，与 executeOne 相同完成语义）；FIFO 顺序不变。
     */
    public void drainDuringWait() {
        drainPool(highPool);
        drainPool(normalPool);
        drainPool(lowPool);
        drain(Long.MAX_VALUE);
    }

    /** 排空单个优先级池（主线程自旋/每 tick 消费）。 */
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
