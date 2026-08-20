// 药水效果类型定义。操作已上移到 `entity.ts` 的 LivingEntity 实例方法
// （`entity.addPotionEffect(...)` 等）——不再提供顶层 uuid 函数。

/** 药水效果（`entity.addPotionEffect` 任务载荷；与 `getActivePotionEffects` 输出同结构）。 */
export interface PotionEffect {
  /** 药水效果类型（值域 R1 注册键，如 `minecraft:speed`；兼容旧枚举名，大小写不敏感）。 */
  type: string;
  /** 持续时间（tick）。 */
  duration: number;
  /** 等级（0 = 一级）。 */
  amplifier: number;
  /** 是否环境指示剂（默认 true）。 */
  ambient?: boolean;
  /** 是否显示粒子（默认 true）。 */
  particles?: boolean;
  /** 是否显示图标（默认 true）。 */
  icon?: boolean;
}
