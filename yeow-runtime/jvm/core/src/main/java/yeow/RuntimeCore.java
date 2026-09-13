package yeow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

/**
 * 平台无关的运行时核心：插件注册表、包解析加载、任务提交、生命周期、dev WebSocket、
 * 原生服务策略。唯一平台依赖经 {@link PlatformHost}（构造注入）。
 *
 * Paper/Bukkit 的 {@code yeow.paper.YeowRuntime} 是宿主适配层：负责 JavaPlugin
 * 生命周期、tick 驱动、事件/命令桥与 /yeow 管理命令的绑定，其余全部委托本类。
 */
public class RuntimeCore {
    private static final Logger LOG = Logger.getLogger("Yeow");
    private static final Gson gson = new Gson();

    private final PlatformHost host;
    private final YeowConfig config;
    private final TaskScheduler scheduler;
    private final yeow.service.ServiceManager serviceManager;
    private final yeow.profile.Profiler profiler;
    private final ConcurrentHashMap<String, PluginEntity> plugins = new ConcurrentHashMap<>();
    private final InstanceRegistry instances = new InstanceRegistry();
    private final String initCode;
    private final boolean devMode;
    private WebSocket devWs;

    public RuntimeCore(PlatformHost host, YeowConfig config, TaskScheduler scheduler) {
        this.host = host;
        this.devMode = "true".equals(System.getProperty("yeow.dev"));
        this.config = config;
        this.scheduler = scheduler;
        this.serviceManager = new yeow.service.ServiceManager(this::getPlugin);
        this.profiler = yeow.profile.Profiler.create(yeow.profile.ProfileConfig.from(config), host.dataFolder());
        this.profiler.setAutoReloadAction(this::handleAutoReload);
        // 调度器插桩（ProfileSink/BudgetScaler）由平台在构造其调度器时装配
        // 引导脚本：polyfill.js（TextEncoder/TextDecoder/fetch 等纯 JS Polyfill）在前，
        // init.js（桥闭包/回调注册表/消息循环）在后——单脚本顺序执行，共享同一脚本作用域。
        String code = null;
        try {
            String polyfill = null;
            try (var is = RuntimeCore.class.getResourceAsStream("/js/polyfill.js")) {
                if (is != null) polyfill = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (var is = RuntimeCore.class.getResourceAsStream("/js/init.js")) {
                code = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (polyfill != null) code = polyfill + "\n" + code;
        } catch (Exception e) {
            host.logger().warning("Failed to load bootstrap scripts: " + e.getMessage());
        }
        this.initCode = code;
        if (devMode) connectDevWebSocket();
    }

    // ── 访问器（宿主适配层 / 任务类共用） ────────────────────────────

    public PlatformHost host() { return host; }
    public YeowConfig config() { return config; }
    public TaskScheduler scheduler() { return scheduler; }
    public yeow.service.ServiceManager serviceManager() { return serviceManager; }
    public yeow.profile.Profiler profiler() { return profiler; }
    public boolean devMode() { return devMode; }
    public PluginEntity getPlugin(String name) { return plugins.get(name); }

    /** JS 句柄实例注册表（id → 释放器；平台注册闭包，id 不携带业务信息）。 */
    public InstanceRegistry instances() { return instances; }

    /**
     * 热重载强杀后的实体重建：全新 PluginThread（新线程/新队列/新上下文），
     * 保留版本/作者/权限与原生哈希声明、内存包、dev 资产目录与 dev 模式。
     * 旧实体被遗弃——其卡死的 JS 线程无法在进程内回收（需原生中断支持），
     * 但已从注册表移除、不再被投递消息，且线程为 daemon。
     */
    public boolean rebuildPluginEntity(String name, String newCode) {
        var old = plugins.remove(name);
        if (old == null) return false;
        if (!(old instanceof PluginThread pt)) { old.stopAndWait(); return false; }
        host.purgePlatformResources(name);
        if (serviceManager != null) serviceManager.purgePluginServices(name);
        if (profiler != null) profiler.unregisterPlugin(name);
        var fresh = new PluginThread(name, pt.version(), pt.author(), pt.source(), pt.pluginPackage(), initCode, newCode, this, pt.permissions(), pt.nativeManifest());
        fresh.setDevAssetsDir(pt.getDevAssetsDir());
        fresh.setDevMode(pt.isDevMode());
        return registerPluginEntity(fresh, false);
    }

    /** 仅从注册表移除（不等待/不清理——调用方已处理；用于强杀后的实体重建）。 */
    public void unregisterPluginEntity(String name) {
        var pt = plugins.remove(name);
        if (pt != null && profiler != null) profiler.unregisterPlugin(name);
    }

    /**
     * 挂起自恢复回调：由 {@link yeow.profile.RecoveryManager} 异步调用（非主线程）。
     * 语义：基于与告警分离的重载阈值（默认 120s），超过阈值自动重载。
     * 已在 RecoveryManager 完成阈值/冷却/重试/去重校验，此处仅执行重载。
     */
    private void handleAutoReload(String name) {
        // dev 模式下由 dev-server 热重载接管，跳过自动重载
        if (devMode) {
            LOG.info("[Yeow] auto-reload skipped for " + name + " (dev mode)");
            return;
        }
        var entity = plugins.get(name);
        if (entity == null) {
            LOG.warning("[Yeow] auto-reload: plugin not found: " + name);
            return;
        }
        if (entity.isVirtual()) {
            LOG.info("[Yeow] auto-reload skipped for virtual plugin: " + name);
            return;
        }
        LOG.warning("[Yeow] auto-reload: reloading hung plugin " + name);
        boolean ok = reloadPlugin(name, null);
        if (ok) {
            LOG.info("[Yeow] auto-reload: " + name + " reloaded successfully");
        } else {
            LOG.warning("[Yeow] auto-reload: " + name + " reload failed — will retry after cooldown (max "
                + config.profileAutoReloadMaxRetries() + " attempts)");
        }
    }

    /** 非虚拟插件名列表（/yeow 管理命令不覆盖虚拟插件/Worker）。 */
    public java.util.List<String> realPluginNames() {
        var out = new java.util.ArrayList<String>();
        for (var e : plugins.entrySet()) {
            if (!e.getValue().isVirtual()) out.add(e.getKey());
        }
        return out;
    }

    // ── 生命周期 ──────────────────────────────────────────────────

    /** 启动服务子系统与调度线程（宿主在装配完平台 pump 后调用）。 */
    public void start() {
        try { serviceManager.start(); } catch (Exception e) { LOG.warning("Failed to start native service TCP: " + e.getMessage()); }
        scheduler.start();
    }

    /** 扫描数据目录 *.yeow.zip 自动加载。 */
    public void scanPluginDirectory() {
        var dir = host.dataFolder();
        if (!dir.exists()) return;
        var files = dir.listFiles((d, n) -> n.endsWith(".yeow.zip"));
        if (files == null) return;
        for (var f : files) {
            LOG.info("Auto-loading plugin package: " + f.getName());
            registerPlugin(f.getAbsolutePath());
        }
    }

    /** 向所有已注册插件发送 LOAD（宿主在 tick 驱动就绪后调用一次）。 */
    public void loadAllPlugins() {
        for (var pt : plugins.values()) {
            pt.postMessage(gson.toJson(Map.of("t", "LOAD")));
        }
    }

    /** 关闭：WS、profile、插件、调度器、服务。 */
    public void shutdown() {
        if (devWs != null) try { devWs.sendClose(1000, "shutdown"); } catch (Exception ignored) {}
        if (profiler != null) profiler.close();
        instances.clear();
        plugins.values().forEach(PluginEntity::stopAndWait);
        scheduler.shutdown();
        if (serviceManager != null) serviceManager.shutdown();
    }

    // ── 插件注册 / 卸载 / 重载 ─────────────────────────────────────

    /**
     * Load a Yeow package (template JAR or .yeow.zip). The plugin name comes from yeow.json.
     * One plugin name may only have a single instance - a duplicate load is rejected with a warning.
     *
     * @param sendLoad whether to send the LOAD message right away (runtime load/reload).
     *                 Startup registration (template JARs / auto-scan) passes false - the
     *                 host's LOAD loop sends LOAD to all plugins once the scheduler is ticking.
     * @return true if the plugin was loaded, false if skipped (duplicate) or failed
     */
    public boolean registerPlugin(String jarPath, boolean sendLoad) {
        // 插件包内存镜像：加载时一步到位——yeow.json / main.js 解析与后续
        // assets 通道、原生二进制解压共用同一份内存（零重复 open/解析）。
        // 缓存关闭（assets.cache-enabled=false）、包超过阈值（assets.cache-max-bytes）
        // 或加载失败（ZIP64/非 zip）时回退 ZipFile 直读。
        PluginPackage pkg = null;
        long cacheMax = config.assetsCacheMaxBytes();
        long pkgSize = new File(jarPath).length();
        boolean withinLimit = cacheMax <= 0 || pkgSize <= cacheMax;
        if (config.assetsCacheEnabled() && !withinLimit) {
            LOG.info("Asset cache skipped for " + jarPath + " (" + (pkgSize / (1024 * 1024)) + " MB > "
                + (cacheMax / (1024 * 1024)) + " MB) - falling back to direct read");
        }
        if (config.assetsCacheEnabled() && withinLimit) {
            try {
                pkg = PluginPackage.load(Path.of(jarPath));
            } catch (Exception e) {
                LOG.warning("Asset cache disabled for " + jarPath + " (" + e.getMessage() + ") - falling back to direct read");
            }
        }
        final PluginPackage P = pkg;
        ZipFile direct = null;
        try {
            final java.util.function.Function<String, String> read;
            if (P != null) {
                read = name -> readPkgText(P, name);
            } else {
                direct = new ZipFile(jarPath);
                final ZipFile z = direct;
                read = name -> readZipEntry(z, name);
            }

            var meta = read.apply("yeow.json");
            var name = "unknown";
            var version = "";
            var author = "";
            var perms = new LinkedHashSet<String>();
            var natives = NativeManifest.EMPTY; // yeow.json native 声明（serviceId + 打包后路径 → SHA-256）
            if (meta != null) {
                var obj = new Gson().fromJson(meta, JsonObject.class);
                if (obj.has("name")) name = obj.get("name").getAsString();
                if (obj.has("version")) version = obj.get("version").getAsString();
                if (obj.has("author")) author = obj.get("author").getAsString();
                // 最终权限由构建器计算（合并依赖包声明 + 通配归一化）写入 computedPermissions。
                // v0 阶段不做旧包兼容--旧格式包（仅 permissions）视为无权限。
                if (obj.has("computedPermissions") && obj.get("computedPermissions").isJsonArray()) {
                    for (var el : obj.getAsJsonArray("computedPermissions")) perms.add(el.getAsString());
                }
                // 原生服务可信性声明（构建时由 yeow.config.json 的 native 计算）：作为插件元数据保存
                natives = NativeManifest.parse(obj.get("native"));
            }

            if (plugins.containsKey(name)) {
                LOG.warning("Duplicate plugin load rejected: " + name + " is already loaded (source: " + jarPath + ")");
                return false;
            }

            // 原生服务策略（加载层）：申请了 `service:registerNative` 权限即视为将运行不可信二进制。
            // config.yml `native-service-allow-untrusted`（默认 true）为 false 时拒绝加载；
            // 为 true 时正常加载并打印醒目警告。yeow.config.json 的 `native` 声明与
            // SHA-256 校验不受此开关影响（注册原生服务时始终校验）。
            boolean wantsNative = perms.contains("service:registerNative") || perms.contains("service:*");
            // 强制声明（加载层）：申请了原生服务权限的插件，其 yeow.json native 清单不得为空
            // （yeow.config.json 必须声明 native，构建时写入）。声明 ≠ 可信——见下方 untrusted 开关。
            if (wantsNative && natives.isEmpty()) {
                LOG.severe("\n" + "=".repeat(60)
                    + "\n  [Yeow] " + name + " requests NATIVE SERVICES but declares NO `native` manifest"
                    + "\n  and was REFUSED to load."
                    + "\n  Declare `native` in yeow.config.json (serviceId + binary files) so every"
                    + "\n  binary is pinned by SHA-256, then rebuild."
                    + "\n" + "=".repeat(60));
                return false;
            }
            if (wantsNative && !config.nativeServiceAllowUntrusted()) {
                LOG.severe("\n" + "=".repeat(60)
                    + "\n  [Yeow] " + name + " requests NATIVE SERVICES and was REFUSED to load"
                    + "\n  (config `native-service-allow-untrusted: false` - untrusted binaries are not allowed)."
                    + "\n  To allow it, set `native-service-allow-untrusted: true` in plugins/Yeow/runtime/config.yml"
                    + "\n" + "=".repeat(60));
                return false;
            }

            String userCode;
            String devAssetsDir = null;

            // Check for dev mode - .yeow/dev.json contains compiled code path
            var devMeta = read.apply(".yeow/dev.json");
            if (devMode && devMeta != null) {
                var devObj = new Gson().fromJson(devMeta, JsonObject.class);
                // Read compiled code from the file path stored in dev.json
                var codeFile = Path.of(devObj.get("codeFile").getAsString());
                userCode = Files.readString(codeFile);
                if (devObj.has("assetsDir") && !devObj.get("assetsDir").isJsonNull()) {
                    devAssetsDir = devObj.get("assetsDir").getAsString();
                }
                LOG.info("Dev mode: reading code from " + codeFile);
            } else {
                var code = read.apply(".yeow/main.js");
                if (code == null) { LOG.severe("Missing .yeow/main.js in " + jarPath); return false; }
                userCode = code;
            }

            var pt = new PluginThread(name, version, author, jarPath, P, initCode, userCode, this, perms, natives);
            if (devAssetsDir != null) pt.setDevAssetsDir(devAssetsDir);
            if (devMode) pt.setDevMode(true);
            if (!registerPluginEntity(pt, sendLoad)) return false;
            if (devMode) LOG.info("Dev mode active for " + name);
            LOG.info("Loaded plugin: " + name + (version.isEmpty() ? "" : " v" + version)
                + (author.isEmpty() ? "" : " by " + author)
                + " - permissions: " + displayPermissions(perms));
            if (wantsNative && config.nativeServiceAllowUntrusted()) {
                var trust = natives.files().isEmpty()
                    ? "NO SHA-256 pinned (no `native` declaration in yeow.config.json)"
                    : natives.files().size() + " file(s) SHA-256 pinned (verified when the service registers)";
                LOG.warning("\n" + "!".repeat(60)
                    + "\n  [Yeow] " + name + " runs UNTRUSTED NATIVE binaries as child processes"
                    + "\n  " + trust
                    + "\n  Only install plugins from sources you trust."
                    + "\n  (Online safety check against the official safety list is planned;"
                    + "\n   listed binaries will load without this warning.)"
                    + "\n" + "!".repeat(60));
            }
            return true;
        } catch (Exception e) {
            LOG.severe("Failed to register plugin " + jarPath + ": " + e.getMessage());
            return false;
        } finally {
            if (direct != null) try { direct.close(); } catch (Exception ignored) {}
        }
    }

    /** 内存包文本读取（缺失返回 null；损坏按缺失处理，调用方按原有语义报错）。 */
    private static String readPkgText(PluginPackage pkg, String entry) {
        try {
            var b = pkg.read(entry);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** ZipFile 直读文本（缺失返回 null）。 */
    private static String readZipEntry(ZipFile zip, String entry) {
        try {
            var e = zip.getEntry(entry);
            if (e == null || e.isDirectory()) return null;
            return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 插件实体注册（运行时内部：JS 插件 {@link PluginThread} 与 Worker 虚拟插件
     * {@link WorkerThread} 共用），接入统一运行时链路：同名唯一检查、Profile 指标、
     * 生命周期（start + LOAD）。
     *
     * 实体负责：包结构/引擎封装（postMessage 消化消息契约）、ping 实现；
     * 运行时负责：注册、去重、启动、卸载清理（/yeow unload、服务/事件清理）、指标采集。
     *
     * @param entity    已构造的插件实体（未启动；name() 必须非空且全局唯一）
     * @param sendLoad  是否立即发送 LOAD 生命周期消息
     * @return true 注册成功；false 同名冲突或参数非法
     */
    public boolean registerPluginEntity(PluginEntity entity, boolean sendLoad) {
        if (entity == null || entity.name() == null || entity.name().isEmpty()) {
            LOG.severe("Plugin entity registration rejected: name is required");
            return false;
        }
        var name = entity.name();
        if (plugins.containsKey(name)) {
            LOG.warning("Duplicate plugin load rejected: " + name + " is already loaded");
            return false;
        }
        plugins.put(name, entity);
        if (profiler != null) profiler.registerPlugin(entity);
        entity.start();
        if (sendLoad) entity.postMessage(new Gson().toJson(Map.of("t", "LOAD")));
        LOG.info("Loaded plugin (entity): " + name + " (" + entity.type() + ")"
            + (entity.isVirtual() ? " [virtual]" : "")
            + (entity.source() != null ? " - source: " + entity.source() : ""));
        return true;
    }

    /**
     * 运行时级游戏任务提交--插件实体（JS 插件 / Worker）提交游戏任务的统一入口
     * （等价于 JS 的 `$_send('task', ...)`）。
     * 回调约定：payload 含 `cb` 字段时异步执行（立即返回 null），结果经
     * {@link PluginEntity#postMessage} 回投 `{"t":"cb","p":"<cbId>","r":<data>}`；
     * 无 `cb` 时同步阻塞返回结果 JSON。`cbId` 由调用方自行生成与管理。
     *
     * **批量扩展**：payload 含 `tasks` 数组（`[{type, params, priority?}, ...]`）时执行批处理——
     * 按顺序向调度器提交全部任务、收集结果数组一次返回（同步阻塞 / 异步回调
     * `r` 为结果数组）。任务逐个独立执行，无原子性。
     *
     * @param entity  提交方实体（注册表中的插件）
     * @param message 任务消息：JSON 字符串，或 POJO（**直接使用**，避免序列化开销--
     *               gson `JsonObject` 零转换直接执行；一般 POJO 由运行时一次转换）
     * @return 结果原始对象（同步）或 null（异步）；序列化由通信层负责
     */
    public Object submitTask(PluginEntity entity, Object message) {
        if (entity == null) return Map.of("err", "unknown plugin entity");
        try {
            JsonObject obj;
            if (message instanceof String s) {
                obj = gson.fromJson(s.isEmpty() ? "{}" : s, JsonObject.class);
            } else if (message instanceof JsonObject jo) {
                obj = jo; // 直接使用，零序列化
            } else {
                obj = gson.toJsonTree(message).getAsJsonObject(); // 一般 POJO 一次转换
            }
            if (obj.has("tasks") && obj.get("tasks").isJsonArray()) {
                return submitTasks(entity, obj); // 批量：tasks 数组
            }
            var taskType = obj.get("type").getAsString();
            var params = obj.has("params") ? obj.getAsJsonObject("params") : new JsonObject();
            params.addProperty("_plugin", entity.name()); // ownership for per-plugin cleanup (gui/bossbar etc.)
            // 空字符串 cb（如 `cb: ''` 表示"同步执行，不关心结果"）不视为异步--
            // 否则 cbId 为空字符串，结果回投匹配不到任何注册的 pend（事件/补全完成会永久超时）。
            var hasCb = obj.has("cb") && !obj.get("cb").getAsString().isEmpty();
            var priority = parsePriority(obj.has("priority") ? obj.get("priority").getAsString() : null);
            if (hasCb) {
                var cbId = obj.get("cb").getAsString();
                scheduler.submitGameAsync(taskType, params, r -> entity.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(cbId, r)), priority, entity.name());
                return null;
            }
            var future = new java.util.concurrent.CompletableFuture<Object>();
            scheduler.submitGameSync(taskType, params, future, priority, entity.name());
            try { return future.get(config.taskSyncTimeoutMs(), TimeUnit.MILLISECONDS); }
            catch (Exception e) { return Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()); }
        } catch (Exception e) {
            return Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** 批量任务错误项：与执行器 errObject 同形状（含 type/task），避免同一数组内两种错误形状并存。 */
    private static Map<String, Object> batchErr(Exception e, String taskType) {
        var m = new LinkedHashMap<String, Object>();
        m.put("err", e.getMessage() != null ? e.getMessage() : e.toString());
        m.put("type", e.getClass().getSimpleName());
        if (taskType != null) m.put("task", taskType);
        return m;
    }

    /**
     * 批量任务：按顺序提交 `tasks` 数组，结果按原顺序收集（同步阻塞返回结果数组 JSON；
     * 含非空 `cb` 时异步——全部完成后一次回调结果数组）。任务逐个独立执行，无原子性。
     */
    private Object submitTasks(PluginEntity entity, JsonObject obj) {
        var tasks = obj.getAsJsonArray("tasks");
        var hasCb = obj.has("cb") && !obj.get("cb").getAsString().isEmpty();
        if (hasCb) {
            var cbId = obj.get("cb").getAsString();
            submitTasksAsync(entity, tasks, cbId);
            return null;
        }
        var out = new java.util.ArrayList<Object>();
        for (var el : tasks) {
            String taskType = null;
            Object item;
            try {
                var t = el.getAsJsonObject();
                taskType = t.get("type").getAsString();
                var params = t.has("params") ? t.getAsJsonObject("params") : new JsonObject();
                params.addProperty("_plugin", entity.name());
                var priority = parsePriority(t.has("priority") ? t.get("priority").getAsString() : null);
                var future = new java.util.concurrent.CompletableFuture<Object>();
                scheduler.submitGameSync(taskType, params, future, priority, entity.name());
                try {
                    item = future.get(config.taskSyncTimeoutMs(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    item = batchErr(e, taskType);
                }
            } catch (Exception e) {
                item = batchErr(e, taskType);
            }
            out.add(item);
        }
        return out;
    }

    /** 批量异步：全部任务完成后一次回调结果数组（按提交顺序）。 */
    private void submitTasksAsync(PluginEntity entity, JsonArray tasks, String cbId) {
        int n = tasks.size();
        if (n == 0) { entity.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(cbId, java.util.List.of())); return; }
        var results = new Object[n];
        var pending = new java.util.concurrent.atomic.AtomicInteger(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            String taskType = null;
            try {
                var t = tasks.get(i).getAsJsonObject();
                taskType = t.get("type").getAsString();
                var params = t.has("params") ? t.getAsJsonObject("params") : new JsonObject();
                params.addProperty("_plugin", entity.name());
                var priority = parsePriority(t.has("priority") ? t.get("priority").getAsString() : null);
                scheduler.submitGameAsync(taskType, params, r -> {
                    results[idx] = r;
                    if (pending.decrementAndGet() == 0) {
                        entity.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(cbId, java.util.Arrays.asList(results)));
                    }
                }, priority, entity.name());
            } catch (Exception e) {
                results[idx] = batchErr(e, taskType);
                if (pending.decrementAndGet() == 0) {
                    entity.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(cbId, java.util.Arrays.asList(results)));
                }
            }
        }
    }

    private static TaskScheduler.Priority parsePriority(String s) {
        if (s == null) return TaskScheduler.Priority.NORMAL;
        return switch (s.toLowerCase()) { case "high" -> TaskScheduler.Priority.HIGH; case "low" -> TaskScheduler.Priority.LOW; default -> TaskScheduler.Priority.NORMAL; };
    }

    /**
     * 权限清单的展示形态：`fs:*` 展开为 `fs:outer.*, fs:server.*`（服主对 fs:*
     * 无感，看不出具体影响范围）。仅影响打印，权限校验仍按原值（fs:*）进行。
     */
    private static String displayPermissions(java.util.Set<String> perms) {
        var out = new java.util.ArrayList<String>();
        for (var p : perms) {
            if ("fs:*".equals(p)) { out.add("fs:outer.*"); out.add("fs:server.*"); }
            else out.add(p);
        }
        return String.join(", ", out);
    }

    public boolean registerPlugin(String jarPath) { return registerPlugin(jarPath, false); }

    public void enablePlugin(String name) {
        var pt = plugins.get(name);
        if (pt != null && !pt.isRunning()) pt.start();
    }

    /**
     * Unload a plugin: purge platform resources, wait up to 5s for the JS thread
     * to exit (force-kill if hung), then remove it from the registry.
     */
    public boolean unloadPlugin(String name) {
        var pt = plugins.remove(name);
        if (pt == null) return false;
        host.purgePlatformResources(name);
        if (serviceManager != null) serviceManager.purgePluginServices(name);
        pt.stopAndWait();
        if (profiler != null) profiler.unregisterPlugin(name);
        LOG.info("Unloaded plugin: " + name);
        return true;
    }

    public void disablePlugin(String name) { unloadPlugin(name); }

    /**
     * Reload a plugin from its original source (or a given path/url). The old instance is fully
     * unloaded (same 5s force-stop logic as hot reload) and the package is re-read from disk.
     * URL sources are downloaded to the cache (temporary - never persisted).
     */
    public boolean reloadPlugin(String name, String path) {
        var pt = plugins.get(name);
        if (pt == null) return false;
        var source = path != null ? path : pt.source();
        if (isHttpUrl(source)) {
            var cache = downloadPluginZip(source);
            if (cache == null) return false;
            source = cache.getAbsolutePath();
        } else {
            source = resolveServerPath(source);
        }
        var f = new File(source);
        if (!f.isFile()) { LOG.warning("Reload source not found: " + source); return false; }
        unloadPlugin(name);
        var ok = registerPlugin(f.getAbsolutePath(), true);
        if (ok) host.syncCommands();
        return ok;
    }

    private static String resolveServerPath(String p) {
        var path = Path.of(p);
        return (path.isAbsolute() ? path : Path.of(System.getProperty("user.dir"), p)).normalize().toString();
    }

    /**
     * Resolve a `/yeow load` target:
     * <ol>
     *   <li>as a server-relative / absolute path;</li>
     *   <li>else the same relative path under the runtime data dir (<code>plugins/Yeow</code>);</li>
     *   <li>else an installed <code>&lt;name&gt;-&lt;version&gt;.yeow.zip</code> in the data dir.</li>
     * </ol>
     * Name matching is **case-insensitive but prefers an exact-case match**; when several
     * versions match, the most recently modified file wins. Returns a readable file or null.
     */
    public static File resolveLoadTarget(String arg, File dataFolder) {
        if (arg == null || arg.isEmpty()) return null;

        var f = new File(resolveServerPath(arg));
        if (f.isFile()) return f;

        // Same relative path under plugins/Yeow (exact first, then case-insensitive basename)
        var exact = new File(dataFolder, arg);
        if (exact.isFile()) return exact;
        if (arg.indexOf('/') < 0 && arg.indexOf('\\') < 0) {
            var ci = findChildIgnoringCase(dataFolder, arg);
            if (ci != null && ci.isFile()) return ci;
        }

        // Installed `<name>-<version>.yeow.zip` (exact-case prefix preferred, newest wins)
        return findPackageIgnoringCase(dataFolder, arg);
    }

    /** Direct child of {@code dir} named {@code name}: exact case first, else {@code equalsIgnoreCase}. */
    private static File findChildIgnoringCase(File dir, String name) {
        if (dir == null || !dir.isDirectory()) return null;
        var children = dir.listFiles();
        if (children == null) return null;
        File ci = null;
        for (var c : children) {
            if (c.getName().equals(name)) return c;
            if (ci == null && c.getName().equalsIgnoreCase(name)) ci = c;
        }
        return ci;
    }

    /** `<name>-<version>.yeow.zip` in {@code dir}: case-insensitive prefix, exact case preferred, newest wins. */
    private static File findPackageIgnoringCase(File dir, String name) {
        if (dir == null || !dir.isDirectory()) return null;
        var files = dir.listFiles();
        if (files == null) return null;
        File exact = null, ci = null;
        for (var f : files) {
            var n = f.getName();
            if (!n.endsWith(".yeow.zip") || n.length() <= name.length() + 1) continue;
            if (n.charAt(name.length()) != '-') continue;
            var prefix = n.substring(0, name.length());
            if (prefix.equals(name)) {
                if (exact == null || f.lastModified() > exact.lastModified()) exact = f;
            } else if (prefix.equalsIgnoreCase(name)) {
                if (ci == null || f.lastModified() > ci.lastModified()) ci = f;
            }
        }
        return exact != null ? exact : ci;
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    /** Download a .yeow.zip to the runtime cache dir. Returns the cached file or null on failure. */
    public File downloadPluginZip(String url) {
        try {
            var cacheDir = new File(host.dataFolder(), ".cache");
            cacheDir.mkdirs();
            var tmp = File.createTempFile("dl-", ".yeow.zip", cacheDir);
            var conn = (java.net.HttpURLConnection) new java.net.URI(url).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            try (var in = conn.getInputStream()) {
                java.nio.file.Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (tmp.length() == 0) { tmp.delete(); return null; }
            LOG.info("Downloaded plugin package: " + url + " → " + tmp.getAbsolutePath() + " (" + tmp.length() + " bytes)");
            return tmp;
        } catch (Exception e) {
            LOG.warning("Download failed: " + url + " - " + e.getMessage());
            return null;
        }
    }

    /** Read {name, version} from a package's yeow.json, or null if invalid. */
    public String[] readPackageInfo(File zip) {
        try (var z = new ZipFile(zip)) {
            var meta = z.getEntry("yeow.json");
            if (meta == null) return null;
            var obj = new Gson().fromJson(new String(z.getInputStream(meta).readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            if (!obj.has("name")) return null;
            return new String[]{ obj.get("name").getAsString(), obj.has("version") ? obj.get("version").getAsString() : "1.0.0" };
        } catch (Exception e) {
            return null;
        }
    }

    /** Save a downloaded package to plugins/Yeow/<name>-<version>.yeow.zip (standard auto-scan format). */
    public File savePluginPackage(File cache) {
        var info = readPackageInfo(cache);
        if (info == null) return null;
        try {
            var dest = new File(host.dataFolder(), info[0] + "-" + info[1] + ".yeow.zip");
            java.nio.file.Files.copy(cache.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Saved plugin package: " + dest.getAbsolutePath());
            return dest;
        } catch (Exception e) {
            LOG.warning("Save failed: " + e.getMessage());
            return null;
        }
    }

    /** Scan plugins/Yeow for an installed package whose yeow.json name matches. */
    public File findExistingPackage(String name) {
        var dir = host.dataFolder();
        var files = dir.listFiles((d, n) -> n.endsWith(".yeow.zip"));
        if (files == null) return null;
        for (var f : files) {
            var info = readPackageInfo(f);
            if (info != null && name.equals(info[0])) return f;
        }
        return null;
    }

    // ── dev WebSocket（热重载） ─────────────────────────────────────

    private void connectDevWebSocket() {
        int wsPort = Integer.parseInt(System.getProperty("yeow.ws.port", "17368"));
        String uri = "ws://localhost:" + wsPort;
        host.logger().info("Connecting to dev server WebSocket at " + uri);

        var latch = new java.util.concurrent.CountDownLatch(1);

        HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create(uri), new WebSocket.Listener() {
                private StringBuilder sb = new StringBuilder();

                @Override public void onOpen(WebSocket ws) {
                    devWs = ws;
                    ws.request(Long.MAX_VALUE);
                    latch.countDown();
                    host.logger().info("Dev WebSocket connected");
                }

                @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    sb.append(data);
                    if (last) {
                        var msg = sb.toString(); sb = new StringBuilder();
                        try {
                            var obj = gson.fromJson(msg, JsonObject.class);
                            var type = obj.get("type").getAsString();
                            var pname = obj.get("plugin").getAsString();

                            if ("hot-reload".equals(type)) {
                                handleHotReload(pname, obj);
                            } else if ("build-error".equals(type)) {
                                LOG.warning("Build error for " + pname + ": " + obj.get("error").getAsString());
                            }
                        } catch (Exception e) {
                            LOG.warning("WS msg err: " + e.getMessage());
                        }
                    }
                    return null;
                }

                @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                    LOG.warning("Dev WebSocket closed: " + reason);
                    return null;
                }

                @Override public void onError(WebSocket ws, Throwable error) {
                    LOG.warning("Dev WebSocket error: " + error.getMessage());
                    latch.countDown();
                }
            });

        // Block until connected or timeout (5s), so WebSocket is ready before registerPlugin()
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                host.logger().warning("Dev WebSocket connection timed out (5s) - running without dev WS");
            } else if (devWs == null) {
                host.logger().warning("Dev WebSocket connection failed - running without dev WS");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleHotReload(String pname, JsonObject obj) throws java.io.IOException {
        var codeFile = obj.get("codeFile").getAsString();
        var code = Files.readString(java.nio.file.Path.of(codeFile));
        var pt = plugins.get(pname);
        if (pt == null) { LOG.warning("Unknown plugin for hot reload: " + pname); return; }
        // 开发模式热重载允许重新加载权限：以新构建包的 computedPermissions 覆盖权限集——
        // 在 reloadInternal 之前刷新，使重载后的代码立即按新权限校验；强杀重建路径
        // （rebuildPluginEntity 读 pt.permissions()）同样继承更新后的权限。
        if (obj.has("permissions") && obj.get("permissions").isJsonArray()) {
            var perms = new java.util.LinkedHashSet<String>();
            for (var el : obj.getAsJsonArray("permissions")) perms.add(el.getAsString());
            if (pt instanceof PluginThread t) t.updatePermissions(perms);
            LOG.info("Hot reload: permissions updated for " + pname + " (" + perms.size() + " nodes)");
        }
        // 命令注销/事件退订/平台资源清理由 reload → cleanupResources → host.purgePlatformResources 完成
        if (serviceManager != null) serviceManager.purgePluginServices(pname);
        if (obj.has("assetsDir") && !obj.get("assetsDir").isJsonNull()) {
            if (pt instanceof PluginThread t) t.setDevAssetsDir(obj.get("assetsDir").getAsString());
        }
        if (pt instanceof PluginThread t) {
            if (!t.reloadInternal(code)) {
                // 旧线程被强杀：重建全新实体（新线程/新队列/新上下文），
                // 防止卡死的旧线程从共享队列偷取消息（双线程并发执行同一插件逻辑）。
                if (rebuildPluginEntity(pname, code)) {
                    LOG.warning("Force-killed JS thread for " + pname + " - plugin entity rebuilt fresh");
                }
            }
        } else {
            pt.reload(code);
        }
        var current = plugins.get(pname);
        if (current != null) current.postMessage(gson.toJson(Map.of("t", "LOAD")));
        host.syncCommands();
        LOG.info("Hot reload complete for " + pname);
    }

    public void sendDevMessage(String json) {
        if (devWs != null && !devWs.isInputClosed()) {
            try {
                devWs.sendText(json, true).exceptionally(t -> { host.logger().warning("WS send: " + t.getMessage()); return null; });
            } catch (Exception ex) { host.logger().warning("WS send: " + ex.getMessage()); }
        }
    }
}
