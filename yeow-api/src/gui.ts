import { post } from './task.js';
import { GUIHandle } from './instance-id.js';
import type { ItemStack } from './item.js';

export async function createGUI(size: number, title: string): Promise<GUIHandle> {
  const h = new GUIHandle();
  await post('gui.create', { id: h.toString(), size, title });
  return h;
}

export function destroy(id: GUIHandle): Promise<void> {
  return post('gui.destroy', { id: id.toString() });
}

export function open(id: GUIHandle, uuid: string): Promise<void> {
  return post('gui.open', { id: id.toString(), uuid });
}

export function close(id: GUIHandle): Promise<void> {
  return post('gui.close', { id: id.toString() });
}

export function setItem(id: GUIHandle, slot: number, item: ItemStack): Promise<void> {
  return post('gui.setItem', { id: id.toString(), slot, item });
}

export function fill(id: GUIHandle, item: ItemStack): Promise<void> {
  return post('gui.fill', { id: id.toString(), item });
}

export function clear(id: GUIHandle): Promise<void> {
  return post('gui.clear', { id: id.toString() });
}
