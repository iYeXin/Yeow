import { post } from './task.js';
import type { TaskOptions } from './task.js';
import { BossBarHandle } from './instance-id.js';

export interface BossBarOptions {
  color?: string;
  style?: string;
  progress?: number;
  visible?: boolean;
}

export async function createBossBar(title: string, options?: BossBarOptions, taskOptions?: TaskOptions): Promise<BossBarHandle> {
  const h = new BossBarHandle();
  await post('bossbar.create', { id: h.toString(), title, ...options }, taskOptions);
  return h;
}

export function destroy(id: BossBarHandle, options?: TaskOptions): Promise<void> {
  return post('bossbar.destroy', { id: id.toString() }, options);
}

export function setTitle(id: BossBarHandle, title: string, options?: TaskOptions): Promise<void> {
  return post('bossbar.setTitle', { id: id.toString(), title }, options);
}

export function setProgress(id: BossBarHandle, progress: number, options?: TaskOptions): Promise<void> {
  return post('bossbar.setProgress', { id: id.toString(), progress }, options);
}

export function setColor(id: BossBarHandle, color: string, options?: TaskOptions): Promise<void> {
  return post('bossbar.setColor', { id: id.toString(), color }, options);
}

export function setStyle(id: BossBarHandle, style: string, options?: TaskOptions): Promise<void> {
  return post('bossbar.setStyle', { id: id.toString(), style }, options);
}

export function setVisible(id: BossBarHandle, visible: boolean, options?: TaskOptions): Promise<void> {
  return post('bossbar.setVisible', { id: id.toString(), visible }, options);
}

export function addPlayer(id: BossBarHandle, uuid: string, options?: TaskOptions): Promise<void> {
  return post('bossbar.addPlayer', { id: id.toString(), uuid }, options);
}

export function removePlayer(id: BossBarHandle, uuid: string, options?: TaskOptions): Promise<void> {
  return post('bossbar.removePlayer', { id: id.toString(), uuid }, options);
}

export function removeAll(id: BossBarHandle, options?: TaskOptions): Promise<void> {
  return post('bossbar.removeAll', { id: id.toString() }, options);
}

export function addFlag(id: BossBarHandle, flag: string, options?: TaskOptions): Promise<void> {
  return post('bossbar.addFlag', { id: id.toString(), flag }, options);
}

export function removeFlag(id: BossBarHandle, flag: string, options?: TaskOptions): Promise<void> {
  return post('bossbar.removeFlag', { id: id.toString(), flag }, options);
}
