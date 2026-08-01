import { post } from './task.js';

export function get(uuid: string, key: string): Promise<string | null> {
  return post<string | null>('pdc.get', { uuid, key });
}

export function set(uuid: string, key: string, value: string): Promise<boolean> {
  return post<boolean>('pdc.set', { uuid, key, value });
}

export function has(uuid: string, key: string): Promise<boolean> {
  return post<boolean>('pdc.has', { uuid, key });
}

export function remove(uuid: string, key: string): Promise<boolean> {
  return post<boolean>('pdc.remove', { uuid, key });
}

export function keys(uuid: string): Promise<string[]> {
  return post<string[]>('pdc.keys', { uuid });
}

export function getBlock(world: string, x: number, y: number, z: number, key: string): Promise<string | null> {
  return post<string | null>('pdc.get', { world, x, y, z, key });
}

export function setBlock(world: string, x: number, y: number, z: number, key: string, value: string): Promise<boolean> {
  return post<boolean>('pdc.set', { world, x, y, z, key, value });
}

export function hasBlock(world: string, x: number, y: number, z: number, key: string): Promise<boolean> {
  return post<boolean>('pdc.has', { world, x, y, z, key });
}

export function removeBlock(world: string, x: number, y: number, z: number, key: string): Promise<boolean> {
  return post<boolean>('pdc.remove', { world, x, y, z, key });
}
