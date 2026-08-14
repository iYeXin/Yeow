package yeow;

import com.google.gson.*;
import com.whl.quickjs.wrapper.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.*;
import javax.net.ssl.*;
import java.net.*;

public class PluginThread implements Runnable, PluginEntity {
    static final Gson gson = new Gson();

    public final String name;
    public final String jarPath;
    public final MsgQueue queue = new MsgQueue();
    private String initCode;
    private volatile String userCode;
    private final TaskScheduler scheduler;
    private final RuntimeCore core;
    private final Logger log;
    /** 依附于本插件的 Worker（虚拟插件）：key = worker 名；主插件卸载时连带卸载。 */
    private final ConcurrentHashMap<String, WorkerThread> workers = new ConcurrentHashMap<>();
    /** util 通道输入上限（base64 字符数 ≈ 48 MiB 原始字节）：防内存炸弹。 */
    private static final int MAX_UTIL_INPUT_B64 = 64 * 1024 * 1024;
    /** gzip 解压输出上限（原始字节）：防压缩炸弹。 */
    private static final int MAX_UTIL_OUTPUT_BYTES = 256 * 1024 * 1024;
    private final Set<String> permissions;
    private final Map<String, String> nativeHashes; // 打包后路径(assets/<id>/...) → SHA-256（yeow.json native 声明）
    private volatile QuickJSContext ctx;
    private volatile boolean running = false;
    /** 强杀标记：waitForExit 超时且 interrupt 无法退出时置位，调用方必须重建全新实体。 */
    private volatile boolean forceKilled = false;
    private Thread thread;
    private ScheduledExecutorService timer;
    private ExecutorService ioExecutor;
    private final List<ScheduledFuture<?>> timerFutures = Collections.synchronizedList(new ArrayList<>());
    /** cbId → 定时任务（clear 协议用：JS 侧 clearTimeout/clearInterval 经 timer 通道取消，防僵尸周期任务）。 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timerTasks = new ConcurrentHashMap<>();
    /** 代际计数：每次 reload 自增——旧代 timer 任务投递前校验，杜绝热重载后消息串扰新生代。 */
    private volatile long generation = 0;
    private volatile String devAssetsDir;
    private volatile boolean devMode;
    private final ConcurrentHashMap<String, com.sun.net.httpserver.HttpServer> httpServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HttpConn> httpPending = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Long> pendingPing;
    private volatile long pendingPingSentAt;

    record HttpConn(String serverId, long createdAt, com.sun.net.httpserver.HttpExchange exchange) {}

    /** HTTP 请求无响应超时：JS 侧从未 respond 时关闭连接并移除（防连接/内存泄漏）。 */
    private static final long HTTP_PENDING_TIMEOUT_MS = 30_000;
    private static final long HTTP_SWEEP_INTERVAL_MS = 10_000;
    private final java.util.concurrent.atomic.AtomicBoolean httpSweepStarted = new java.util.concurrent.atomic.AtomicBoolean();

    public void setDevAssetsDir(String d) { devAssetsDir = d; }
    public String getDevAssetsDir() { return devAssetsDir; }
    public void setDevMode(boolean m) { devMode = m; }
    public boolean isDevMode() { return devMode; }

    // ── PluginEntity ──────────────────────────────────────────
    @Override public String name() { return name; }
    @Override public String source() { return jarPath; }
    @Override public String type() { return "js"; }
    @Override public boolean isVirtual() { return false; }
    @Override public void postMessage(Object message) {
        // JS 适配器需要 JSON 字符串：POJO 由运行时序列化，String 原样投递
        queue.sendJs(message instanceof String s ? s : gson.toJson(message));
    }

    @Override
    public CompletableFuture<Long> ping() {
        synchronized (this) {
            if (pendingPing != null) return null; // in-flight，不重复发起
            var fut = new CompletableFuture<Long>();
            pendingPing = fut;
            pendingPingSentAt = System.nanoTime();
            queue.sendJs("{\"t\":\"DEBUG\",\"p\":\"ping\"}");
            return fut;
        }
    }

    /** pong 到达：完成 in-flight ping future（往返纳秒）。 */
    private void onPong() {
        CompletableFuture<Long> fut;
        long sentAt;
        synchronized (this) {
            fut = pendingPing;
            pendingPing = null;
            sentAt = pendingPingSentAt;
        }
        if (fut != null) fut.complete(System.nanoTime() - sentAt);
    }
    // ──────────────────────────────────────────────────────────

    public PluginThread(String name, String jarPath, String initCode, String userCode, RuntimeCore core, Set<String> permissions, Map<String, String> nativeHashes) {
        this.name = name; this.jarPath = jarPath; this.initCode = initCode; this.userCode = userCode;
        this.scheduler = core.scheduler();
        this.core = core;
        this.log = core.host().logger();
        this.permissions = permissions != null ? Set.copyOf(permissions) : Set.of();
        this.nativeHashes = nativeHashes != null ? Map.copyOf(nativeHashes) : Map.of();
    }

    public RuntimeCore core() { return core; }

    /** 权限快照（重建实体用，不可变）。 */
    Set<String> permissions() { return permissions; }

    /** 原生服务 SHA-256 声明（重建实体用，不可变）。 */
    Map<String, String> nativeHashes() { return nativeHashes; }

    public void start() { running = true; thread = new Thread(this, "yeow-" + name); thread.setDaemon(true); thread.start(); }
    public boolean isRunning() { return running; }

    /** Fire-and-forget: ask the JS thread to run onUnload and exit. */
    public void stop() {
        if (ctx != null) queue.sendJs(gson.toJson(Map.of("t","DISABLE")));
    }

    /**
     * Send DISABLE, wait up to 5s for the JS thread to exit (force-kill if hung),
     * then clean up timers / IO / http resources.
     */
    public void stopAndWait() {
        stop();
        waitForExit();
        cleanupResources();
    }

    /**
     * 重载（适配器契约实现）。内部强杀场景的实体重建由 RuntimeCore 处理，
     * 本方法忽略重建结果。
     */
    @Override public void reload(String newCode) { reloadInternal(newCode); }

    /**
     * 重载。返回 false 表示旧线程被强杀（interrupt 无法退出）——调用方**必须**经
     * {@link RuntimeCore#rebuildPluginEntity} 重建全新实体（新线程/新队列/新上下文）：
     * 旧实体仍被卡死的线程引用，若在本对象上 start() 新线程，旧线程恢复后可能从
     * 共享队列偷取消息（双线程并发执行同一插件逻辑）。
     */
    public boolean reloadInternal(String newCode) {
        queue.sendJs(gson.toJson(Map.of("t","RELOAD")));
        waitForExit();
        boolean killed = forceKilled;
        forceKilled = false;
        cleanupResources();
        if (killed) return false;
        generation++; // 代际隔离：旧代 timer 任务投递前校验（见 sendTimerCb），不再进入新生代队列
        queue.clear();
        this.userCode = newCode;
        start();
        return true;
    }

    /**
     * 等待 JS 线程退出（最长 5s）。超时未退出 → 强杀路径：
     * ① {@code ctx.interrupt()}（wrapper 3.9.0+，JS_SetInterruptHandler）原生中断——
     * JS 线程在自身执行流中中止（解释器周期性检查中断标志），随后正常退出并在
     * run() finally 自毁上下文；
     * ② 卡在 Java 调用无法回 JS 的线程：thread.interrupt() 唤醒阻塞点，
     * 仍无法退出则置 forceKilled 标记，由调用方重建全新实体将其遗弃。
     *
     * **绝不在本线程调用 ctx.destroy()**：QuickJS wrapper 的 destroy() 要求创建线程调用
     * （checkSameThread 守卫），跨线程调用必然抛异常（原实现 destroy 恒为空操作，上下文
     * 从未被释放），若去掉守卫又会演变为 use-after-free。上下文一律由 JS 线程自身销毁；
     * JS 线程为 daemon，不会阻塞 JVM 退出。
     */
    private void waitForExit() {
        long deadline = System.currentTimeMillis() + 5000;
        while (running && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }

        if (running) {
            log.warning("[" + name + "] JS thread unresponsive for 5s - forcing stop");
            running = false;
            var c = ctx;
            if (c != null) { try { c.interrupt(); } catch (Exception ignored) {} } // 原生中断（3.9.0+）
            thread.interrupt();
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            if (thread.isAlive()) {
                // 线程仍卡住（卡在无法返回 JS 的 Java 调用等）。不跨线程 destroy
                // （见方法注释）：标记强杀，让调用方重建实体。
                forceKilled = true;
                try { thread.join(1000); } catch (InterruptedException ignored) {}
            }
        } else if (thread != null) {
            try { thread.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    private void cleanupResources() {
        // 先卸载依附的 Worker（虚拟插件：完整清理经 RuntimeCore.unloadPlugin）
        for (var w : new ArrayList<>(workers.values())) {
            core.unloadPlugin(w.name());
        }
        workers.clear();
        timerTasks.forEach((k, f) -> f.cancel(false));
        timerTasks.clear();
        timerFutures.forEach(f -> f.cancel(false));
        timerFutures.clear();
        if (timer != null) timer.shutdownNow();
        if (ioExecutor != null) ioExecutor.shutdownNow();
        scheduler.purgePluginTasks(name);
        var dir = "plugins/" + name;
        httpServers.entrySet().removeIf(e -> { if (e.getKey().startsWith(dir)) { try { e.getValue().stop(0); } catch (Exception ignored) {} return true; } return false; });
        httpPending.entrySet().removeIf(e -> { if (e.getValue().serverId().startsWith(dir)) { try { e.getValue().exchange().close(); } catch (Exception ignored) {} return true; } return false; });
        // 平台侧命令/事件/GUI/BossBar 清理（热重载、卸载、Worker 均经此路径）
        core.host().purgePlatformResources(name);
    }

    /** 定时器回调投递：代际校验——热重载后旧代 timer 消息不得进入新生代队列（防跨代 cbId 串扰）。 */
    private void sendTimerCb(String cbId, long gen) {
        if (gen != generation) return;
        queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",true)));
    }

    @Override
    public void run() {
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "timer-" + name); t.setDaemon(true); return t; });
        this.ioExecutor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "io-" + name); t.setDaemon(true); return t; });
        try {
            ctx = QuickJSContext.create();
        } catch (Exception e) {
            log.warning("[" + name + "] Failed to create QuickJS context: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return;
        }
        if (ctx == null) { log.warning("[" + name + "] ctx is null"); return; }
        try {
            inject();
            if (initCode == null) { log.warning("[" + name + "] initCode is null"); return; }
            ctx.evaluate(initCode, "init.js");
            if (userCode == null) { log.warning("[" + name + "] userCode is null"); return; }
            ctx.evaluate(userCode, "main.js");

            var hmObj = ctx.getGlobalObject().getProperty("$hm");
            var hmFunc = hmObj instanceof JSFunction ? (JSFunction)hmObj : null;
            if (hmFunc == null) log.warning("[" + name + "] $hm not found");

            if (hmFunc != null) hmFunc.call(gson.toJson(Map.of("t","INIT")));

            var prof = core.profiler();
            if (prof != null) prof.registerPlugin(PluginThread.this);

            // 消息驱动的消息循环（原子性由 BlockingQueue 保证，单消费者线程）：
            //   - 无消息 → 阻塞在 takeJs（循环"未运行"态，零轮询）
            //   - 收到消息 → 处理 → 队列有剩余立即继续（pollJs），空则回 takeJs
            // 退出：DISABLE/RELOAD 消息 → JS 侧 unloadDone → running=false → 退出循环。
            while (running) {
                var raw = queue.takeJs();
                while (running) {
                    if (raw == null) break;
                    try {
                        if (hmFunc != null) {
                            hmFunc.call(raw);
                        } else {
                            var escaped = raw.replace("\\","\\\\").replace("'","\\'");
                            ctx.evaluate("$hm('" + escaped + "')");
                        }
                    } catch (QuickJSException ex) { handleJSError(ex); } catch (Exception ignored) {}
                    try {
                        while (ctx.isJobPending()) ctx.executePendingJob();
                    } catch (QuickJSException ex) {
                        // A pending job threw (e.g. an async error surfaced by the native wrapper).
                        // Report it but keep the message loop alive - the plugin must not die here.
                        handleJSError(ex);
                    } catch (Exception e) {
                        log.warning("[" + name + "] job error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                    raw = queue.pollJs(); // 有剩余立即取；空则退出内层，回到 takeJs 阻塞
                }
            }
        } catch (QuickJSException e) { handleJSError(e); } catch (Exception e) {
            log.warning("[" + name + "] " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            // Only destroy the context this thread created. If reload() force-destroyed it
            // (ctx set to null) or a new thread already created a new one, leave it alone.
            var myCtx = ctx;
            if (myCtx != null && myCtx == ctx) {
                ctx = null;
                try { myCtx.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    private void inject() {
        var g = ctx.getGlobalObject();
        var dir = "plugins/" + name;
        ctx.evaluate("globalThis.__plugin = {name:'" + name.replace("'","\\'") + "',version:'',author:''};");
        ctx.evaluate("globalThis.$dev = " + devMode + ";");

        g.setProperty("$_send", (JSCallFunction) args -> {
            try {
                var channel = String.valueOf(args[0]); var pld = String.valueOf(args.length > 1 ? args[1] : "{}");
                var rt = core;
                if ("worker".equals(channel)) {
                    // Worker 通道（内部控制，不受权限模型约束）：创建/卸载/投递/重载主插件的 Worker
                    return handleWorker(pld);
                } else if ("task".equals(channel)) {
                    // task 通道为共有接口（适配器同一入口：YeowRuntime.submitTask）
                    return rt != null ? rt.submitTask(PluginThread.this, pld) : gson.toJson(Map.of("err", "runtime unavailable"));
                } else if ("timer".equals(channel)) {
                    var obj = gson.fromJson(pld, JsonObject.class); var type = obj.get("type").getAsString();
                    if ("clear".equals(type)) {
                        // clear 协议：JS 侧 clearTimeout/clearInterval 取消 Java 定时任务
                        // （此前只做本地注销——interval 的 scheduleAtFixedRate 会永久空转）
                        var cbId = obj.get("cb").getAsString();
                        var f = timerTasks.remove(cbId);
                        if (f != null) f.cancel(false);
                        return null;
                    }
                    var cbId = obj.get("cb").getAsString(); var delay = obj.get("delay").getAsLong();
                    // 延迟下限（协议层防御）：timeout ≥0；interval ≥1（scheduleAtFixedRate period 必须 >0，
                    // 否则抛 IAE 且 JS 侧回调已注册——静默失败 + 悬挂注册）
                    final long gen = generation; // 代际捕获：热重载后旧任务投递前校验
                    if ("timeout".equals(type)) {
                        var f = timer.schedule(() -> { timerTasks.remove(cbId); sendTimerCb(cbId, gen); },
                            Math.max(0, delay), TimeUnit.MILLISECONDS);
                        timerTasks.put(cbId, f);
                    } else if ("interval".equals(type)) {
                        var f = timer.scheduleAtFixedRate(() -> sendTimerCb(cbId, gen),
                            Math.max(1, delay), Math.max(1, delay), TimeUnit.MILLISECONDS);
                        timerTasks.put(cbId, f);
                    }
                    return null;
                } else if ("fs".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("fs", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleFs(pld); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cbId, toJsonValue(result))); });
                        return null;
                    }
                    return handleFs(pld);
                } else if ("http".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("http", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    return handleHttp(pld);
                } else if ("assets".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("assets", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleAssets(pld); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cbId, toJsonValue(result))); });
                        return null;
                    }
                    return handleAssets(pld);
                } else if ("util".equals(channel)) {
                    // util 通道（纯计算，无权限检查）：gzip 压缩/解压 + UTF-8 ↔ 字节转换。
                    // 字节数据以 base64 字符串承载（JS 侧引擎原生 Uint8Array.toBase64/fromBase64）；
                    // encode/decode 语义 = buffer ↔ 字符串，base64 只是承载形式。
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleUtil(pld); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cbId, toJsonValue(result))); });
                        return null;
                    }
                    return handleUtil(pld);
                } else if ("debug".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var dt = obj.get("t").getAsString();
                    if ("reportError".equals(dt)) { handleJSReport(gson.toJson(obj.get("p"))); }
                    else if ("pong".equals(dt)) { onPong(); }
                    else if ("command".equals(dt)) {
                        // 运行时内部测试节点（如性能基准）：**仅开发模式开放**（-Dyeow.dev=true，
                        // dev-server 默认启用）——生产环境拒绝。
                        // core 只转发、不关心具体逻辑（指令解析/执行线程由平台实现负责；
                        // 可选接口，平台不实现返回 not implemented；Folia 不实现）。
                        if (!devMode) return gson.toJson(Map.of("err", "debug command disabled (dev-only)"));
                        var cp = obj.has("p") ? obj.getAsJsonObject("p") : new JsonObject();
                        return gson.toJson(core.host().debugCommand(cp));
                    }
                    return null;
                } else if ("lifecycle".equals(channel)) {
                    var o = gson.fromJson(pld, JsonObject.class); var lt = o.has("type") ? o.get("type").getAsString() : "";
                    if ("gc-collect".equals(lt)) {
                        var ids = o.getAsJsonArray("ids");
                        for (var el : ids) { core.instances().release(el.getAsString()); }
                        return null;
                    }
                    if ("unloadDone".equals(lt)) { running = false; return null; }
                    running = false; return null;
                } else if ("log".equals(channel)) {
                    var o = gson.fromJson(pld, JsonObject.class); log.info(o.has("message") ? o.get("message").getAsString() : pld); return null;
                } else if ("env".equals(channel)) { return handleEnv(); }
                else if ("service".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("service", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    return handleService(pld);
                }
                else if ("dir".equals(channel)) { return dir; }
                else { return null; }
            } catch (Exception ex) {
                log.warning("[" + name + "] $_send err: " + ex.getMessage());
                return gson.toJson(Map.of("err", ex.getMessage() != null ? ex.getMessage() : ex.toString()));
            }
        });

        var consoleObj = ctx.getGlobalObject().getProperty("console");
        if (consoleObj instanceof JSObject jsConsole) {
            jsConsole.setProperty("log", (JSCallFunction) a -> null);
        }
    }

    /**
     * Sensitive-permission check for message channels（JS 插件特有的权限模型）。
     * 权限只按消息节点（channel:node）考虑；节点名中的段是业务/访问范围命名，非层级。
     * 策略：声明命中（精确节点 / channel:* / channel:段.*）→ 允许；否则命中默认拒绝
     * 前缀 → 拒绝；否则默认允许。
     */
    private static final String[] DEFAULT_DENIED_NODES = {
        "fs:server.", "fs:outer.", "http:", "service:registerNative", "assets:extract",
    };

    /** 运行时配置目录（plugins/Yeow/runtime/）：fs 写操作一律禁止修改（读取不受限）。 */
    private static final Path RUNTIME_DIR = Path.of("plugins", "Yeow", "runtime").toAbsolutePath().normalize();

    private String checkChannelPermission(String channel, String op) {
        var node = channel + ":" + op;
        if (permissions.contains(node) || permissions.contains(channel + ":*")) return null;
        var dot = op.indexOf('.');
        if (dot > 0 && permissions.contains(channel + ":" + op.substring(0, dot) + ".*")) return null;
        for (var denied : DEFAULT_DENIED_NODES) {
            if (node.startsWith(denied)) return "Permission denied: " + node;
        }
        return null;
    }

    // ── Worker 通道（内部控制）与公共包装（WorkerThread 委托）─────────────

    /**
     * Worker 通道：主插件 JS 侧的 createWorker/load/unload/postMessage/reload。
     * 请求：{ "t": "create|unload|post|reload|postToMain", "p": {...} }--p 含 cb（异步回调 ok/err）。
     */
    private String handleWorker(String pld) {
        try {
            var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
            var t = obj.get("t").getAsString();
            var p = obj.has("p") ? obj.getAsJsonObject("p") : new JsonObject();
            var cb = p.has("cb") ? p.get("cb").getAsString() : null;
            java.util.function.Consumer<String> respond = (result) -> {
                if (cb != null) queue.sendJs(gson.toJson(Map.of("t","cb","p",cb,"r",result)));
            };
            return switch (t) {
                case "create" -> {
                    // 仅注册（构造句柄，不启动）：worker.load() 才执行 init/inject/代码/INIT/LOAD
                    var wname = p.get("name").getAsString();
                    if (wname.isEmpty() || "main".equals(wname) || workers.containsKey(wname)) {
                        respond.accept("{\"err\":\"invalid or duplicate worker name: " + wname + "\"}");
                        yield null;
                    }
                    var code = workerCode(p);
                    if (code == null) { respond.accept("{\"err\":\"worker entry not found\"}"); yield null; }
                    var w = new WorkerThread(wname, wname, PluginThread.this, initCode, code);
                    if (p.has("msgCb") && !p.get("msgCb").isJsonNull()) w.setMainMessageCb(p.get("msgCb").getAsString());
                    workers.put(wname, w);
                    respond.accept("true");
                    yield null;
                }
                case "load" -> {
                    // 启动已注册的 Worker（注册实体 → 执行 init.js → worker-inject.js → 代码 → INIT → LOAD）
                    var w = workers.get(p.get("name").getAsString());
                    if (w == null) { respond.accept("{\"err\":\"worker not registered\"}"); yield null; }
                    if (!w.isRunning()) {
                        var rt = core;
                        if (rt != null && rt.getPlugin(w.name()) == null) rt.registerPluginEntity(w, false);
                        else w.start();
                        long deadline = System.currentTimeMillis() + 5000;
                        while (System.currentTimeMillis() < deadline && (w.messageCbId() == null || !w.isRunning())) {
                            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                        }
                    }
                    respond.accept("true");
                    yield null;
                }
                case "unload" -> {
                    // 卸载：停止线程并清理（物理销毁 JS 上下文），句柄保留在注册表--可重新 load
                    var w = workers.get(p.get("name").getAsString());
                    if (w != null) core.unloadPlugin(w.name());
                    respond.accept("true");
                    yield null;
                }
                case "post" -> {
                    var w = workers.get(p.get("name").getAsString());
                    if (w == null || !w.isRunning() || w.messageCbId() == null) { respond.accept("{\"err\":\"worker not loaded\"}"); yield null; }
                    var msg = p.has("msg") ? p.get("msg") : JsonNull.INSTANCE;
                    w.postMessage(gson.toJson(Map.of("t","cb","p",w.messageCbId(),"r", gson.fromJson(msg.toString(), Object.class))));
                    respond.accept("true");
                    yield null;
                }
                case "reload" -> {
                    // 重载运行中的 Worker；未加载（未 load）时报错
                    var wname2 = p.get("name").getAsString();
                    var w = workers.get(wname2);
                    if (w == null) { respond.accept("{\"err\":\"worker not registered\"}"); yield null; }
                    if (!w.isRunning()) { respond.accept("{\"err\":\"worker not loaded\"}"); yield null; }
                    var code = workerCode(p);
                    if (code == null) { respond.accept("{\"err\":\"worker entry not found\"}"); yield null; }
                    if (!w.reloadInternal(code)) {
                        // 旧线程被强杀：重建全新 WorkerThread（新线程/新队列/新上下文），
                        // 防止卡死的旧线程从共享队列偷取消息（双线程并发执行）。
                        var oldMainCb = w.mainMessageCb();
                        var nw = new WorkerThread(wname2, wname2, PluginThread.this, initCode, code);
                        if (oldMainCb != null) nw.setMainMessageCb(oldMainCb);
                        workers.put(wname2, nw);
                        w = nw;
                        var rt = core;
                        if (rt != null) {
                            rt.unregisterPluginEntity(w.name()); // 强杀路径旧实体未清理——先摘除再注册
                            if (rt.getPlugin(w.name()) == null) rt.registerPluginEntity(w, false);
                            else w.start();
                        } else {
                            w.start();
                        }
                    }
                    long deadline = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < deadline && (w.messageCbId() == null || !w.isRunning())) {
                        try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    }
                    respond.accept("true");
                    yield null;
                }
                default -> gson.toJson(Map.of("err", "Unknown worker op: " + t));
            };
        } catch (Exception e) {
            return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 从 code 或 entry（assets 资源路径）读取 worker 代码；失败返回 null。 */
    private String workerCode(JsonObject p) {
        if (p.has("code") && !p.get("code").isJsonNull() && !p.get("code").getAsString().isEmpty())
            return p.get("code").getAsString();
        if (p.has("entry") && !p.get("entry").isJsonNull()) {
            var entry = p.get("entry").getAsString();
            var raw = handleAssetsPublic(gson.toJson(Map.of("t","read","p",Map.of("path", entry))));
            var j = gson.fromJson(raw, JsonObject.class);
            if (j != null && j.has("data")) return j.get("data").getAsString();
        }
        return null;
    }

    /** 数据目录（fs plugin 级 base；Worker 共享）。 */
    public String getDataDirPublic() { return "plugins/" + name; }
    public TaskScheduler getSchedulerRef() { return scheduler; }
    public String checkChannelPermissionPublic(String channel, String op) { return checkChannelPermission(channel, op); }
    public String handleFsPublic(String pld) { return handleFs(pld); }
    public String handleAssetsPublic(String pld) { return handleAssets(pld); }
    public String handleHttpPublic(String pld) { return handleHttp(pld); }
    public Object toJsonValuePublic(String json) { return toJsonValue(json); }
    public void handleJSReportPublic(String pld, String origin) { handleJSReport(pld, origin); }
    public void handleJSErrorPublic(QuickJSException e, String origin) { handleJSError(e, origin); }
    public String handleEnvPublic() { return handleEnv(); }

    /**
     * env 通道（同步）：运行时环境信息 + 微秒时间戳。
     * - cpus：CPU 逻辑核心数
     * - memory：JVM 总内存（字节）
     * - arch：系统架构（如 windows-x64 / linux-x64 / linux-arm64）
     * - minecraftVersion：Minecraft 版本（如 1.21.4）
     * - yeow：运行时信息 { platform, version }
     * - now：epoch 微秒时间戳（通信开销在微秒级，纳秒无意义）
     */
    private String handleEnv() {
        var osName = System.getProperty("os.name").toLowerCase();
        String os = osName.contains("win") ? "windows" : osName.contains("mac") ? "macos" : "linux";
        var archRaw = System.getProperty("os.arch").toLowerCase();
        String arch = archRaw.contains("aarch64") || archRaw.contains("arm64") ? "arm64"
            : archRaw.contains("x86_64") || archRaw.contains("amd64") ? "x64"
            : archRaw.contains("arm") ? "armv7" : archRaw;
        var now = java.time.Instant.now();
        long nowUs = now.getEpochSecond() * 1_000_000L + now.getNano() / 1000L;
        String yeowVersion = core.host().runtimeVersion();
        if (yeowVersion == null) yeowVersion = "0.2.0";
        return gson.toJson(Map.of(
            "cpus", Runtime.getRuntime().availableProcessors(),
            "memory", Runtime.getRuntime().totalMemory(),
            "arch", os + "-" + arch,
            "minecraftVersion", core.host().minecraftVersion(),
            "yeow", Map.of("platform", core.host().platformName(), "version", yeowVersion),
            "now", nowUs));
    }

    /** 禁止对 Yeow 运行时配置目录（含 approve.json / config.yml）的修改--fs 写操作（全部级别）一律拦截。 */
    private void assertNotRuntimeDir(Path path) throws SecurityException {
        if (path.startsWith(RUNTIME_DIR)) {
            throw new SecurityException("Cannot modify Yeow runtime directory (plugins/Yeow/runtime): " + path);
        }
    }

    /**
     * 通道处理返回的 JSON 字符串 → 对象，用于异步回调投递（`r` 字段）：
     * 同步调用经 `$send` 的 JSON.parse 得到对象；异步回调必须同样得到对象，
     * 否则 JS 侧收到字符串（如 `{"data":...}`），`r.data` 为 undefined。
     * "null"（缺失文件等）→ null；解析失败原样返回。
     */
    private static Object toJsonValue(String json) {
        if (json == null) return null;
        try { return gson.fromJson(json, Object.class); } catch (Exception e) { return json; }
    }

    /** 周期清理：JS 侧从未 respond 的请求（超时）→ 503 关闭，防止连接与内存泄漏。 */
    private void sweepHttpPending() {
        if (httpPending.isEmpty()) return;
        long cutoff = System.currentTimeMillis() - HTTP_PENDING_TIMEOUT_MS;
        httpPending.entrySet().removeIf(e -> {
            var conn = e.getValue();
            if (conn.createdAt() >= cutoff) return false;
            try { conn.exchange().sendResponseHeaders(503, -1); } catch (Exception ignored) {}
            try { conn.exchange().close(); } catch (Exception ignored) {}
            return true;
        });
    }

    private String handleFs(String pld) {        try {
            var obj = gson.fromJson(pld, JsonObject.class); var task = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
            var dot = task.indexOf('.');
            var level = dot > 0 ? task.substring(0, dot) : "plugin";
            var op = dot > 0 ? task.substring(dot + 1) : task;
            // plugin：插件数据目录（免声明）；server：服务器根（工作目录，需 fs:server.*）；
            // outer：任意路径（相对路径仍基于服务器根计算，需 fs:outer.*）。
            var base = switch (level) {
                case "server" -> Path.of("").toAbsolutePath().normalize();
                case "outer" -> null;
                default -> Path.of("plugins", name).toAbsolutePath().normalize();
            };
            return switch (op) {
                case "readFile" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield gson.toJson(Map.of("data", Files.readString(path))); }
                case "writeFile" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.writeString(path, p.get("data").getAsString()); yield "true"; }
                case "appendFile" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.writeString(path, p.get("data").getAsString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); yield "true"; }
                case "exists" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield String.valueOf(Files.exists(path)); }
                case "isDirectory" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield String.valueOf(Files.isDirectory(path)); }
                case "delete" -> { var path = resolvePath(base, p.get("path").getAsString(), false); assertNotRuntimeDir(path); yield String.valueOf(Files.deleteIfExists(path)); }
                case "mkdir" -> { var path = resolvePath(base, p.get("path").getAsString(), false); assertNotRuntimeDir(path); Files.createDirectories(path); yield "true"; }
                case "list" -> { var path = resolvePath(base, p.get("path").getAsString(), false); try (var s = Files.list(path)) { yield gson.toJson(s.map(Path::toString).toList()); } }
                case "readBase64" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(Files.readAllBytes(path)))); }
                case "writeBase64" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.write(path, Base64.getDecoder().decode(p.get("data").getAsString())); yield "true"; }
                case "systemPaths" -> {
                    // 仅 outer 级：返回常用系统路径（桌面/临时目录/用户主目录）
                    if (!"outer".equals(level)) throw new IllegalArgumentException("systemPaths is outer-level only");
                    var home = System.getProperty("user.home", "");
                    yield gson.toJson(Map.of(
                        "home", home,
                        "desktop", Path.of(home, "Desktop").toString(),
                        "temp", System.getProperty("java.io.tmpdir", "")));
                }
                case "getServerPath" -> {
                    // 仅 outer 级：服务器根目录（Java 进程工作目录）的绝对路径
                    if (!"outer".equals(level)) throw new IllegalArgumentException("getServerPath is outer-level only");
                    yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().toString()));
                }
                default -> throw new IllegalArgumentException("Unknown fs: " + task);
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /**
     * 路径解析（写操作语义）：规范化 + 越界检查 + 创建父目录。
     * 读操作请用 {@link #resolvePath(Path, String, boolean)} 传 false——
     * 查询操作不得有创建目录的副作用（原实现 readFile/exists/list 会静默建目录）。
     */
    private Path resolvePath(Path base, String userPath) throws IOException {
        return resolvePath(base, userPath, true);
    }

    private Path resolvePath(Path base, String userPath, boolean createParent) throws IOException {
        var p = (base != null ? base.resolve(userPath) : Path.of(userPath)).normalize();
        if (base != null && !p.startsWith(base)) throw new SecurityException("Path traversal: " + userPath);
        if (createParent) Files.createDirectories(p.getParent());
        return p;
    }

    /** 递归复制目录（assetsExtractDir：dev 模式源目录 → 目标）。 */
    private static void copyDirRecursive(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (var p : stream.toList()) {
                var target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String handleHttp(String pld) {
        try {
            var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class); var t = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
            return switch (t) {
                case "listen" -> {
                    var pluginName = p.get("pluginName").getAsString(); var callbackId = p.get("callbackId").getAsString(); var port = p.get("port").getAsInt();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
                    var id = "plugins/" + name + "/" + (port == 0 ? server.getAddress().getPort() : port);
                    server.createContext("/", exchange -> {
                        try {
                            var req = new LinkedHashMap<String, Object>();
                            var connId = UUID.randomUUID().toString();
                            req.put("serverId", id); req.put("connId", connId);
                            req.put("method", exchange.getRequestMethod()); req.put("path", exchange.getRequestURI().getPath());
                            req.put("query", exchange.getRequestURI().getQuery());
                            req.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            var headers = new LinkedHashMap<String, String>();
                            exchange.getRequestHeaders().forEach((k,v) -> headers.put(k.toLowerCase(), String.join(", ", v)));
                            req.put("headers", headers);
                            httpPending.put(connId, new HttpConn(id, System.currentTimeMillis(), exchange));
                            queue.sendJs(gson.toJson(Map.of("t","cb","p",callbackId,"r",req)));
                        } catch (Exception ignored) {}
                    });
                    server.setExecutor(ioExecutor); server.start();
                    httpServers.put(id, server);
                    // 周期清扫：JS 侧从不 respond 的请求超时后 503 关闭（首次 listen 时启动一次）
                    if (httpSweepStarted.compareAndSet(false, true)) {
                        timerFutures.add(timer.scheduleAtFixedRate(this::sweepHttpPending,
                            HTTP_SWEEP_INTERVAL_MS, HTTP_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS));
                    }
                    yield gson.toJson(Map.of("serverId", id, "port", server.getAddress().getPort()));
                }
                case "respond" -> {
                    var connId = p.get("connId").getAsString();
                    var conn = httpPending.remove(connId);
                    if (conn != null) {
                        try {
                            var status = p.has("status") ? p.get("status").getAsInt() : 200;
                            if (p.has("headers") && !p.get("headers").isJsonNull()) {
                                var hs = p.getAsJsonObject("headers");
                                hs.entrySet().forEach(e2 -> conn.exchange().getResponseHeaders().add(e2.getKey(), e2.getValue().getAsString()));
                            }
                            if (p.has("bodyBase64") && !p.get("bodyBase64").isJsonNull() && !p.get("bodyBase64").getAsString().isEmpty()) {
                                // 二进制响应：base64 解码后原样写出（如资源包等 assets 二进制）
                                var bytes = Base64.getDecoder().decode(p.get("bodyBase64").getAsString());
                                conn.exchange().sendResponseHeaders(status, bytes.length);
                                try (var os = conn.exchange().getResponseBody()) { os.write(bytes); }
                            } else {
                                var body = p.has("body") ? p.get("body").getAsString() : "";
                                if (body.isEmpty()) {
                                    conn.exchange().sendResponseHeaders(status, -1);
                                } else {
                                    var bytes = body.getBytes(StandardCharsets.UTF_8);
                                    conn.exchange().sendResponseHeaders(status, bytes.length);
                                    try (var os = conn.exchange().getResponseBody()) { os.write(bytes); }
                                }
                            }
                        } catch (Exception ignored) {}
                        finally { try { conn.exchange().close(); } catch (Exception ignored) {} }
                    }
                    yield "true";
                }
                case "close" -> {
                    var serverId = p.get("serverId").getAsString(); var srv = httpServers.remove(serverId);
                    if (srv != null) srv.stop(0);
                    httpPending.entrySet().removeIf(e -> {
                        if (serverId.equals(e.getValue().serverId())) { try { e.getValue().exchange().close(); } catch (Exception ignored) {} return true; }
                        return false;
                    });
                    yield "true";
                }
                case "request" -> {
                    var url = p.get("url").getAsString(); var method = p.has("method") ? p.get("method").getAsString() : "GET";
                    var body = p.has("body") ? p.get("body").getAsString() : null;
                    var headers = p.has("headers") ? p.getAsJsonObject("headers") : new JsonObject();
                    var responseType = p.has("responseType") ? p.get("responseType").getAsString() : "text";
                    yield handleHttpRequest(url, method, body, headers, responseType);
                }
                case "requestAsync" -> {
                    var url = p.get("url").getAsString(); var method = p.has("method") ? p.get("method").getAsString() : "GET";
                    var body = p.has("body") ? p.get("body").getAsString() : null;
                    var headers = p.has("headers") ? p.getAsJsonObject("headers") : new JsonObject();
                    var responseType = p.has("responseType") ? p.get("responseType").getAsString() : "text";
                    var cb = p.get("cb").getAsString();
                    ioExecutor.submit(() -> { var result = handleHttpRequest(url, method, body, headers, responseType); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cb, toJsonValue(result))); });
                    yield null;
                }
                default -> gson.toJson(Map.of("err", "Unknown http op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    private String handleHttpRequest(String url, String method, String body, JsonObject headers, String responseType) {
        try {
            var uri = URI.create(url); var conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod(method.toUpperCase()); conn.setConnectTimeout(5000); conn.setReadTimeout(10000);
            if (headers != null) headers.entrySet().forEach(e -> conn.setRequestProperty(e.getKey(), e.getValue().getAsString()));
            if (body != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                conn.setDoOutput(true); conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            conn.connect();
            var status = conn.getResponseCode();
            java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            var responseBody = "";
            if (is != null) {
                var bytes = is.readAllBytes();
                responseBody = "base64".equalsIgnoreCase(responseType)
                    ? Base64.getEncoder().encodeToString(bytes)
                    : new String(bytes, StandardCharsets.UTF_8);
            }
            var responseHeaders = new LinkedHashMap<String, String>();
            conn.getHeaderFields().forEach((k,v) -> { if (k != null) responseHeaders.put(k.toLowerCase(), String.join(", ", v)); });
            return gson.toJson(Map.of("status", status, "body", responseBody, "headers", responseHeaders));
        } catch (Exception e) { return gson.toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    private String handleAssets(String pld) {
        try {
            var obj = gson.fromJson(pld, JsonObject.class); var task = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
            var rawPath = p.get("path").getAsString();
            var dest = p.has("dest") ? p.get("dest").getAsString() : (rawPath.startsWith("assets/") ? rawPath : "assets/" + rawPath);
            if (devAssetsDir != null) {
                var stripped = rawPath.startsWith("assets/") ? rawPath.substring("assets/".length()) : rawPath;
                // dev 资产路径防护：规范化后必须仍在 devAssetsDir 内（../ 逃逸检查）
                var devBase = Path.of(devAssetsDir).toAbsolutePath().normalize();
                var fp = devBase.resolve(stripped).normalize();
                if (!fp.startsWith(devBase)) return gson.toJson(Map.of("err", "Invalid asset path: " + rawPath));
                return switch (task) {
                    case "read" -> { if (!Files.exists(fp)) yield "null"; yield gson.toJson(Map.of("data", Files.readString(fp))); }
                    case "readBase64" -> { if (!Files.exists(fp)) yield "null"; yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(Files.readAllBytes(fp)))); }
                    case "extract" -> {
                        if (!Files.exists(fp)) yield gson.toJson(Map.of("err", "Asset not found: " + rawPath));
                        var target = resolvePath(Path.of("plugins", name).toAbsolutePath().normalize(), dest);
                        assertNotRuntimeDir(target);
                        Files.copy(fp, target, StandardCopyOption.REPLACE_EXISTING);
                        yield gson.toJson(Map.of("path", target.toString()));
                    }
                    case "extractDir" -> {
                        if (!Files.isDirectory(fp)) yield gson.toJson(Map.of("err", "Asset directory not found: " + rawPath));
                        var target = resolvePath(Path.of("plugins", name).toAbsolutePath().normalize(), dest);
                        assertNotRuntimeDir(target);
                        copyDirRecursive(fp, target);
                        yield gson.toJson(Map.of("path", target.toString()));
                    }
                    default -> gson.toJson(Map.of("err", "Unknown assets op: " + task));
                };
            }
            try (var zip = new ZipFile(jarPath)) {
                return switch (task) {
                    case "read" -> { var entry = zip.getEntry(rawPath); if (entry == null) yield "null"; yield gson.toJson(Map.of("data", new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))); }
                    case "readBase64" -> { var entry = zip.getEntry(rawPath); if (entry == null) yield "null"; yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(zip.getInputStream(entry).readAllBytes()))); }
                    case "extract" -> {
                        var entry = zip.getEntry(rawPath);
                        if (entry == null || entry.isDirectory()) yield gson.toJson(Map.of("err", "Asset not found: " + rawPath));
                        var target = resolvePath(Path.of("plugins", name).toAbsolutePath().normalize(), dest);
                        assertNotRuntimeDir(target);
                        Files.copy(zip.getInputStream(entry), target, StandardCopyOption.REPLACE_EXISTING);
                        yield gson.toJson(Map.of("path", target.toString()));
                    }
                    case "extractDir" -> {
                        var prefix = rawPath.endsWith("/") ? rawPath : rawPath + "/";
                        var base = Path.of("plugins", name).toAbsolutePath().normalize();
                        var target = resolvePath(base, dest);
                        assertNotRuntimeDir(target);
                        var found = false;
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            var ze = entries.nextElement();
                            var zn = ze.getName();
                            if (!zn.startsWith(prefix) || ze.isDirectory()) continue;
                            found = true;
                            var rel = zn.substring(prefix.length());
                            var dst = target.resolve(rel).normalize();
                            // zip-slip 防护：entry 相对路径含 ../ 时不得逃逸目标目录
                            if (!dst.startsWith(target)) throw new SecurityException("Zip entry escapes target dir: " + zn);
                            assertNotRuntimeDir(dst);
                            Files.createDirectories(dst.getParent());
                            Files.copy(zip.getInputStream(ze), dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (!found) yield gson.toJson(Map.of("err", "Asset directory not found: " + rawPath));
                        yield gson.toJson(Map.of("path", target.toString()));
                    }
                    default -> gson.toJson(Map.of("err", "Unknown assets op: " + task));
                };
            }
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /**
     * 主插件错误上报：origin = "main"（dev-server 契约：'main' = 主插件 bundle，
     * 其他值 = worker 名，对应各自 source-map 产物）。
     */
    private void handleJSReport(String pld) { handleJSReport(pld, "main"); }

    private void handleJSReport(String pld, String origin) {
        try {
            var json = gson.fromJson(pld, JsonObject.class);
            var msg = json.has("message") ? json.get("message").getAsString() : "unknown";
            var stack = json.has("stack") ? json.get("stack").getAsString() : "";
            var fileName = json.has("fileName") && !json.get("fileName").isJsonNull() ? json.get("fileName").getAsString() : "main.js";
            var line = json.has("lineNumber") ? json.get("lineNumber").getAsInt() : 0;
            var col = json.has("columnNumber") ? json.get("columnNumber").getAsInt() : 0;
            if (devMode) {
                var errPayload = new LinkedHashMap<String,Object>();
                errPayload.put("type", "js-error"); errPayload.put("plugin", name); errPayload.put("message", msg);
                errPayload.put("origin", origin); // main 或 worker 名（dev-server 按 origin 选 source-map）
                if (json.has("context") && !json.get("context").isJsonNull()) errPayload.put("context", json.get("context").getAsString());
                var stackToSend = (stack != null && !stack.isEmpty()) ? stack : msg + (fileName != null && line > 0 ? "\n    at " + fileName + ":" + line + ":" + col : "");
                errPayload.put("stack", stackToSend);
                errPayload.put("fileName", fileName); errPayload.put("lineNumber", line); errPayload.put("columnNumber", col);
                var rt = core;
                if (rt != null) rt.sendDevMessage(gson.toJson(errPayload));
            }
            var loc = fileName != null && line > 0 ? " at " + fileName + ":" + line + ":" + col : "";
            var sb = "[" + name + (origin != null && !origin.equals(name) ? ":" + origin : "") + "] JS Error: " + msg + loc;
            if (stack != null && !stack.isEmpty()) {
                var limit = stack.lines().limit(3).collect(java.util.stream.Collectors.joining("\n[" + name + "]   "));
                sb += "\n[" + name + "]   " + limit;
            }
            log.warning(sb);
        } catch (Exception ex) { log.warning("[" + name + "] JS Error: " + pld); }
    }

    /**
     * util 通道：gzip.compress / gzip.decompress / encode.utf8 / decode.utf8。
     * 字节数据一律以 base64 字符串承载（encode/decode 语义 = buffer ↔ UTF-8 字符串，
     * base64 只是承载形式）；无流式接口，一次性整体处理。
     */
    private String handleUtil(String pld) {
        try {
            var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
            var t = obj.get("t").getAsString();
            var p = obj.has("p") ? obj.getAsJsonObject("p") : new JsonObject();
            var data = p.get("data").getAsString();
            // 输入上限（base64 字符数，≈48 MiB 原始字节）：防内存炸弹
            if (data.length() > MAX_UTIL_INPUT_B64)
                throw new IllegalArgumentException("util input exceeds " + MAX_UTIL_INPUT_B64 + " b64 chars");
            return switch (t) {
                case "gzip.compress" -> {
                    var level = p.has("level") ? p.get("level").getAsInt() : -1;
                    if (level < 0 || level > 9) throw new IllegalArgumentException("level must be 0-9");
                    var raw = Base64.getDecoder().decode(data);
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(yeow.util.UtilCodec.gzip(raw, level))));
                }
                case "gzip.decompress" -> {
                    var raw = Base64.getDecoder().decode(data);
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(yeow.util.UtilCodec.gunzip(raw, MAX_UTIL_OUTPUT_BYTES))));
                }
                // 字符串 → 字节（b64 承载）
                case "encode.utf8" -> gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(yeow.util.UtilCodec.utf8(data))));
                // 字节（b64 承载）→ 字符串（非法 UTF-8 序列替换为 U+FFFD）
                case "decode.utf8" -> {
                    var raw = Base64.getDecoder().decode(data);
                    yield gson.toJson(Map.of("data", yeow.util.UtilCodec.utf8(raw)));
                }
                default -> throw new IllegalArgumentException("Unknown util op: " + t);
            };
        } catch (Exception e) {
            return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private String handleService(String pld) {
        try {
            var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
            var t = obj.get("t").getAsString();
            var sm = core.serviceManager();
            return switch (t) {
                case "register" -> { var refName = obj.get("refName").getAsString(); var onReq = obj.get("onRequest").getAsString(); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerPluginService(refName, name, onReq, isPublic); }
                case "registerNative" -> { var refName = obj.get("refName").getAsString(); var platforms = obj.getAsJsonObject("platforms"); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerNativeService(refName, name, platforms, isPublic, jarPath, devAssetsDir, nativeHashes); }
                case "registerNativeTerminate" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.registerTerminateCb(svcId, cbId, name); yield "true"; }
                case "request" -> { var svcId = obj.get("serviceId").getAsString(); var path = obj.has("path") ? obj.get("path").getAsString() : "/"; var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); var reqId = obj.get("requestId").getAsString(); sm.trackRequestConsumer(reqId, name, svcId); sm.request(svcId, path, body, reqId, name); yield null; }
                case "awaitReady" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.awaitReady(svcId, cbId, name); yield null; }
                case "response" -> { var reqId = obj.get("requestId").getAsString(); var result = obj.has("body") ? gson.fromJson(obj.get("body").toString(), Object.class) : null; sm.respond(reqId, name, result); yield null; }
                case "subscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var cbId = obj.get("cb").getAsString(); sm.subscribe(svcId, eventPath, cbId, name); yield "true"; }
                case "unsubscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); sm.unsubscribe(svcId, eventPath, name); yield "true"; }
                case "publish" -> { var token = obj.get("token").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); sm.publish(token, eventPath, body); yield "true"; }
                default -> gson.toJson(Map.of("err", "Unknown service op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /** 主插件错误上报（origin = "main"，见 {@link #handleJSReport(String)}）。 */
    private void handleJSError(QuickJSException e) { handleJSError(e, "main"); }

    private void handleJSError(QuickJSException e, String origin) {
        try {
            var msgText = e.getMessage();
            String msg, stack, fileName;
            int line, col;
            if (msgText != null) {
                var colonIdx = msgText.indexOf(':');
                if (colonIdx > 0) {
                    var afterColon = msgText.substring(colonIdx + 1).trim();
                    if (afterColon.startsWith("{")) msgText = afterColon;
                }
            }
            if (msgText != null && msgText.startsWith("{")) {
                try {
                    var json = gson.fromJson(msgText, JsonObject.class);
                    msg = json.has("message") ? json.get("message").getAsString() : msgText;
                    stack = json.has("stack") ? json.get("stack").getAsString() : "";
                    fileName = json.has("fileName") && !json.get("fileName").isJsonNull() ? json.get("fileName").getAsString() : "main.js";
                    line = json.has("lineNumber") ? json.get("lineNumber").getAsInt() : 0;
                    col = json.has("columnNumber") ? json.get("columnNumber").getAsInt() : 0;
                } catch (Exception ignored) { msg = msgText; stack = ""; fileName = "main.js"; line = 0; col = 0; }
            } else {
                var lines = msgText != null ? msgText.split("\n") : new String[]{""};
                msg = lines.length > 0 ? lines[0].trim() : msgText;
                stack = msgText != null ? msgText : "";
                fileName = "main.js"; line = 0; col = 0;
                for (var l : lines) {
                    var m = java.util.regex.Pattern.compile("\\(?([^\\s(]+):(\\d+):(\\d+)\\)?$").matcher(l.trim());
                    if (m.find()) { fileName = m.group(1); line = Integer.parseInt(m.group(2)); col = Integer.parseInt(m.group(3)); break; }
                }
            }
            if (devMode) {
                var errPayload = new LinkedHashMap<String,Object>();
                errPayload.put("type", "js-error"); errPayload.put("plugin", name); errPayload.put("message", msg != null ? msg : "");
                errPayload.put("origin", origin);
                var stackToSend = (stack != null && !stack.isEmpty()) ? stack : (msg != null ? msg : "") + (fileName != null && line > 0 ? "\n    at " + fileName + ":" + line + ":" + col : "");
                errPayload.put("stack", stackToSend);
                errPayload.put("fileName", fileName != null ? fileName : "main.js");
                errPayload.put("lineNumber", line); errPayload.put("columnNumber", col);
                var rt = core;
                if (rt != null) rt.sendDevMessage(gson.toJson(errPayload));
            }
            var loc = fileName != null && line > 0 ? " at " + fileName + ":" + line + ":" + col : "";
            var sb = "[" + name + (origin != null && !origin.equals(name) ? ":" + origin : "") + "] JS Error: " + (msg != null ? msg : "unknown") + loc;
            if (stack != null && !stack.isEmpty()) {
                var limit = stack.lines().limit(3).collect(java.util.stream.Collectors.joining("\n[" + name + "]   "));
                sb += "\n[" + name + "]   " + limit;
            }
            log.warning(sb);
        } catch (Exception ignored) { log.warning("[" + name + "] JS Error: " + e.getMessage()); }
    }
}
