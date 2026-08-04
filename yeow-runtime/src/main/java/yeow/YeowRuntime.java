package yeow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import yeow.task.EventBridge;
import yeow.task.Tasks;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

public class YeowRuntime extends JavaPlugin {
    private static final Logger LOG = Logger.getLogger("Yeow");
    private static final Gson gson = new Gson();
    private static YeowRuntime instance;

    private YeowConfig config;
    private Scheduler scheduler;
    private final java.util.concurrent.ConcurrentHashMap<String, PluginEntity> plugins = new java.util.concurrent.ConcurrentHashMap<>();
    private final EventBridge eventBridge = new EventBridge(this);
    private String initCode;
    private boolean devMode = false;
    private WebSocket devWs;
    private yeow.service.ServiceManager serviceManager;
    private yeow.profile.Profiler profiler;

    public PluginEntity getPlugin(String name) { return plugins.get(name); }
    public Scheduler getScheduler() { return scheduler; }
    public EventBridge getEventBridge() { return eventBridge; }
    public yeow.service.ServiceManager getServiceManager() { return serviceManager; }
    public YeowConfig getYeowConfig() { return config; }
    public yeow.profile.Profiler getProfiler() { return profiler; }

    public static YeowRuntime inst() { return instance; }

    @Override public void onLoad() {
        instance = this;
        devMode = "true".equals(System.getProperty("yeow.dev"));
        this.config = new YeowConfig(getDataFolder());
        this.scheduler = new Scheduler(config);
        this.serviceManager = new yeow.service.ServiceManager();
        var pc = yeow.profile.ProfileConfig.from(config);
        profiler = yeow.profile.Profiler.create(pc);
        scheduler.setProfileSink(profiler.sink());
        if (pc.scalerEnabled()) {
            scheduler.setBudgetScaler(new BudgetScaler(scheduler.tickBudgetNs(),
                pc.scalerFactor(), pc.scalerMax(), pc.backlogThreshold(), pc.backlogWindowTicks()));
        }
        try (var is = getClass().getResourceAsStream("/js/init.js")) {
            initCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { getLogger().warning("Failed to load init.js: " + e.getMessage()); }
        if (devMode) connectDevWebSocket();
    }

    @Override public void onEnable() {
        try { serviceManager.start(); } catch (Exception e) { LOG.warning("Failed to start native service TCP: " + e.getMessage()); }
        Bukkit.getScheduler().runTaskTimer(this, () -> scheduler.tick(), 0L, 1L);

        eventBridge.setSink(profiler.sink());
        eventBridge.setTimeoutMs(config.profileCallbackTimeoutEventMs());
        yeow.task.CommandTasks.setProfileSink(profiler.sink());
        yeow.task.CommandTasks.setTimeoutMs(config.profileCallbackTimeoutTabCompleteMs());

        scanPluginDirectory();
        registerYeowCommand();

        for (var pt : plugins.values()) {
            pt.postMessage(new com.google.gson.Gson().toJson(Map.of("t","LOAD")));
        }
    }

    @Override public void onDisable() {
        if (devWs != null) try { devWs.sendClose(1000, "shutdown"); } catch (Exception ignored) {}
        if (profiler != null) profiler.close();
        plugins.values().forEach(PluginEntity::stopAndWait);
        scheduler.shutdown();
        if (serviceManager != null) serviceManager.shutdown();
        instance = null;
    }

    /** Auto-load every *.yeow.zip found in the runtime data folder (plugins/Yeow). */
    private void scanPluginDirectory() {
        var dir = getDataFolder();
        if (!dir.exists()) return;
        var files = dir.listFiles((d, n) -> n.endsWith(".yeow.zip"));
        if (files == null) return;
        for (var f : files) {
            LOG.info("Auto-loading plugin package: " + f.getName());
            registerPlugin(f.getAbsolutePath());
        }
    }

    private void connectDevWebSocket() {
        int wsPort = Integer.parseInt(System.getProperty("yeow.ws.port", "17368"));
        String uri = "ws://localhost:" + wsPort;
        getLogger().info("Connecting to dev server WebSocket at " + uri);

        var gson = new Gson();
        var latch = new java.util.concurrent.CountDownLatch(1);

        HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create(uri), new WebSocket.Listener() {
                private StringBuilder sb = new StringBuilder();

                @Override public void onOpen(WebSocket ws) {
                    devWs = ws;
                    ws.request(Long.MAX_VALUE);
                    latch.countDown();
                    getLogger().info("Dev WebSocket connected");
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
                                var codeFile = obj.get("codeFile").getAsString();
                                var code = java.nio.file.Files.readString(java.nio.file.Path.of(codeFile));
                                var pt = plugins.get(pname);
                                if (pt == null) { LOG.warning("Unknown plugin for hot reload: " + pname); return null; }
                                try { Tasks.execute("command.unregisterAll", gson.fromJson("{\"pluginName\":\"" + pname + "\"}", JsonObject.class)); } catch (Exception ignored) {}
                                eventBridge.unsubscribeAll(pname);
                                if (serviceManager != null) serviceManager.purgePluginServices(pname);
                                if (obj.has("assetsDir") && !obj.get("assetsDir").isJsonNull()) {
                                    if (pt instanceof PluginThread t) t.setDevAssetsDir(obj.get("assetsDir").getAsString());
                                }
                                pt.reload(code);
                                pt.postMessage(gson.toJson(Map.of("t","LOAD")));
                                yeow.task.CommandTasks.syncCommands();
                                LOG.info("Hot reload complete for " + pname);
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
                getLogger().warning("Dev WebSocket connection timed out (5s) — running without dev WS");
            } else if (devWs == null) {
                getLogger().warning("Dev WebSocket connection failed — running without dev WS");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Load a Yeow package (template JAR or .yeow.zip). The plugin name comes from yeow.json.
     * One plugin name may only have a single instance — a duplicate load is rejected with a warning.
     *
     * @param sendLoad whether to send the LOAD message right away (runtime load/reload).
     *                 Startup registration (template JARs / auto-scan) passes false — the
     *                 onEnable() loop sends LOAD to all plugins once the scheduler is ticking.
     * @return true if the plugin was loaded, false if skipped (duplicate) or failed
     */
    public boolean registerPlugin(String jarPath, boolean sendLoad) {
        try (var zip = new ZipFile(jarPath)) {
            var metaEntry = zip.getEntry("yeow.json");
            var name = "unknown";
            var version = "";
            var author = "";
            var perms = new java.util.LinkedHashSet<String>();
            var nativeHashes = new java.util.HashMap<String, String>(); // 打包后路径 → SHA-256
            if (metaEntry != null) {
                var meta = new String(zip.getInputStream(metaEntry).readAllBytes(), StandardCharsets.UTF_8);
                var obj = new Gson().fromJson(meta, JsonObject.class);
                if (obj.has("name")) name = obj.get("name").getAsString();
                if (obj.has("version")) version = obj.get("version").getAsString();
                if (obj.has("author")) author = obj.get("author").getAsString();
                // 最终权限由构建器计算（合并依赖包声明 + 通配归一化）写入 computedPermissions。
                // v0 阶段不做旧包兼容——旧格式包（仅 permissions）视为无权限。
                if (obj.has("computedPermissions") && obj.get("computedPermissions").isJsonArray()) {
                    for (var el : obj.getAsJsonArray("computedPermissions")) perms.add(el.getAsString());
                }
                // 原生服务可信性声明（构建时计算 SHA-256 写入）：打包后路径 → hash
                if (obj.has("native") && obj.get("native").isJsonArray()) {
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

            String userCode;
            String devAssetsDir = null;

            // Check for dev mode — .yeow/dev.json contains compiled code path
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

            var pt = new PluginThread(name, jarPath, initCode, userCode, scheduler, perms, nativeHashes);
            if (devAssetsDir != null) pt.setDevAssetsDir(devAssetsDir);
            if (devMode) pt.setDevMode(true);
            if (!registerPluginEntity(pt, sendLoad)) return false;
            if (devMode) LOG.info("Dev mode active for " + name);
            LOG.info("Loaded plugin: " + name + (version.isEmpty() ? "" : " v" + version)
                + (author.isEmpty() ? "" : " by " + author)
                + " — permissions: " + displayPermissions(perms));
            return true;
        } catch (Exception e) {
            LOG.severe("Failed to register plugin " + jarPath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 公开的插件实体注册接口——第三方适配器（Yeow-Python、Worker、TCP 适配器等）
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
            + (entity.source() != null ? " — source: " + entity.source() : ""));
        return true;
    }

    /**
     * 注册插件实体并立即发送 LOAD 生命周期消息。
     * 适配器（Yeow-Python、Worker、TCP 适配器等）构造 {@link PluginEntity} 后
     * 调用此方法接入运行时：同名唯一、Profile 指标、生命周期管理均由运行时负责。
     *
     * @param entity 已构造的插件实体（未启动；name() 非空且全局唯一）
     * @return true 注册成功；false 同名冲突或参数非法
     */
    public boolean registerPluginEntity(PluginEntity entity) {
        return registerPluginEntity(entity, true);
    }

    /**
     * 运行时级游戏任务提交——适配器提交游戏任务的统一入口（等价于 JS 的 `$_send('task', ...)`）。
     *
     * 只有 task 通道是共有的；其他通道（service / fs / http / timer / log / debug 等）与
     * 权限模型都是 JS 插件特有的，适配器根据自身情况处理（如 CPython 自带标准库，
     * 无需运行时提供 log / fs 等辅助）。
     *
     * 回调约定：payload 含 `cb` 字段时异步执行（立即返回 null），结果经
     * {@link PluginEntity#postMessage} 回投 `{"t":"cb","p":"<cbId>","r":<data>}`；
     * 无 `cb` 时同步阻塞返回结果 JSON。`cbId` 由适配器自行生成与管理。
     *
     * @param entity  提交方实体（注册表中的插件）
     * @param message 任务消息：JSON 字符串，或 POJO（**直接使用**，避免序列化开销——
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
            var hasCb = obj.has("cb");
            var priority = parsePriority(obj.has("priority") ? obj.get("priority").getAsString() : null);
            if (hasCb) {
                var cbId = obj.get("cb").getAsString();
                scheduler.submitGameAsync(taskType, params, r -> entity.postMessage(yeow.channel.SyncCallbackHelper.cbMessage(cbId, r)), priority, entity.name());
                return null;
            }
            var future = new java.util.concurrent.CompletableFuture<String>();
            scheduler.submitGameSync(taskType, params, future, priority, entity.name());
            try { return future.get(5, TimeUnit.SECONDS); }
            catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
        } catch (Exception e) {
            return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private static Scheduler.Priority parsePriority(String s) {
        if (s == null) return Scheduler.Priority.NORMAL;
        return switch (s.toLowerCase()) { case "high" -> Scheduler.Priority.HIGH; case "low" -> Scheduler.Priority.LOW; default -> Scheduler.Priority.NORMAL; };
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
     * Unload a plugin: unregister its commands/events/services, wait up to 5s for the JS thread
     * to exit (force-kill if hung), then remove it from the registry.
     */
    public boolean unloadPlugin(String name) {
        var pt = plugins.remove(name);
        if (pt == null) return false;
        try { Tasks.execute("command.unregisterAll", new Gson().fromJson("{\"pluginName\":\"" + name + "\"}", JsonObject.class)); } catch (Exception ignored) {}
        eventBridge.unsubscribeAll(name);
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
     * URL sources are downloaded to the cache (temporary — never persisted).
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
        if (ok) yeow.task.CommandTasks.syncCommands();
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
    private File downloadPluginZip(String url) {
        try {
            var cacheDir = new File(getDataFolder(), ".cache");
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
            LOG.warning("Download failed: " + url + " — " + e.getMessage());
            return null;
        }
    }

    /** Read {name, version} from a package's yeow.json, or null if invalid. */
    private String[] readPackageInfo(File zip) {
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
    private File savePluginPackage(File cache) {
        var info = readPackageInfo(cache);
        if (info == null) return null;
        try {
            var dest = new File(getDataFolder(), info[0] + "-" + info[1] + ".yeow.zip");
            java.nio.file.Files.copy(cache.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Saved plugin package: " + dest.getAbsolutePath());
            return dest;
        } catch (Exception e) {
            LOG.warning("Save failed: " + e.getMessage());
            return null;
        }
    }

    /** Scan plugins/Yeow for an installed package whose yeow.json name matches. */
    private File findExistingPackage(String name) {
        var dir = getDataFolder();
        var files = dir.listFiles((d, n) -> n.endsWith(".yeow.zip"));
        if (files == null) return null;
        for (var f : files) {
            var info = readPackageInfo(f);
            if (info != null && name.equals(info[0])) return f;
        }
        return null;
    }

    private void registerYeowCommand() {
        var map = yeow.task.CommandTasks.getBukkitCommandMap();
        if (map == null) return;
        var cmd = new org.bukkit.command.defaults.BukkitCommand("yeow") {
            @Override
            public boolean execute(CommandSender s, String l, String[] a) {
                if (a.length == 0) { usage(s); return true; }
                return switch (a[0]) {
                    case "load" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2) { s.sendMessage("Usage: /yeow load <path|url>"); yield true; }
                        // Always temporary: URL downloads go to the cache and are never persisted.
                        if (isHttpUrl(a[1])) {
                            var cache = downloadPluginZip(a[1]);
                            if (cache == null) { s.sendMessage("Download failed: " + a[1]); yield true; }
                            if (registerPlugin(cache.getAbsolutePath(), true)) s.sendMessage("Loaded (temporary): " + a[1]);
                            else { s.sendMessage("Load failed (duplicate or invalid package): " + a[1]); cache.delete(); }
                            yield true;
                        }
                        var path = resolveServerPath(a[1]);
                        var f = new File(path);
                        if (!f.isFile()) { s.sendMessage("File not found: " + path); yield true; }
                        if (registerPlugin(f.getAbsolutePath(), true)) s.sendMessage("Loaded: " + f.getAbsolutePath());
                        else s.sendMessage("Load failed (duplicate or invalid package): " + f.getAbsolutePath());
                        yield true;
                    }
                    case "install" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2 || !isHttpUrl(a[1])) { s.sendMessage("Usage: /yeow install <url>"); yield true; }
                        var cache = downloadPluginZip(a[1]);
                        if (cache == null) { s.sendMessage("Download failed: " + a[1]); yield true; }
                        // Persist as the standard <name>-<version>.yeow.zip in plugins/Yeow/
                        // (auto-loaded on the next server start) and load it right away.
                        var dest = savePluginPackage(cache);
                        cache.delete();
                        if (dest == null) { s.sendMessage("Install failed (invalid package): " + a[1]); yield true; }
                        if (registerPlugin(dest.getAbsolutePath(), true)) s.sendMessage("Installed: " + dest.getAbsolutePath());
                        else s.sendMessage("Install failed (duplicate or invalid package): " + dest.getAbsolutePath());
                        yield true;
                    }
                    case "update" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2 || !isHttpUrl(a[1])) { s.sendMessage("Usage: /yeow update <url>"); yield true; }
                        var cache = downloadPluginZip(a[1]);
                        if (cache == null) { s.sendMessage("Download failed: " + a[1]); yield true; }
                        var info = readPackageInfo(cache);
                        if (info == null) { s.sendMessage("Update failed (invalid package): " + a[1]); cache.delete(); yield true; }
                        var old = findExistingPackage(info[0]);
                        if (old == null) {
                            s.sendMessage("No existing package found for '" + info[0] + "' — use /yeow install <url>");
                            cache.delete();
                            yield true;
                        }
                        // Move the old version to Yeow/.backup
                        var backupDir = new File(getDataFolder(), ".backup");
                        backupDir.mkdirs();
                        var backup = new File(backupDir, old.getName() + "." + System.currentTimeMillis() + ".bak");
                        try { java.nio.file.Files.move(old.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                        catch (Exception e) { s.sendMessage("Update failed (backup error): " + e.getMessage()); cache.delete(); yield true; }
                        LOG.info("Backed up old package: " + old.getName() + " → " + backup.getAbsolutePath());
                        // Write the new version
                        var dest = new File(getDataFolder(), info[0] + "-" + info[1] + ".yeow.zip");
                        try { java.nio.file.Files.copy(cache.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                        catch (Exception e) { s.sendMessage("Update failed (write error): " + e.getMessage()); cache.delete(); yield true; }
                        cache.delete();
                        if (plugins.containsKey(info[0])) {
                            // The plugin is running — reload from the new package.
                            unloadPlugin(info[0]);
                            registerPlugin(dest.getAbsolutePath(), true);
                            yeow.task.CommandTasks.syncCommands();
                            s.sendMessage("Updated + reloaded: " + dest.getAbsolutePath() + " (old → " + backup.getAbsolutePath() + ")");
                        } else {
                            s.sendMessage("Updated: " + dest.getAbsolutePath() + " (old → " + backup.getAbsolutePath() + ")");
                        }
                        yield true;
                    }
                    case "unload" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2) { s.sendMessage("Usage: /yeow unload <plugin|all>"); yield true; }
                        if ("all".equals(a[1])) {
                            for (var n : new java.util.ArrayList<>(plugins.keySet())) unloadPlugin(n);
                            s.sendMessage("All plugins unloaded");
                        } else if (unloadPlugin(a[1])) {
                            s.sendMessage("Unloaded: " + a[1]);
                        } else {
                            s.sendMessage("Plugin not loaded: " + a[1]);
                        }
                        yield true;
                    }
                    case "uninstall" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2) { s.sendMessage("Usage: /yeow uninstall <plugin>"); yield true; }
                        var name = a[1];
                        var pkg = findExistingPackage(name);
                        if (pkg == null && !plugins.containsKey(name)) {
                            s.sendMessage("Plugin not loaded and no package found: " + name);
                            yield true;
                        }
                        if (plugins.containsKey(name)) unloadPlugin(name);
                        if (pkg == null) {
                            s.sendMessage("Unloaded: " + name + " — no .yeow.zip found in plugins/Yeow");
                            yield true;
                        }
                        // 插件本体 + 数据目录一并迁移到 .backup/<时间戳>/（失败则放弃并报告错误）
                        var ts = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
                        var backupDir = new File(new File(getDataFolder(), ".backup"), ts);
                        var failed = new java.util.ArrayList<String>();
                        if (!backupDir.mkdirs()) {
                            s.sendMessage("Uninstall failed: cannot create backup directory " + backupDir.getAbsolutePath());
                            yield true;
                        }
                        try {
                            java.nio.file.Files.move(pkg.toPath(),
                                new File(backupDir, pkg.getName()).toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            failed.add("package: " + e.getMessage());
                        }
                        var dataDir = new File("plugins", name);
                        if (dataDir.exists()) {
                            try {
                                java.nio.file.Files.move(dataDir.toPath(),
                                    new File(backupDir, name).toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception e) {
                                failed.add("data directory: " + e.getMessage());
                            }
                        }
                        if (!failed.isEmpty()) {
                            s.sendMessage("Uninstall failed (files may be locked): " + String.join("; ", failed)
                                + " — check " + backupDir.getAbsolutePath());
                            yield true;
                        }
                        s.sendMessage("Uninstalled: " + name + " (package + data → .backup/" + ts + "/)");
                        yield true;
                    }
                    case "reload" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2) { s.sendMessage("Usage: /yeow reload <plugin|all> [path]"); yield true; }
                        if ("all".equals(a[1])) {
                            var names = new java.util.ArrayList<>(plugins.keySet());
                            for (var n : names) reloadPlugin(n, null);
                            s.sendMessage("Reloaded " + names.size() + " plugins");
                        } else {
                            var path = a.length >= 3 ? a[2] : null;
                            if (reloadPlugin(a[1], path)) s.sendMessage("Reloaded: " + a[1]);
                            else s.sendMessage("Reload failed: plugin not loaded or bad source — " + a[1]);
                        }
                        yield true;
                    }
                    case "profile" -> {
                        if (!s.hasPermission("yeow.profile")) { s.sendMessage("No permission."); yield true; }
                        if (profiler != null) profiler.handleProfile(s);
                        else s.sendMessage("Profiler is disabled.");
                        yield true;
                    }
                    case "track" -> {
                        if (!s.hasPermission("yeow.profile")) { s.sendMessage("No permission."); yield true; }
                        if (profiler == null) { s.sendMessage("Profiler is disabled."); yield true; }
                        if (a.length < 3) { s.sendMessage("Usage: /yeow track <plugin> <seconds>"); yield true; }
                        try { profiler.handleTrack(s, a[1], Integer.parseInt(a[2])); }
                        catch (NumberFormatException e) { s.sendMessage("Invalid seconds: " + a[2]); }
                        yield true;
                    }
                    default -> { usage(s); yield true; }
                };
            }

            private void usage(CommandSender s) {
                s.sendMessage("Usage: /yeow load <path|url> | /yeow install <url> | /yeow update <url> | /yeow unload <plugin|all> | /yeow uninstall <plugin> | /yeow reload <plugin|all> [path|url] | /yeow profile | /yeow track <plugin> <seconds>");
            }

            @Override
            public java.util.List<String> tabComplete(CommandSender s, String l, String[] a) {
                var out = new java.util.ArrayList<String>();
                if (a.length <= 1) {
                    out.add("load"); out.add("install"); out.add("update"); out.add("unload"); out.add("uninstall"); out.add("reload");
                    out.add("profile"); out.add("track");
                } else if (a.length == 2) {
                    switch (a[0]) {
                        case "unload", "reload" -> { out.add("all"); out.addAll(plugins.keySet()); }
                        case "uninstall" -> out.addAll(plugins.keySet());
                        case "load" -> out.addAll(pluginFileCandidates());
                        case "track" -> out.addAll(plugins.keySet());
                    }
                } else if (a.length == 3 && "reload".equals(a[0])) {
                    out.addAll(pluginFileCandidates());
                }
                var prefix = a[a.length - 1];
                return out.stream().filter(x -> x.startsWith(prefix)).toList();
            }

            private java.util.List<String> pluginFileCandidates() {
                var out = new java.util.ArrayList<String>();
                var dataDir = getDataFolder();
                if (dataDir.exists()) {
                    var files = dataDir.listFiles((d, n) -> n.endsWith(".yeow.zip"));
                    if (files != null) for (var f : files) out.add("plugins/Yeow/" + f.getName());
                }
                var pluginsDir = new File("plugins");
                if (pluginsDir.exists()) {
                    var files = pluginsDir.listFiles((d, n) -> n.endsWith(".jar"));
                    if (files != null) for (var f : files) out.add("plugins/" + f.getName());
                }
                return out;
            }
        };
        cmd.setDescription("Yeow plugin management");
        cmd.setUsage("/yeow load|unload|reload|profile|track");
        cmd.setPermission("yeow.admin");
        map.register("yeow", cmd);
    }

    public void sendDevMessage(String json) {
        if (devWs != null && !devWs.isInputClosed()) {
            try {
                devWs.sendText(json, true).exceptionally(t -> { getLogger().warning("WS send: " + t.getMessage()); return null; });
            } catch (Exception ex) { getLogger().warning("WS send: " + ex.getMessage()); }
        }
    }
}
