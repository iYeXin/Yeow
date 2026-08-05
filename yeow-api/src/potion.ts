import { post } from './task.js';
import type { TaskOptions } from './task.js';

export interface PotionEffect {
  type: string;
  duration: number;
  amplifier: number;
  ambient?: boolean;
  particles?: boolean;
  icon?: boolean;
}

export function addPotionEffect(uuid: string, effect: PotionEffect, options?: TaskOptions): Promise<void> {
  return post('entity.addPotionEffect', { uuid, ...effect }, options);
}
export function removePotionEffect(uuid: string, type: string, options?: TaskOptions): Promise<void> {
  return post('entity.removePotionEffect', { uuid, type }, options);
}
export function clearPotionEffects(uuid: string, options?: TaskOptions): Promise<void> {
  return post('entity.clearPotionEffects', { uuid }, options);
}
export function getActivePotionEffects(uuid: string, options?: TaskOptions): Promise<PotionEffect[]> {
  return post<PotionEffect[]>('entity.getActivePotionEffects', { uuid }, options);
}
