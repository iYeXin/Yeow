import { post } from './task.js';
import type { TaskOptions } from './task.js';

export interface MaterialInfo {
  key: string;
  isBlock: boolean;
  isItem: boolean;
}

let _materials: MaterialInfo[] | null = null;

export async function getMaterials(options?: TaskOptions): Promise<MaterialInfo[]> {
  if (_materials) return _materials;
  _materials = await post<MaterialInfo[]>('server.getMaterials', {}, options);
  Object.freeze(_materials);
  for (const m of _materials) Object.freeze(m);
  return _materials;
}

let _blocks: string[] | null = null;

export async function getBlocks(options?: TaskOptions): Promise<string[]> {
  if (_blocks) return _blocks;
  _blocks = await post<string[]>('server.getBlocks', {}, options);
  Object.freeze(_blocks);
  return _blocks;
}

let _items: string[] | null = null;

export async function getItems(options?: TaskOptions): Promise<string[]> {
  if (_items) return _items;
  _items = await post<string[]>('server.getItems', {}, options);
  Object.freeze(_items);
  return _items;
}
