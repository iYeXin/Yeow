import type { Player } from './player.js';
import type { Entity, LivingEntity } from './entity.js';

/**
 * 以玩家/实体为目标的参数统一类型：**对象或字符串**。
 *
 * 传对象（`Player` / `LivingEntity` / `Entity`）时取其 `uuid`；传字符串时按原样
 * 当作 uuid（或按具体 API 上下文为玩家名——如计分板 entry）。彻底消除
 * 「必须手动传 uuid」的摩擦：有对象直接传对象，只有原始 id 时才传字符串。
 */
export type PlayerTarget = Player | string;

export type LivingTarget = LivingEntity | string;

export type EntityTarget = Entity | string;

/** 解析目标 → uuid：对象取其 `uuid`，字符串原样返回。 */
export function resolveUuid(t: PlayerTarget | LivingTarget | EntityTarget): string {
  return typeof t === 'string' ? t : t.uuid;
}
