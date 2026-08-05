import { post } from './task.js';
import type { TaskOptions } from './task.js';

export function get(uuid: string, key: string, options?: TaskOptions): Promise<string | null> {
  return post<string | null>('pdc.get', { uuid, key }, options);
}

export function set(uuid: string, key: string, value: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.set', { uuid, key, value }, options);
}

export function has(uuid: string, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.has', { uuid, key }, options);
}

export function remove(uuid: string, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.remove', { uuid, key }, options);
}

export function keys(uuid: string, options?: TaskOptions): Promise<string[]> {
  return post<string[]>('pdc.keys', { uuid }, options);
}

export function getBlock(world: string, x: number, y: number, z: number, key: string, options?: TaskOptions): Promise<string | null> {
  return post<string | null>('pdc.get', { world, x, y, z, key }, options);
}

export function setBlock(world: string, x: number, y: number, z: number, key: string, value: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.set', { world, x, y, z, key, value }, options);
}

export function hasBlock(world: string, x: number, y: number, z: number, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.has', { world, x, y, z, key }, options);
}

export function removeBlock(world: string, x: number, y: number, z: number, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.remove', { world, x, y, z, key }, options);
}
