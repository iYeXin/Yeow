package yeow.folia;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import yeow.PlatformHost;
import yeow.RuntimeCore;
import yeow.YeowConfig;

import java.io.File;
import java.util.logging.Logger;

/**
 * Folia 平台适配层（实验性）。与 Paper 的差异：
 * - 无全局主线程：任务经 region/entity/global 调度器投递（FoliaScheduler）
 * - 事件桥在 region 线程自旋 pump（FoliaEventBridge）
 * - 任务执行器独立实现（FoliaTasks），不与 Paper 共享
 */
public class FoliaRuntime extends JavaPlugin implements PlatformHost {
    private static FoliaRuntime instance;
    private RuntimeCore core;
    private FoliaScheduler scheduler;

    public static FoliaRuntime inst() { return instance; }

    // ── PlatformHost ────────────────────────────────────────────────

    @Override public Logger logger() { return getLogger(); }
    @Override public String minecraftVersion() { return Bukkit.getMinecraftVersion(); }
    @Override public String runtimeVersion() { return getDescription() != null ? getDescription().getVersion() : null; }
    @Override public String platformName() { return "folia"; }
    @Override public File dataFolder() { return getDataFolder(); }
    @Override public boolean isGameThread() { return Bukkit.isGlobalTickThread(); }
    @Override public void onGameThread(Runnable r) { Bukkit.getGlobalRegionScheduler().run(this, t -> r.run()); }

    @Override
    public void purgePlatformResources(String pluginName) {
        FoliaCommandBridge.unregisterAll(pluginName);
        FoliaEventBridge.unsubscribeAll(pluginName);
        // GUI/BossBar/Scoreboard/Recipe/Advancement 骨架阶段未实现，无平台侧资源
    }

    @Override public void syncCommands() { FoliaCommandBridge.syncCommands(); }

    // ── 访问入口 ────────────────────────────────────────────────────

    public RuntimeCore core() { return core; }
    public FoliaScheduler getScheduler() { return scheduler; }

    // ── 生命周期 ────────────────────────────────────────────────────

    @Override public void onLoad() {
        instance = this;
        // Folia 平台专用配置（folia: section：调度预算/in-flight/空闲阻塞等待）
        var cfg = new YeowConfig(getDataFolder(), true);
        this.scheduler = new FoliaScheduler(cfg, this);
        this.core = new RuntimeCore(this, cfg, scheduler);
        scheduler.setProfileSink(core.profiler().sink());
    }

    @Override public void onEnable() {
        core.start();
        FoliaTasks.init(scheduler);
        FoliaEventBridge.init(this, scheduler);
        FoliaCommandBridge.init(this, scheduler);
        FoliaCommandBridge.setTimeoutMs(core.config().profileCallbackTimeoutTabCompleteMs());
        core.scanPluginDirectory();
        core.loadAllPlugins();
    }

    @Override public void onDisable() {
        core.shutdown();
        instance = null;
    }
}
