import { call, post } from './task.js';

interface ItemData {
  type: string;
  amount: number;
}

export class Inventory {
  constructor(private readonly uuid: string) {}

  getItem(slot: number): Promise<ItemData | null> {
    return post<ItemData | null>('inventory.getItem', { uuid: this.uuid, slot });
  }
  getItemSync(slot: number): ItemData | null {
    return call<ItemData | null>('inventory.getItem', { uuid: this.uuid, slot });
  }
  setItem(slot: number, itemType: string, amount?: number): Promise<void> {
    return post('inventory.setItem', { uuid: this.uuid, slot, itemType, amount });
  }
  setItemSync(slot: number, itemType: string, amount?: number): void {
    call('inventory.setItem', { uuid: this.uuid, slot, itemType, amount });
  }
  addItem(itemType: string, amount?: number): Promise<void> {
    return post('inventory.addItem', { uuid: this.uuid, itemType, amount });
  }
  addItemSync(itemType: string, amount?: number): void {
    call('inventory.addItem', { uuid: this.uuid, itemType, amount });
  }
  removeItem(itemType: string, amount?: number): Promise<void> {
    return post('inventory.removeItem', { uuid: this.uuid, itemType, amount });
  }
  removeItemSync(itemType: string, amount?: number): void {
    call('inventory.removeItem', { uuid: this.uuid, itemType, amount });
  }
  clear(slot?: number): Promise<void> {
    return post('inventory.clear', { uuid: this.uuid, slot });
  }
  clearSync(slot?: number): void {
    call('inventory.clear', { uuid: this.uuid, slot });
  }
}
