import { call, post } from './task.js';
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
  isSolid(): Promise<boolean> { return post<boolean>('block.isSolid', { world: this.world, x: this.x, y: this.y, z: this.z }); }
  isSolidSync(): boolean { return call<boolean>('block.isSolid', { world: this.world, x: this.x, y: this.y, z: this.z }); }
  isLiquid(): Promise<boolean> { return post<boolean>('block.isLiquid', { world: this.world, x: this.x, y: this.y, z: this.z }); }
  isLiquidSync(): boolean { return call<boolean>('block.isLiquid', { world: this.world, x: this.x, y: this.y, z: this.z }); }
  isEmpty(): Promise<boolean> { return post<boolean>('block.isEmpty', { world: this.world, x: this.x, y: this.y, z: this.z }); }
  isEmptySync(): boolean { return call<boolean>('block.isEmpty', { world: this.world, x: this.x, y: this.y, z: this.z }); }
  breakNaturally(tool?: ItemStack): Promise<boolean> {
    const p: Record<string, unknown> = { world: this.world, x: this.x, y: this.y, z: this.z };
    if (tool) p.item = tool;
    return post<boolean>('block.breakNaturally', p);
  }
  breakNaturallySync(tool?: ItemStack): boolean {
    const p: Record<string, unknown> = { world: this.world, x: this.x, y: this.y, z: this.z };
    if (tool) p.item = tool;
    return call<boolean>('block.breakNaturally', p);
  }
}
