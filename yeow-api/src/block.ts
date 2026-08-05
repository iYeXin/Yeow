import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { Location } from './location.js';
import type { ItemStack } from './item.js';

export class Block {
  constructor(
    public readonly world: string,
    public readonly x: number,
    public readonly y: number,
    public readonly z: number,
    public readonly type: string,
  ) {}

  get location(): Location {
    return new Location(this.x, this.y, this.z, undefined, undefined, this.world);
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
