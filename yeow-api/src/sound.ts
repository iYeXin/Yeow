import { post } from './task.js';
import type { TaskOptions } from './task.js';

export function playSound(world: string, sound: string, x: number, y: number, z: number, volume?: number, pitch?: number, options?: TaskOptions): Promise<void> {
  return post('world.playSound', { world, sound, x, y, z, volume, pitch }, options);
}

export function stopSound(uuid: string, sound: string, options?: TaskOptions): Promise<void> {
  return post('player.stopSound', { uuid, sound }, options);
}

export function stopAllSounds(uuid: string, options?: TaskOptions): Promise<void> {
  return post('player.stopAllSounds', { uuid }, options);
}
