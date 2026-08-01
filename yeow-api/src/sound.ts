import { post } from './task.js';

export function playSound(world: string, sound: string, x: number, y: number, z: number, volume?: number, pitch?: number): Promise<void> {
  return post('world.playSound', { world, sound, x, y, z, volume, pitch });
}

export function stopSound(uuid: string, sound: string): Promise<void> {
  return post('player.stopSound', { uuid, sound });
}

export function stopAllSounds(uuid: string): Promise<void> {
  return post('player.stopAllSounds', { uuid });
}
