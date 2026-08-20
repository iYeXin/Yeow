// 进度操作已上移到 `player.ts` 的 Player 实例方法（`player.grantAdvancement(...)` 等）
// ——不再提供顶层 uuid 函数。

/** 进度完成情况。 */
export interface AdvancementProgress {
  awardedCriteria: string[];
  remainingCriteria: string[];
}
