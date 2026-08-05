import { post } from './task.js';
import type { TaskOptions } from './task.js';

export interface AdvancementProgress {
  awardedCriteria: string[];
  remainingCriteria: string[];
}

export function grant(uuid: string, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('advancement.grant', { uuid, key }, options);
}

export function revoke(uuid: string, key: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('advancement.revoke', { uuid, key }, options);
}

export function getProgress(uuid: string, key: string, options?: TaskOptions): Promise<AdvancementProgress | null> {
  return post<AdvancementProgress | null>('advancement.getProgress', { uuid, key }, options);
}

export function awardCriteria(uuid: string, key: string, criteria: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('advancement.awardCriteria', { uuid, key, criteria }, options);
}

export function revokeCriteria(uuid: string, key: string, criteria: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('advancement.revokeCriteria', { uuid, key, criteria }, options);
}
