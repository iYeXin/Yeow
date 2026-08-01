import { post } from './task.js';
import { BossBarHandle } from './instance-id.js';

export interface BossBarOptions {
  color?: string;
  style?: string;
  progress?: number;
  visible?: boolean;
}

export async function createBossBar(title: string, options?: BossBarOptions): Promise<BossBarHandle> {
  const h = new BossBarHandle();
  await post('bossbar.create', { id: h.toString(), title, ...options });
  return h;
}

export function destroy(id: BossBarHandle): Promise<void> {
  return post('bossbar.destroy', { id: id.toString() });
}

export function setTitle(id: BossBarHandle, title: string): Promise<void> {
  return post('bossbar.setTitle', { id: id.toString(), title });
}

export function setProgress(id: BossBarHandle, progress: number): Promise<void> {
  return post('bossbar.setProgress', { id: id.toString(), progress });
}

export function setColor(id: BossBarHandle, color: string): Promise<void> {
  return post('bossbar.setColor', { id: id.toString(), color });
}

export function setStyle(id: BossBarHandle, style: string): Promise<void> {
  return post('bossbar.setStyle', { id: id.toString(), style });
}

export function setVisible(id: BossBarHandle, visible: boolean): Promise<void> {
  return post('bossbar.setVisible', { id: id.toString(), visible });
}

export function addPlayer(id: BossBarHandle, uuid: string): Promise<void> {
  return post('bossbar.addPlayer', { id: id.toString(), uuid });
}

export function removePlayer(id: BossBarHandle, uuid: string): Promise<void> {
  return post('bossbar.removePlayer', { id: id.toString(), uuid });
}

export function removeAll(id: BossBarHandle): Promise<void> {
  return post('bossbar.removeAll', { id: id.toString() });
}

export function addFlag(id: BossBarHandle, flag: string): Promise<void> {
  return post('bossbar.addFlag', { id: id.toString(), flag });
}

export function removeFlag(id: BossBarHandle, flag: string): Promise<void> {
  return post('bossbar.removeFlag', { id: id.toString(), flag });
}
