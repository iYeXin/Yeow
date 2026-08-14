package yeow.paper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import yeow.PluginEntity;
import yeow.PlatformHost;
import yeow.RuntimeCore;
import yeow.task.CommandTasks;
import yeow.task.InventoryTasks;
import yeow.task.BossBarTasks;
import yeow.task.Tasks;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

/**
 * Paper/Bukkit 平台适配层（JavaPlugin）。平台相关的全部内容都在本包：
 * JavaPlugin 生命周期、tick 驱动、事件/命令桥、权限注册、/yeow 管理命令绑定。
 * 引擎逻辑（插件加载、调度队列、JS 线程、服务、Profile）在 {@link RuntimeCore}。
 */
public class YeowRuntime extends JavaPlugin implements PlatformHost {
    private static final Logger LOG = Logger.getLogger("Yeow");
    private static final Gson gson = new Gson();
    private static YeowRuntime instance;

    private RuntimeCore core;
    private PaperScheduler paperScheduler;
    private final EventBridge eventBridge = new EventBridge(this);
    private final CommandBridge commandBridge = new CommandBridge(this);
    private final PermissionRegistry permissionRegistry = new PermissionRegistry();

    public static YeowRuntime inst() { return instance; }

    // ── PlatformHost 实现（core 的唯一平台耦合面） ───────────────────

    // 根 logger（非插件 getLogger()）：控制台输出不带 [Yeow] 前缀，与拆分前一致
    @Override public Logger logger() { return Bukkit.getLogger(); }
    @Override public String minecraftVersion() { return Bukkit.getMinecraftVersion(); }
    @Override public String runtimeVersion() { return getDescription() != null ? getDescription().getVersion() : null; }
    @Override public String platformName() { return "paper"; }
    @Override public File dataFolder() { return getDataFolder(); }
    @Override public boolean isGameThread() { return Bukkit.isPrimaryThread(); }
    @Override public void onGameThread(Runnable r) { Bukkit.getScheduler().runTask(this, r); }

    @Override
    public void purgePlatformResources(String pluginName) {
        commandBridge.unregisterAll(pluginName);
        eventBridge.unsubscribeAll(pluginName);
        InventoryTasks.purgePlugin(pluginName);
        BossBarTasks.purgePlugin(pluginName);
    }

    @Override
    public void syncCommands() { commandBridge.syncCommands(); }

    // ── 任务类 / 事件桥访问入口 ─────────────────────────────────────

    public RuntimeCore core() { return core; }

    /**
     * 模板 Bootstrap 契约（拆分前即有的公开入口）：按包路径注册插件。
     * 签名保持与拆分前一致（boolean registerPlugin(String)），旧模板 jar 亦兼容。
     */
    public boolean registerPlugin(String jarPath) { return core.registerPlugin(jarPath); }

    public PaperScheduler getScheduler() { return paperScheduler; }
    public yeow.YeowConfig getYeowConfig() { return core.config(); }
    public yeow.service.ServiceManager getServiceManager() { return core.serviceManager(); }
    public yeow.profile.Profiler getProfiler() { return core.profiler(); }
    public EventBridge getEventBridge() { return eventBridge; }
    public CommandBridge getCommandBridge() { return commandBridge; }
    public PermissionRegistry getPermissionRegistry() { return permissionRegistry; }
    public PluginEntity getPlugin(String name) { return core.getPlugin(name); }

    // ── JavaPlugin 生命周期 ─────────────────────────────────────────

    @Override public void onLoad() {
        instance = this;
        var cfg = new yeow.YeowConfig(getDataFolder());
        this.paperScheduler = new PaperScheduler(cfg, this);
        this.core = new RuntimeCore(this, cfg, paperScheduler);
        // 调度器插桩装配（ProfileSink；BudgetScaler 已在 PaperScheduler 构造时装配）
        paperScheduler.setProfileSink(core.profiler().sink());
        // JS 句柄注册表装配：GUI/BossBar 创建时向 core 注册释放闭包（id 不携带业务信息）
        InventoryTasks.setInstances(core.instances());
        BossBarTasks.setInstances(core.instances());
    }

    @Override public void onEnable() {
        core.start();
        // 主线程任务 pump：执行调度线程路由到主线程的游戏任务（每 tick 预算内）
        Bukkit.getScheduler().runTaskTimer(this, paperScheduler::mainTickPump, 0L, 1L);

        eventBridge.setSink(core.profiler().sink());
        eventBridge.setTimeoutMs(core.config().profileCallbackTimeoutEventMs());
        commandBridge.setProfileSink(core.profiler().sink());
        commandBridge.setTimeoutMs(core.config().profileCallbackTimeoutTabCompleteMs());

        core.scanPluginDirectory();
        registerYeowCommand();
        core.loadAllPlugins();
    }

    @Override public void onDisable() {
        core.shutdown();
        instance = null;
    }

    // ── /yeow 管理命令（逻辑委托 RuntimeCore） ─────────────────────

    private void registerYeowCommand() {
        var map = CommandBridge.getBukkitCommandMap();
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
                        if (a[1].startsWith("http://") || a[1].startsWith("https://")) {
                            var cache = core.downloadPluginZip(a[1]);
                            if (cache == null) { s.sendMessage("Download failed: " + a[1]); yield true; }
                            if (core.registerPlugin(cache.getAbsolutePath(), true)) s.sendMessage("Loaded (temporary): " + a[1]);
                            else { s.sendMessage("Load failed (duplicate or invalid package): " + a[1]); cache.delete(); }
                            yield true;
                        }
                        var path = resolveServerPath(a[1]);
                        var f = new File(path);
                        if (!f.isFile()) { s.sendMessage("File not found: " + path); yield true; }
                        if (core.registerPlugin(f.getAbsolutePath(), true)) s.sendMessage("Loaded: " + f.getAbsolutePath());
                        else s.sendMessage("Load failed (duplicate or invalid package): " + f.getAbsolutePath());
                        yield true;
                    }
                    case "install" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2 || !(a[1].startsWith("http://") || a[1].startsWith("https://"))) { s.sendMessage("Usage: /yeow install <url>"); yield true; }
                        var cache = core.downloadPluginZip(a[1]);
                        if (cache == null) { s.sendMessage("Download failed: " + a[1]); yield true; }
                        // Persist as the standard <name>-<version>.yeow.zip in plugins/Yeow/
                        // (auto-loaded on the next server start) and load it right away.
                        var dest = core.savePluginPackage(cache);
                        cache.delete();
                        if (dest == null) { s.sendMessage("Install failed (invalid package): " + a[1]); yield true; }
                        if (core.registerPlugin(dest.getAbsolutePath(), true)) s.sendMessage("Installed: " + dest.getAbsolutePath());
                        else s.sendMessage("Install failed (duplicate or invalid package): " + dest.getAbsolutePath());
                        yield true;
                    }
                    case "update" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2 || !(a[1].startsWith("http://") || a[1].startsWith("https://"))) { s.sendMessage("Usage: /yeow update <url>"); yield true; }
                        var cache = core.downloadPluginZip(a[1]);
                        if (cache == null) { s.sendMessage("Download failed: " + a[1]); yield true; }
                        var info = core.readPackageInfo(cache);
                        if (info == null) { s.sendMessage("Update failed (invalid package): " + a[1]); cache.delete(); yield true; }
                        var old = core.findExistingPackage(info[0]);
                        if (old == null) {
                            s.sendMessage("No existing package found for '" + info[0] + "' - use /yeow install <url>");
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
                        if (core.getPlugin(info[0]) != null) {
                            // The plugin is running - reload from the new package.
                            core.unloadPlugin(info[0]);
                            core.registerPlugin(dest.getAbsolutePath(), true);
                            syncCommands();
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
                            for (var n : core.realPluginNames()) core.unloadPlugin(n);
                            s.sendMessage("All plugins unloaded");
                        } else if (core.unloadPlugin(a[1])) {
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
                        var pkg = core.findExistingPackage(name);
                        if (pkg == null && core.getPlugin(name) == null) {
                            s.sendMessage("Plugin not loaded and no package found: " + name);
                            yield true;
                        }
                        if (core.getPlugin(name) != null) core.unloadPlugin(name);
                        if (pkg == null) {
                            s.sendMessage("Unloaded: " + name + " - no .yeow.zip found in plugins/Yeow");
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
                                + " - check " + backupDir.getAbsolutePath());
                            yield true;
                        }
                        s.sendMessage("Uninstalled: " + name + " (package + data → .backup/" + ts + "/)");
                        yield true;
                    }
                    case "reload" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2) { s.sendMessage("Usage: /yeow reload <plugin|all> [path]"); yield true; }
                        if ("all".equals(a[1])) {
                            var names = core.realPluginNames();
                            for (var n : names) core.reloadPlugin(n, null);
                            s.sendMessage("Reloaded " + names.size() + " plugins");
                        } else {
                            var path = a.length >= 3 ? a[2] : null;
                            if (core.reloadPlugin(a[1], path)) s.sendMessage("Reloaded: " + a[1]);
                            else s.sendMessage("Reload failed: plugin not loaded or bad source - " + a[1]);
                        }
                        yield true;
                    }
                    case "profile" -> {
                        if (!s.hasPermission("yeow.profile")) { s.sendMessage("No permission."); yield true; }
                        if (core.profiler() != null) core.profiler().handleProfile(m -> s.sendMessage(m));
                        else s.sendMessage("Profiler is disabled.");
                        yield true;
                    }
                    case "track" -> {
                        if (!s.hasPermission("yeow.profile")) { s.sendMessage("No permission."); yield true; }
                        if (core.profiler() == null) { s.sendMessage("Profiler is disabled."); yield true; }
                        if (a.length < 3) { s.sendMessage("Usage: /yeow track <plugin> <seconds>"); yield true; }
                        try { core.profiler().handleTrack(m -> s.sendMessage(m), a[1], Integer.parseInt(a[2])); }
                        catch (NumberFormatException e) { s.sendMessage("Invalid seconds: " + a[2]); }
                        yield true;
                    }
                    case "approve" -> {
                        if (!s.hasPermission("yeow.admin")) { s.sendMessage("No permission."); yield true; }
                        if (a.length < 2) { s.sendMessage("Usage: /yeow approve <code>"); yield true; }
                        var pn = core.approveNativeByCode(a[1]);
                        if (pn == null) { s.sendMessage("Invalid or expired approval code: " + a[1]); yield true; }
                        s.sendMessage("Approved native services for " + pn + " (persisted on server shutdown)");
                        // 被拒加载的插件：自动重新加载
                        var pending = core.pendingLoadFor(pn);
                        if (pending != null) {
                            s.sendMessage("Loading " + pn + " ...");
                            if (core.registerPlugin(pending, true)) {
                                s.sendMessage("Loaded " + pn);
                                syncCommands();
                            } else {
                                s.sendMessage("Failed to load " + pn + " (see log)");
                            }
                        } else {
                            s.sendMessage("Run /yeow reload " + pn + " if it is already loaded");
                        }
                        yield true;
                    }
                    default -> { usage(s); yield true; }
                };
            }

            private void usage(CommandSender s) {
                s.sendMessage("Usage: /yeow load <path|url> | /yeow install <url> | /yeow update <url> | /yeow unload <plugin|all> | /yeow uninstall <plugin> | /yeow reload <plugin|all> [path|url] | /yeow approve <code> | /yeow profile | /yeow track <plugin> <seconds>");
            }

            @Override
            public java.util.List<String> tabComplete(CommandSender s, String l, String[] a) {
                var out = new java.util.ArrayList<String>();
                if (a.length <= 1) {
                    out.add("load"); out.add("install"); out.add("update"); out.add("unload"); out.add("uninstall"); out.add("reload");
                    out.add("approve"); out.add("profile"); out.add("track");
                } else if (a.length == 2) {
                    switch (a[0]) {
                        case "unload", "reload" -> { out.add("all"); out.addAll(core.realPluginNames()); }
                        case "uninstall" -> out.addAll(core.realPluginNames());
                        case "load" -> out.addAll(pluginFileCandidates());
                        case "track" -> out.addAll(core.realPluginNames());
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

    // ── debug 通道 command 节点（运行时内部测试；仅开发模式开放，见 PluginThread） ──

    /** fill 指令的方块数上限（防手滑巨型区域）。 */
    private static final long MAX_FILL_BLOCKS = 1_000_000;
    /** debug command 超时（ms）：fill 等基准指令可能执行很久（百万方块级）。 */
    private static final long DEBUG_COMMAND_TIMEOUT_MS = 600_000;

    /**
     * debug 通道 command 节点（core 只转发，平台实现负责执行线程）：
     * debug 通道在插件 JS 线程处理——世界修改指令必须切主线程执行
     * （Paper AsyncCatcher 拒绝异步方块修改：Asynchronous block remove!）。
     */
    @Override
    public Object debugCommand(JsonObject p) {
        if (Bukkit.isPrimaryThread()) return runDebugCommand(p);
        var fut = new java.util.concurrent.CompletableFuture<Object>();
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                try { fut.complete(runDebugCommand(p)); }
                catch (Exception e) { fut.complete(java.util.Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
            });
            return fut.get(DEBUG_COMMAND_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return java.util.Map.of("err", "debug command failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 指令解析（已在主线程执行）。 */
    private Object runDebugCommand(JsonObject p) {
        var cmd = p.has("cmd") ? p.get("cmd").getAsString() : "";
        try {
            return switch (cmd) {
                case "fill" -> fillBlocks(p);
                default -> java.util.Map.of("err", "unknown debug command: " + cmd);
            };
        } catch (Exception e) {
            return java.util.Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * 性能基准 fill：Java 侧循环指定区域对每个方块 setType。
     * 参数：world / x1 y1 z1 x2 y2 z2 / type（方块名如 "stone"）。
     * 返回 { count, elapsedMs, blocksPerSec }。请在已加载区块区域内测试
     * （未加载区块的 getBlockAt 返回代理块，setType 无效果）。
     */
    private Object fillBlocks(JsonObject p) {
        var world = Bukkit.getWorld(p.get("world").getAsString());
        if (world == null) return java.util.Map.of("err", "world not found: " + p.get("world").getAsString());
        var mat = Material.matchMaterial(p.get("type").getAsString());
        if (mat == null || !mat.isBlock()) return java.util.Map.of("err", "invalid block type: " + p.get("type").getAsString());
        int x1 = p.get("x1").getAsInt(), y1 = p.get("y1").getAsInt(), z1 = p.get("z1").getAsInt();
        int x2 = p.get("x2").getAsInt(), y2 = p.get("y2").getAsInt(), z2 = p.get("z2").getAsInt();
        if (x1 > x2) { int t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { int t = y1; y1 = y2; y2 = t; }
        if (z1 > z2) { int t = z1; z1 = z2; z2 = t; }
        long count = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (count > MAX_FILL_BLOCKS)
            return java.util.Map.of("err", "fill region too large: " + count + " > " + MAX_FILL_BLOCKS);
        long start = System.nanoTime();
        int done = 0;
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++) {
                    // 与 world.setBlock 任务行为一致：默认 setType（触发物理更新）
                    world.getBlockAt(x, y, z).setType(mat);
                    done++;
                }
        long elapsedNs = System.nanoTime() - start;
        double secs = elapsedNs / 1_000_000_000.0;
        return java.util.Map.of(
            "count", done,
            "elapsedMs", elapsedNs / 1_000_000,
            "blocksPerSec", (long) (done / secs));
    }

    private static String resolveServerPath(String p) {
        var path = java.nio.file.Path.of(p);
        return (path.isAbsolute() ? path : java.nio.file.Path.of(System.getProperty("user.dir"), p)).normalize().toString();
    }
}
