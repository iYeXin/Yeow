import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { Location } from './location.js';
import type { ItemStack } from './item.js';

/** 方块状态（BlockData 键值对，值统一为字符串）。 */
export interface BlockState {
  [key: string]: string;
}

/**
 * Block —— **数据层面**的方块描述符（不绑定世界坐标）。
 * 对应 Bukkit BlockData：方块类型 + 状态键值对。
 */
export class Block {
  constructor(
    public readonly type: string,
    public readonly state?: BlockState,
  ) {}

  static of(type: string, state?: BlockState): Block {
    return new Block(type, state);
  }

  /** 派生一个带状态的新描述符（原对象不变）。 */
  withState(state: BlockState): Block {
    return new Block(this.type, { ...(this.state ?? {}), ...state });
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
}

/**
 * WorldBlock —— 世界中的方块（位置 + 数据描述符）。
 * 由 `world.getBlock(x, y, z)` 返回。
 */
export class WorldBlock {
  constructor(
    public readonly world: string,
    public readonly x: number,
    public readonly y: number,
    public readonly z: number,
    public readonly type: string,
    public readonly state?: BlockState,
  ) {}

  get location(): Location {
    return new Location(this.x, this.y, this.z, undefined, undefined, this.world);
  }

  /** 数据描述符视图（可传给 world.setBlock）。 */
  toBlock(): Block {
    return new Block(this.type, this.state);
  }

  isSolid(options?: TaskOptions): Promise<boolean> { return post<boolean>('block.isSolid', { world: this.world, x: this.x, y: this.y, z: this.z }, options); }
  isSolidSync(options?: TaskOptions): boolean { return call<boolean>('block.isSolid', { world: this.world, x: this.x, y: this.y, z: this.z }, options); }
  isLiquid(options?: TaskOptions): Promise<boolean> { return post<boolean>('block.isLiquid', { world: this.world, x: this.x, y: this.y, z: this.z }, options); }
  isLiquidSync(options?: TaskOptions): boolean { return call<boolean>('block.isLiquid', { world: this.world, x: this.x, y: this.y, z: this.z }, options); }
  isEmpty(options?: TaskOptions): Promise<boolean> { return post<boolean>('block.isEmpty', { world: this.world, x: this.x, y: this.y, z: this.z }, options); }
  isEmptySync(options?: TaskOptions): boolean { return call<boolean>('block.isEmpty', { world: this.world, x: this.x, y: this.y, z: this.z }, options); }
  breakNaturally(tool?: ItemStack, options?: TaskOptions): Promise<boolean> {
    const p: Record<string, unknown> = { world: this.world, x: this.x, y: this.y, z: this.z };
    if (tool) p.item = tool;
    return post<boolean>('block.breakNaturally', p, options);
  }
  breakNaturallySync(tool?: ItemStack, options?: TaskOptions): boolean {
    const p: Record<string, unknown> = { world: this.world, x: this.x, y: this.y, z: this.z };
    if (tool) p.item = tool;
    return call<boolean>('block.breakNaturally', p, options);
  }
}
