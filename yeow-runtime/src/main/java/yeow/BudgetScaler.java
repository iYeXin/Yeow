package yeow;

import java.util.logging.Logger;

/**
 * 运行时预算缩放器（运行时组件，独立于 Profile）。
 *
 * 语义：HIGH/NORMAL 队列承载实时性与交互响应，不应存在积压——
 * 滑动窗口内（默认 40 tick）积压 tick 数达到阈值（默认 35）即自动扩容 tick 预算；
 * 持续无积压后逐级回落。LOW 批量队列不计入。
 */
public class BudgetScaler {
    private static final Logger LOG = Logger.getLogger("Yeow");

    private final int windowTicks;
    private final int thresholdTicks;
    private final double expansionFactor;
    private final double maxMultiplier;

    private final int[] window;
    private int idx;
    private int backlogCount;
    private int clearTicks;
    private double currentMultiplier = 1.0;
    private volatile long currentBudgetNs;
    private final long baseBudgetNs;
    private boolean atMaxWarned;

    private volatile Runnable budgetListener; // 可选：通知预算变化（如 /yeow 输出）

    public BudgetScaler(long baseBudgetNs, double expansionFactor, double maxMultiplier,
            int thresholdTicks, int windowTicks) {
        this.baseBudgetNs = baseBudgetNs;
        this.currentBudgetNs = baseBudgetNs;
        this.expansionFactor = expansionFactor;
        this.maxMultiplier = maxMultiplier;
        this.thresholdTicks = Math.max(1, thresholdTicks);
        this.windowTicks = Math.max(this.thresholdTicks, windowTicks);
        this.window = new int[this.windowTicks];
    }

    public void setBudgetListener(Runnable r) { this.budgetListener = r; }

    /** 每 tick 调用一次：HIGH/NORMAL 是否有积压。 */
    public synchronized void onTick(boolean hnHasBacklog) {
        backlogCount -= window[idx];
        window[idx] = hnHasBacklog ? 1 : 0;
        backlogCount += window[idx];
        idx = (idx + 1) % windowTicks;

        if (hnHasBacklog) {
            clearTicks = 0;
            if (backlogCount >= thresholdTicks && currentMultiplier < maxMultiplier) {
                double prev = currentMultiplier;
                currentMultiplier = Math.min(currentMultiplier * expansionFactor, maxMultiplier);
                currentBudgetNs = (long) (baseBudgetNs * currentMultiplier);
                if (currentMultiplier >= maxMultiplier) {
                    if (!atMaxWarned) {
                        LOG.warning("[Yeow] tick budget reached max expansion (" + currentMultiplier + "x) — "
                            + "HIGH/NORMAL queues still congested");
                        atMaxWarned = true;
                    }
                } else {
                    LOG.info("[Yeow] tick budget expanded " + prev + "x -> " + currentMultiplier
                        + "x (HIGH/NORMAL backlog " + backlogCount + "/" + windowTicks + ")");
                    atMaxWarned = false;
                }
                var l = budgetListener;
                if (l != null) l.run();
            }
        } else {
            clearTicks++;
            if (clearTicks >= windowTicks && currentMultiplier > 1.01) {
                double prev = currentMultiplier;
                currentMultiplier = Math.max(currentMultiplier / expansionFactor, 1.0);
                currentBudgetNs = (long) (baseBudgetNs * currentMultiplier);
                clearTicks = 0;
                if (currentMultiplier == 1.0) {
                    LOG.info("[Yeow] tick budget restored to baseline");
                }
                var l = budgetListener;
                if (l != null) l.run();
            }
        }
    }

    public long currentBudgetNs() { return currentBudgetNs; }
    public double currentMultiplier() { return currentMultiplier; }
    public int backlogCount() { return backlogCount; }
}
