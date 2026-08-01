import { post } from './task.js';

export interface PotionEffect {
  type: string;
  duration: number;
  amplifier: number;
  ambient?: boolean;
  particles?: boolean;
  icon?: boolean;
}

export function addPotionEffect(uuid: string, effect: PotionEffect): Promise<void> {
  return post('entity.addPotionEffect', { uuid, ...effect });
}
export function removePotionEffect(uuid: string, type: string): Promise<void> {
  return post('entity.removePotionEffect', { uuid, type });
}
export function clearPotionEffects(uuid: string): Promise<void> {
  return post('entity.clearPotionEffects', { uuid });
}
export function getActivePotionEffects(uuid: string): Promise<PotionEffect[]> {
  return post<PotionEffect[]>('entity.getActivePotionEffects', { uuid });
}
