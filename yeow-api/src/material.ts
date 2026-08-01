import { post } from './task.js';

export interface MaterialInfo {
  key: string;
  isBlock: boolean;
  isItem: boolean;
}

let _materials: MaterialInfo[] | null = null;

export async function getMaterials(): Promise<MaterialInfo[]> {
  if (_materials) return _materials;
  _materials = await post<MaterialInfo[]>('server.getMaterials', {});
  Object.freeze(_materials);
  for (const m of _materials) Object.freeze(m);
  return _materials;
}

let _blocks: string[] | null = null;

export async function getBlocks(): Promise<string[]> {
  if (_blocks) return _blocks;
  _blocks = await post<string[]>('server.getBlocks', {});
  Object.freeze(_blocks);
  return _blocks;
}

let _items: string[] | null = null;

export async function getItems(): Promise<string[]> {
  if (_items) return _items;
  _items = await post<string[]>('server.getItems', {});
  Object.freeze(_items);
  return _items;
}
