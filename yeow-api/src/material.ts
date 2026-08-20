import { call, post } from './task.js';
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

/**
 * Material —— 材料级静态判断对象（不依赖坐标/状态）。
 * 基于方块类型（material）判断其固有属性：固体/空气。
 */
export const Material = {
  /** 是否为固体方块（基于类型，状态不影响）。 */
  isSolid(type: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('material.isSolid', { type }, options);
  },
  isSolidSync(type: string, options?: TaskOptions): boolean {
    return call<boolean>('material.isSolid', { type }, options);
  },

  /** 是否为空气（空方块）。 */
  isAir(type: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('material.isAir', { type }, options);
  },
  isAirSync(type: string, options?: TaskOptions): boolean {
    return call<boolean>('material.isAir', { type }, options);
  },

  /** 最大耐久（非耐用品返回 0；未知类型抛错）。 */
  getMaxDurability(type: string, options?: TaskOptions): Promise<number> {
    return post<number>('material.getMaxDurability', { type }, options);
  },
  getMaxDurabilitySync(type: string, options?: TaskOptions): number {
    return call<number>('material.getMaxDurability', { type }, options);
  },
};
