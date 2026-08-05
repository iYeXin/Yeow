import { call, post } from './task.js';
import type { TaskOptions } from './task.js';

interface ItemData {
  type: string;
  amount: number;
}

export class Inventory {
  constructor(private readonly uuid: string) {}

  getItem(slot: number, options?: TaskOptions): Promise<ItemData | null> {
    return post<ItemData | null>('inventory.getItem', { uuid: this.uuid, slot }, options);
  }
  getItemSync(slot: number, options?: TaskOptions): ItemData | null {
    return call<ItemData | null>('inventory.getItem', { uuid: this.uuid, slot }, options);
  }
  setItem(slot: number, itemType: string, amount?: number, options?: TaskOptions): Promise<void> {
    return post('inventory.setItem', { uuid: this.uuid, slot, itemType, amount }, options);
  }
  setItemSync(slot: number, itemType: string, amount?: number, options?: TaskOptions): void {
    call('inventory.setItem', { uuid: this.uuid, slot, itemType, amount }, options);
  }
  addItem(itemType: string, amount?: number, options?: TaskOptions): Promise<void> {
    return post('inventory.addItem', { uuid: this.uuid, itemType, amount }, options);
  }
  addItemSync(itemType: string, amount?: number, options?: TaskOptions): void {
    call('inventory.addItem', { uuid: this.uuid, itemType, amount }, options);
  }
  removeItem(itemType: string, amount?: number, options?: TaskOptions): Promise<void> {
    return post('inventory.removeItem', { uuid: this.uuid, itemType, amount }, options);
  }
  removeItemSync(itemType: string, amount?: number, options?: TaskOptions): void {
    call('inventory.removeItem', { uuid: this.uuid, itemType, amount }, options);
  }
  clear(slot?: number, options?: TaskOptions): Promise<void> {
    return post('inventory.clear', { uuid: this.uuid, slot }, options);
  }
  clearSync(slot?: number, options?: TaskOptions): void {
    call('inventory.clear', { uuid: this.uuid, slot }, options);
  }
}
