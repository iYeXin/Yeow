import { post } from './task.js';

export interface AdvancementProgress {
  awardedCriteria: string[];
  remainingCriteria: string[];
}

export function grant(uuid: string, key: string): Promise<boolean> {
  return post<boolean>('advancement.grant', { uuid, key });
}

export function revoke(uuid: string, key: string): Promise<boolean> {
  return post<boolean>('advancement.revoke', { uuid, key });
}

export function getProgress(uuid: string, key: string): Promise<AdvancementProgress | null> {
  return post<AdvancementProgress | null>('advancement.getProgress', { uuid, key });
}

export function awardCriteria(uuid: string, key: string, criteria: string): Promise<boolean> {
  return post<boolean>('advancement.awardCriteria', { uuid, key, criteria });
}

export function revokeCriteria(uuid: string, key: string, criteria: string): Promise<boolean> {
  return post<boolean>('advancement.revokeCriteria', { uuid, key, criteria });
}
