package yeow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

/**
 * 平台无关的运行时核心：插件注册表、包解析加载、任务提交、生命周期、dev WebSocket、
 * 原生服务批准。唯一平台依赖经 {@link PlatformHost}（构造注入）。
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
    private final ApprovalStore approvals;
    private final ConcurrentHashMap<String, PluginEntity> plugins = new ConcurrentHashMap<>();
    private final InstanceRegistry instances = new InstanceRegistry();
    private final String initCode;
    private final boolean devMode;
    private WebSocket devWs;
    /** 因原生服务未批准而被拒加载的插件（pluginName → 包路径）；批准后自动加载。 */
    private final Map<String, String> pendingLoads = new ConcurrentHashMap<>();

    public RuntimeCore(PlatformHost host, YeowConfig config, TaskScheduler scheduler) {
        this.host = host;
        this.devMode = "true".equals(System.getProperty("yeow.dev"));
        this.config = config;
        this.scheduler = scheduler;
        this.serviceManager = new yeow.service.ServiceManager(this::getPlugin);
        this.approvals = new ApprovalStore(host.dataFolder());
        this.profiler = yeow.profile.Profiler.create(yeow.profile.ProfileConfig.from(config), host.dataFolder());
        // 调度器插桩（ProfileSink/BudgetScaler）由平台在构造其调度器时装配
        String code = null;
        try (var is = RuntimeCore.class.getResourceAsStream("/js/init.js")) {
            code = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            host.logger().warning("Failed to load init.js: " + e.getMessage());
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

    /** 原生服务是否需要批准（config.yml，内存唯一信任源）。 */
    public boolean requireNativeApproval() { return config.requireNativeApproval(); }

    /** 插件是否已批准原生服务（approve.json，内存唯一信任源）。 */
    public boolean isNativeApproved(String plugin) { return approvals != null && approvals.isApproved(plugin); }

    /** 批准插件的原生服务（内存修改；服务器关闭时写回 approve.json）。 */
    public void approveNativePlugin(String plugin) {
        if (approvals != null) approvals.approve(plugin);
    }

    /** 生成一次性批准码（只打印在控制台日志，插件不可预知）。 */
    public String requestApprovalCode(String plugin) {
        return approvals != null ? approvals.requestApprovalCode(plugin) : null;
    }

    /** 用一次性 code 批准（成功返回插件名并作废 code；失败返回 null）。 */
    public String approveNativeByCode(String code) {
        return approvals != null ? approvals.approveByCode(code) : null;
    }

    /** 取出并移除因原生服务未批准而被拒加载的插件包路径（批准后自动重载用）。 */
    public String pendingLoadFor(String plugin) {
        return pendingLoads.remove(plugin);
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

    /** 关闭：WS、profile、插件、调度器、服务、批准落盘。 */
    public void shutdown() {
        if (devWs != null) try { devWs.sendClose(1000, "shutdown"); } catch (Exception ignored) {}
        if (profiler != null) profiler.close();
        instances.clear();
        plugins.values().forEach(PluginEntity::stopAndWait);
        scheduler.shutdown();
        if (serviceManager != null) serviceManager.shutdown();
        // 所有 Yeow 插件卸载完成后，把内存中的批准写回文件（approve.json；config.yml 为信任源，无需回写）
        if (approvals != null) approvals.save();
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
        try (var zip = new ZipFile(jarPath)) {
            var metaEntry = zip.getEntry("yeow.json");
            var name = "unknown";
            var version = "";
            var author = "";
            var perms = new LinkedHashSet<String>();
            var nativeHashes = new java.util.HashMap<String, String>(); // 打包后路径 → SHA-256
            var declaresNative = false; // 插件是否声明了原生服务（yeow.json native 非空）
            if (metaEntry != null) {
                var meta = new String(zip.getInputStream(metaEntry).readAllBytes(), StandardCharsets.UTF_8);
                var obj = new Gson().fromJson(meta, JsonObject.class);
                if (obj.has("name")) name = obj.get("name").getAsString();
                if (obj.has("version")) version = obj.get("version").getAsString();
                if (obj.has("author")) author = obj.get("author").getAsString();
                // 最终权限由构建器计算（合并依赖包声明 + 通配归一化）写入 computedPermissions。
                // v0 阶段不做旧包兼容--旧格式包（仅 permissions）视为无权限。
                if (obj.has("computedPermissions") && obj.get("computedPermissions").isJsonArray()) {
                    for (var el : obj.getAsJsonArray("computedPermissions")) perms.add(el.getAsString());
                }
                // 原生服务可信性声明（构建时计算 SHA-256 写入）：打包后路径 → hash
                if (obj.has("native") && obj.get("native").isJsonArray() && obj.getAsJsonArray("native").size() > 0) {
                    declaresNative = true;
                    for (var el : obj.getAsJsonArray("native")) {
                        if (!el.isJsonObject()) continue;
                        var e = el.getAsJsonObject();
                        if (e.has("files") && e.get("files").isJsonArray()) {
                            for (var f : e.getAsJsonArray("files")) {
                                if (!f.isJsonObject()) continue;
                                var fo = f.getAsJsonObject();
                                for (var entry2 : fo.entrySet()) nativeHashes.put(entry2.getKey(), entry2.getValue().getAsString());
                            }
                        }
                    }
                }
            }

            if (plugins.containsKey(name)) {
                LOG.warning("Duplicate plugin load rejected: " + name + " is already loaded (source: " + jarPath + ")");
                return false;
            }

            // 原生服务批准检查（加载时）：声明了原生服务的插件需要批准，否则拒绝加载本插件。
            // 一次性批准码只打印在控制台（插件可读日志也无法预知--code 在拒绝时新生成，且
            // 插件本身未加载，无法 dispatchCommand）。批准后自动重新加载。
            if (declaresNative && config.requireNativeApproval() && !isNativeApproved(name)) {
                var code = requestApprovalCode(name);
                pendingLoads.put(name, jarPath); // 批准后自动加载
                var banner = "\n" + "=".repeat(60)
                    + "\n  [Yeow] " + name + " declares NATIVE SERVICES and is NOT approved"
                    + "\n  The plugin was REFUSED to load (native binaries are untrusted)."
                    + "\n  To approve and load it, run:  /yeow approve " + code
                    + "\n  (one-time code - visible to server console only)"
                    + "\n" + "=".repeat(60);
                LOG.severe(banner);
                return false;
            }

            String userCode;
            String devAssetsDir = null;

            // Check for dev mode - .yeow/dev.json contains compiled code path
            var devEntry = zip.getEntry(".yeow/dev.json");
            if (devMode && devEntry != null) {
                var devMeta = new String(zip.getInputStream(devEntry).readAllBytes(), StandardCharsets.UTF_8);
                var devObj = new Gson().fromJson(devMeta, JsonObject.class);
                // Read compiled code from the file path stored in dev.json
                var codeFile = Path.of(devObj.get("codeFile").getAsString());
                userCode = Files.readString(codeFile);
                if (devObj.has("assetsDir") && !devObj.get("assetsDir").isJsonNull()) {
                    devAssetsDir = devObj.get("assetsDir").getAsString();
                }
                LOG.info("Dev mode: reading code from " + codeFile);
            } else {
                var codeEntry = zip.getEntry(".yeow/main.js");
                if (codeEntry == null) { LOG.severe("Missing .yeow/main.js in " + jarPath); return false; }
                userCode = new String(zip.getInputStream(codeEntry).readAllBytes(), StandardCharsets.UTF_8);
            }

            var pt = new PluginThread(name, jarPath, initCode, userCode, this, perms, nativeHashes);
            if (devAssetsDir != null) pt.setDevAssetsDir(devAssetsDir);
            if (devMode) pt.setDevMode(true);
            if (!registerPluginEntity(pt, sendLoad)) return false;
            if (devMode) LOG.info("Dev mode active for " + name);
            LOG.info("Loaded plugin: " + name + (version.isEmpty() ? "" : " v" + version)
                + (author.isEmpty() ? "" : " by " + author)
                + " - permissions: " + displayPermissions(perms));
            return true;
        } catch (Exception e) {
            LOG.severe("Failed to register plugin " + jarPath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 公开的插件实体注册接口--第三方适配器（Yeow-Python、Worker、TCP 适配器等）
     * 构造好自己的 {@link PluginEntity} 后调用，接入与普通插件一致的运行时链路：
     * 同名唯一检查、Profile 指标、生命周期（start + LOAD）。
     *
     * 适配器负责：包结构解析、引擎封装（postMessage 消化消息契约）、ping 实现；
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

    /** 注册插件实体并立即发送 LOAD 生命周期消息。 */
    public boolean registerPluginEntity(PluginEntity entity) {
        return registerPluginEntity(entity, true);
    }

    /**
     * 运行时级游戏任务提交--适配器提交游戏任务的统一入口（等价于 JS 的 `$_send('task', ...)`）。
     * 回调约定：payload 含 `cb` 字段时异步执行（立即返回 null），结果经
     * {@link PluginEntity#postMessage} 回投 `{"t":"cb","p":"<cbId>","r":<data>}`；
     * 无 `cb` 时同步阻塞返回结果 JSON。`cbId` 由适配器自行生成与管理。
     *
     * @param entity  提交方实体（注册表中的插件）
     * @param message 任务消息：JSON 字符串，或 POJO（**直接使用**，避免序列化开销--
     *               gson `JsonObject` 零转换直接执行；一般 POJO 由运行时一次转换）
     * @return 结果 JSON（同步）或 null（异步）
     */
    public String submitTask(PluginEntity entity, Object message) {
        if (entity == null) return gson.toJson(Map.of("err", "unknown plugin entity"));
        try {
            JsonObject obj;
            if (message instanceof String s) {
                obj = gson.fromJson(s.isEmpty() ? "{}" : s, JsonObject.class);
            } else if (message instanceof JsonObject jo) {
                obj = jo; // 直接使用，零序列化
            } else {
                obj = gson.toJsonTree(message).getAsJsonObject(); // 一般 POJO 一次转换
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
                scheduler.submitGameAsync(taskType, params, r -> entity.postMessage(yeow.channel.SyncCallbackHelper.cbMessage(cbId, r)), priority, entity.name());
                return null;
            }
            var future = new java.util.concurrent.CompletableFuture<String>();
            scheduler.submitGameSync(taskType, params, future, priority, entity.name());
            try { return future.get(config.taskSyncTimeoutMs(), TimeUnit.MILLISECONDS); }
            catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
        } catch (Exception e) {
            return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()));
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
        // 命令注销/事件退订/平台资源清理由 pt.reload → cleanupResources → host.purgePlatformResources 完成
        if (serviceManager != null) serviceManager.purgePluginServices(pname);
        if (obj.has("assetsDir") && !obj.get("assetsDir").isJsonNull()) {
            if (pt instanceof PluginThread t) t.setDevAssetsDir(obj.get("assetsDir").getAsString());
        }
        pt.reload(code);
        pt.postMessage(gson.toJson(Map.of("t", "LOAD")));
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
