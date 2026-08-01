import { post } from './task.js';
import type { ItemStack } from './item.js';

export interface ParticleOptions {
  particle: string;
  x: number;
  y: number;
  z: number;
  world: string;
  count?: number;
  offsetX?: number;
  offsetY?: number;
  offsetZ?: number;
  speed?: number;
  force?: boolean;
  color?: { r: number; g: number; b: number; size?: number };
  blockType?: string;
  item?: ItemStack;
}

export function spawnParticle(options: ParticleOptions): Promise<void> {
  return post('world.spawnParticle', {
    world: options.world,
    particle: options.particle,
    x: options.x,
    y: options.y,
    z: options.z,
    count: options.count,
    offsetX: options.offsetX,
    offsetY: options.offsetY,
    offsetZ: options.offsetZ,
    speed: options.speed,
    force: options.force,
    color: options.color,
    blockType: options.blockType,
    item: options.item,
  });
}
