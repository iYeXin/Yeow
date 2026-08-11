package yeow;

import com.google.gson.JsonObject;

import java.io.File;
import java.util.logging.Logger;

/**
 * 平台桥：core 对宿主平台的**全部**逆依赖点（唯一平台耦合面，编译期由模块边界强制）。
 *
 * Paper/Bukkit 实现见 {@code yeow.paper.YeowRuntime}；其他 JVM 平台（Folia 等）
 * 实现本接口即可接入同一套引擎。
 */
public interface PlatformHost {

    /** 宿主日志器（JS 插件日志、引擎告警统一输出）。 */
    Logger logger();

    /** Minecraft 版本（env 通道）。 */
    String minecraftVersion();

    /** 运行时自身版本（env 通道 yeow.version；未知时返回 null）。 */
    String runtimeVersion();

    /** 平台名（env 通道 yeow.platform，如 "paper"）。 */
    String platformName();

    /** 运行时数据目录（plugins/Yeow，插件扫描/缓存/批准存储/报告）。 */
    File dataFolder();

    /** 当前线程是否宿主游戏线程（Paper：主线程）。 */
    boolean isGameThread();

    /** 在游戏线程上执行（非游戏线程调用时投递；游戏线程上直接执行）。 */
    void onGameThread(Runnable r);

    /**
     * 执行一个游戏任务（平台任务分表）。对应协议层 task 通道；
     * Paper 实现为 {@code yeow.task.Tasks.execute}。
     */
    Object executeTask(String taskType, JsonObject params) throws Exception;

    /**
     * 清理插件的平台侧资源：命令注销、事件退订、GUI/BossBar 句柄释放。
     * 插件卸载、热重载、Worker 卸载时由引擎调用。
     */
    void purgePlatformResources(String pluginName);

    /** 命令注册表变更后同步（刷新客户端 Tab 补全列表）。 */
    void syncCommands();
}
