package yeow.folia;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 事件/补全模式的统一自旋 pump：进入事件模式（暂停通用调度循环）→ 只取指定
 * 插件队列的任务执行 → 等待完成信号或超时 → 退出事件模式（恢复通用调度）。
 *
 * 事件桥与命令桥共用——同一套 enter/exit + drainForPlugins 样板只写一次。
 * 多个事件/补全可并发（互不等待闸门）；事件模式计数归零时唤醒通用调度器。
 */
final class SpinPump {
    private SpinPump() {}

    /**
     * @param scheduler  Folia 调度器
     * @param plugins    事件期间只取这些插件的任务（插件 JS 单线程支点）
     * @param done       完成信号（latch 归零 / pend 完成）
     * @param timeoutMs  等待上限
     * @return true 全部完成；false 超时
     */
    static boolean spin(FoliaScheduler scheduler, Set<String> plugins, BooleanSupplier done, long timeoutMs) {
        var deadline = System.nanoTime() + timeoutMs * 1_000_000;
        scheduler.enterEventMode();
        try {
            while (System.nanoTime() < deadline && !done.getAsBoolean()) {
                scheduler.drainForPlugins(plugins);
                Thread.onSpinWait();
            }
            return done.getAsBoolean();
        } finally {
            scheduler.exitEventMode();
        }
    }
}
