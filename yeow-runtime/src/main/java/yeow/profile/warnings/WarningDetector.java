package yeow.profile.warnings;

import yeow.profile.collector.WindowMetrics;

import java.util.List;

/**
 * 告警检测器：输入一个窗口的聚合数据，输出该窗口触发的告警。
 *
 * 设计约定：
 * - {@link #check} 是主路径上的纯逻辑（可持有少量自包含状态，如挂起计时），
 *   引擎负责节流与输出——检测器不直接写日志；
 * - 只报告"窗口内的事实"（如：某插件事件响应 >2s），跨窗口状态（挂起时长、积压计数）由检测器内部维护；
 * - LOW 队列的积压与延迟是设计语义，检测器一律不报告。
 */
public interface WarningDetector {

    /** 稳定告警码，如 "event.slow"。 */
    String code();

    WarningLevel level();

    /** 同类告警的最短输出间隔（ms）。 */
    long cooldownMs();

    List<Warning> check(WindowMetrics window);
}
