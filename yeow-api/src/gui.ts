import { post } from './task.js';
import type { TaskOptions } from './task.js';
import { GUIHandle } from './instance-id.js';
import type { ItemStack } from './item.js';

export async function createGUI(size: number, title: string, options?: TaskOptions): Promise<GUIHandle> {
  const h = new GUIHandle();
  await post('gui.create', { id: h.toString(), size, title }, options);
  return h;
}

export function destroy(id: GUIHandle, options?: TaskOptions): Promise<void> {
  return post('gui.destroy', { id: id.toString() }, options);
}

export function open(id: GUIHandle, uuid: string, options?: TaskOptions): Promise<void> {
  return post('gui.open', { id: id.toString(), uuid }, options);
}

export function close(id: GUIHandle, options?: TaskOptions): Promise<void> {
  return post('gui.close', { id: id.toString() }, options);
}

export function setItem(id: GUIHandle, slot: number, item: ItemStack, options?: TaskOptions): Promise<void> {
  return post('gui.setItem', { id: id.toString(), slot, item }, options);
}

export function fill(id: GUIHandle, item: ItemStack, options?: TaskOptions): Promise<void> {
  return post('gui.fill', { id: id.toString(), item }, options);
}

export function clear(id: GUIHandle, options?: TaskOptions): Promise<void> {
  return post('gui.clear', { id: id.toString() }, options);
}
