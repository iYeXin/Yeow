import { post } from './task.js';
import type { TaskOptions } from './task.js';

// ══════════════════════════════════════════════════════════════════
// PDC：基于字符串的 K-V 存储。set/get 自动 JSON 序列化/反序列化——
// 开发者无需手写 JSON.stringify / JSON.parse（旧数据非 JSON 时 get 原样返回）。
// getRaw/setRaw 提供底层字符串读写（跨版本/跨语言数据交换用）。
// key 规则：无冒号的裸 key 使用**插件命名空间**（跨插件互不冲突）；
// 显式命名空间用 `ns:key`（如 `myplugin:score`）。
// ══════════════════════════════════════════════════════════════════

// ── 底层（raw 字符串） ────────────────────────────────────────────

/** 读取原始字符串（无值返回 null）。 */
export function getRaw(uuid: string, key: string, options?: TaskOptions): Promise<string | null> {
  return post<string | null>('pdc.get', { uuid, key }, options);
}

/** 写入原始字符串。 */
export function setRaw(uuid: string, key: string, value: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.set', { uuid, key, value }, options);
}

/** 键是否存在。 */
export function has(uuid: string, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.has', { uuid, key }, options);
}

/** 移除键。 */
export function remove(uuid: string, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.remove', { uuid, key }, options);
}

/** 全部键（完整 key 格式，含命名空间）。 */
export function keys(uuid: string, options?: TaskOptions): Promise<string[]> {
  return post<string[]>('pdc.keys', { uuid }, options);
}

/** 全量读取本插件命名空间的键值（value 为原始字符串）。 */
export function getAllRaw(uuid: string, options?: TaskOptions): Promise<Record<string, string>> {
  return post<Record<string, string>>('pdc.getAll', { uuid }, options);
}

// ── 推荐：JSON 自动序列化 ─────────────────────────────────────────

/** 读取并 JSON 反序列化（无值返回 null；旧数据非 JSON 时原样返回字符串）。 */
export async function get<T = unknown>(uuid: string, key: string, options?: TaskOptions): Promise<T | null> {
  const raw = await getRaw(uuid, key, options);
  if (raw == null) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return raw as unknown as T;
  }
}

/** 任意可 JSON 序列化的值自动序列化后写入。 */
export function set(uuid: string, key: string, value: unknown, options?: TaskOptions): Promise<boolean> {
  return setRaw(uuid, key, JSON.stringify(value), options);
}

/** 全量读取本插件命名空间的键值（每个值 JSON 反序列化；非 JSON 值原样保留）。 */
export async function getAll(uuid: string, options?: TaskOptions): Promise<Record<string, unknown>> {
  const raw = await getAllRaw(uuid, options);
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) {
    try {
      out[k] = JSON.parse(v);
    } catch {
      out[k] = v;
    }
  }
  return out;
}

// ── Block 变体（世界坐标） ────────────────────────────────────────

export function getBlockRaw(world: string, x: number, y: number, z: number, key: string, options?: TaskOptions): Promise<string | null> {
  return post<string | null>('pdc.get', { world, x, y, z, key }, options);
}

export function setBlockRaw(world: string, x: number, y: number, z: number, key: string, value: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.set', { world, x, y, z, key, value }, options);
}

export function hasBlock(world: string, x: number, y: number, z: number, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.has', { world, x, y, z, key }, options);
}

export function removeBlock(world: string, x: number, y: number, z: number, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('pdc.remove', { world, x, y, z, key }, options);
}

export function keysBlock(world: string, x: number, y: number, z: number, options?: TaskOptions): Promise<string[]> {
  return post<string[]>('pdc.keys', { world, x, y, z }, options);
}

export function getAllBlockRaw(world: string, x: number, y: number, z: number, options?: TaskOptions): Promise<Record<string, string>> {
  return post<Record<string, string>>('pdc.getAll', { world, x, y, z }, options);
}

export async function getBlock<T = unknown>(world: string, x: number, y: number, z: number, key: string, options?: TaskOptions): Promise<T | null> {
  const raw = await getBlockRaw(world, x, y, z, key, options);
  if (raw == null) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return raw as unknown as T;
  }
}

export function setBlock(world: string, x: number, y: number, z: number, key: string, value: unknown, options?: TaskOptions): Promise<boolean> {
  return setBlockRaw(world, x, y, z, key, JSON.stringify(value), options);
}

export async function getAllBlock(world: string, x: number, y: number, z: number, options?: TaskOptions): Promise<Record<string, unknown>> {
  const raw = await getAllBlockRaw(world, x, y, z, options);
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) {
    try {
      out[k] = JSON.parse(v);
    } catch {
      out[k] = v;
    }
  }
  return out;
}
