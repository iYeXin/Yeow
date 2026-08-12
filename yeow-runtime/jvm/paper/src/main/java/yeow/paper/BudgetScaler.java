package yeow.paper;

import java.util.logging.Logger;

/**
 * 杩愯鏃堕绠楃缉鏀惧櫒锛堣繍琛屾椂缁勪欢锛岀嫭绔嬩簬 Profile锛夈€? *
 * 璇箟锛欻IGH/NORMAL 闃熷垪鎵胯浇瀹炴椂鎬т笌浜や簰鍝嶅簲锛屼笉搴斿瓨鍦ㄧН鍘嬧€斺€? * 婊戝姩绐楀彛鍐咃紙榛樿 40 tick锛夌Н鍘?tick 鏁拌揪鍒伴槇鍊硷紙榛樿 35锛夊嵆鑷姩鎵╁ tick 棰勭畻锛? * 鎸佺画鏃犵Н鍘嬪悗閫愮骇鍥炶惤銆侺OW 鎵归噺闃熷垪涓嶈鍏ャ€? */
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

    private volatile Runnable budgetListener; // 鍙€夛細閫氱煡棰勭畻鍙樺寲锛堝 /yeow 杈撳嚭锛?
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

    /** 姣?tick 璋冪敤涓€娆★細HIGH/NORMAL 鏄惁鏈夌Н鍘嬨€?*/
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
                        LOG.warning("[Yeow] tick budget reached max expansion (" + currentMultiplier + "x) 鈥?"
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
