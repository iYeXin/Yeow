import { post } from './task.js';
import type { TaskOptions } from './task.js';

// 玩家侧 stopSound/stopAllSounds 已上移到 `player.ts` 的 Player 实例方法
// （`player.stopSound(sound)` / `player.stopAllSounds()`）——不再提供顶层 uuid 函数。

/** 世界级音效（在指定世界坐标播放，对范围内玩家生效）。 */
export function playSound(world: string, sound: string, x: number, y: number, z: number, volume?: number, pitch?: number, options?: TaskOptions): Promise<void> {
  return post('world.playSound', { world, sound, x, y, z, volume, pitch }, options);
}
