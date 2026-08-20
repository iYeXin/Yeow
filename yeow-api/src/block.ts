import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { Location } from './location.js';
import type { ItemStack } from './item.js';
import { Material } from './material.js';
import { Inventory } from './inventory.js';
import {
  getBlock as pdcGet, setBlock as pdcSet, hasBlock as pdcHas, removeBlock as pdcRemove,
  keysBlock as pdcKeys, getAllBlock as pdcGetAll,
} from './pdc.js';

/** 方块状态（Minecraft 原版键值对枚举；值为字符串 / 数字 / 布尔——按原版语义保留类型）。 */
export interface BlockState {
  [key: string]: string | number | boolean;
}

/**
 * Block —— 方块数据描述符 + 可选的世界位置（location）。
 * 对应 Minecraft 原版的方块概念：类型 + 方块状态（键值对枚举，如
 * `facing`、`waterlogged`、`level` 等；值按原版语义保留类型——布尔 `waterlogged: false`、
 * 数字 `level: 8`、枚举串 `facing: "north"`）。
 *
 * **静态数据语义**：`type` / `state` / `location` 均为**获取时刻的快照**，
 * 之后世界变化不会自动更新；需要最新状态请重新调用 `world.getBlock`。
 *
 * 两种来源：
 * - `Block.of(type, state?)` —— 纯数据描述符，无 location（用于放置/比较）
 * - `world.getBlock(x, y, z)` —— 世界中的方块，带 location（yaw/pitch 忽略，为 0）
 */
export class Block {
  constructor(
    public readonly type: string,
    public readonly state?: BlockState,
    /** 世界位置（由 world.getBlock 返回时存在；yaw/pitch 恒为 0）。 */
    public readonly location?: Location,
  ) {}

  static of(type: string, state?: BlockState): Block {
    return new Block(type, state);
  }

  /** 派生一个带状态的新描述符（原对象不变）。 */
  withState(state: BlockState): Block {
    return new Block(this.type, { ...(this.state ?? {}), ...state }, this.location);
  }

  /** 是否与给定类型/状态相同（忽略空状态差异）。 */
  matches(type: string, state?: BlockState): boolean {
    if (this.type !== type) return false;
    if (!state || Object.keys(state).length === 0) return true;
    const s = this.state ?? {};
    for (const [k, v] of Object.entries(state)) {
      if (s[k] !== v) return false;
    }
    return true;
  }

  // ── 材料级静态判断（基于类型，委托 Material；不依赖位置/状态）──

  isSolid(options?: TaskOptions): Promise<boolean> { return Material.isSolid(this.type, options); }
  isSolidSync(options?: TaskOptions): boolean { return Material.isSolidSync(this.type, options); }
  isAir(options?: TaskOptions): Promise<boolean> { return Material.isAir(this.type, options); }
  isAirSync(options?: TaskOptions): boolean { return Material.isAirSync(this.type, options); }

  // ── 世界操作（需要 location）──

  private get pos(): { world: string; x: number; y: number; z: number } | null {
    const l = this.location;
    if (!l || l.world === undefined) return null;
    return { world: l.world, x: l.x, y: l.y, z: l.z };
  }

  /** 按该方块的位置自然破坏并掉落物品（需要 location）。 */
  breakNaturally(tool?: ItemStack, options?: TaskOptions): Promise<boolean> {
    const pos = this.pos;
    if (!pos) return Promise.reject(new Error('block has no location (create with world.getBlock)'));
    const p: Record<string, unknown> = { ...pos };
    if (tool) p.item = tool;
    return post<boolean>('block.breakNaturally', p, options);
  }
  breakNaturallySync(tool?: ItemStack, options?: TaskOptions): boolean {
    const pos = this.pos;
    if (!pos) throw new Error('block has no location (create with world.getBlock)');
    const p: Record<string, unknown> = { ...pos };
    if (tool) p.item = tool;
    return call<boolean>('block.breakNaturally', p, options);
  }

  // ── PDC（方块持久数据；需要 location） ──

  private requirePos(): { world: string; x: number; y: number; z: number } {
    const pos = this.pos;
    if (!pos) throw new Error('block has no location (create with world.getBlock)');
    return pos;
  }

  /** 容器方块的内容物（Chest / Furnace / Hopper / Barrel 等 Container；需要 location；非容器方块抛错）。 */
  getInventory(): Inventory {
    const { world, x, y, z } = this.requirePos();
    return Inventory.ofBlock(world, x, y, z);
  }

  /** 读取并 JSON 反序列化（无值返回 null；旧数据非 JSON 时原样返回字符串）。 */
  getPdc<T = unknown>(key: string, options?: TaskOptions): Promise<T | null> {
    const { world, x, y, z } = this.requirePos();
    return pdcGet(world, x, y, z, key, options);
  }

  /** 任意可 JSON 序列化的值自动序列化后写入。 */
  setPdc(key: string, value: unknown, options?: TaskOptions): Promise<boolean> {
    const { world, x, y, z } = this.requirePos();
    return pdcSet(world, x, y, z, key, value, options);
  }

  /** 键是否存在。 */
  hasPdc(key: string, options?: TaskOptions): Promise<boolean> {
    const { world, x, y, z } = this.requirePos();
    return pdcHas(world, x, y, z, key, options);
  }

  /** 移除键。 */
  removePdc(key: string, options?: TaskOptions): Promise<boolean> {
    const { world, x, y, z } = this.requirePos();
    return pdcRemove(world, x, y, z, key, options);
  }

  /** 全部键（完整 key 格式，含命名空间）。 */
  keysPdc(options?: TaskOptions): Promise<string[]> {
    const { world, x, y, z } = this.requirePos();
    return pdcKeys(world, x, y, z, options);
  }

  /** 全量读取本插件命名空间的键值（每个值 JSON 反序列化）。 */
  getAllPdc(options?: TaskOptions): Promise<Record<string, unknown>> {
    const { world, x, y, z } = this.requirePos();
    return pdcGetAll(world, x, y, z, options);
  }
}
