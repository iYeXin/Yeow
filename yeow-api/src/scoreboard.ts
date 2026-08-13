import { post } from './task.js';
import type { TaskOptions } from './task.js';

export interface ObjectiveInfo {
  name: string;
  criteria: string;
  displaySlot: string | null;
}

export interface TeamInfo {
  name: string;
  displayName: string;
  prefix: string;
  suffix: string;
  color: string;
  allowFriendlyFire: boolean;
  canSeeFriendlyInvisibles: boolean;
  entries: string[];
  options: {
    nameTagVisibility: string;
    deathMessageVisibility: string;
    collisionRule: string;
  };
}

function boardParam(board?: string): Record<string, unknown> {
  return board ? { board } : {};
}

// ── Board ──

export function createBoard(id: string, options?: TaskOptions): Promise<string> {
  return post<string>('scoreboard.createBoard', { id }, options);
}

export function deleteBoard(id: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.deleteBoard', { id }, options);
}

// ── Objectives ──

/**
 * 创建计分项。⚠️ **Folia 平台限制**：Folia 不支持注册新 objective（registerNewObjective
 * 抛 UnsupportedOperationException）——在 Folia 上本调用会 reject（错误消息含
 * "Folia does not support creating new objectives"）；已存在的 objective 会更新 displayName 后返回。
 */
export function createObjective(name: string, criteria: string, displayName: string, board?: string, options?: TaskOptions): Promise<ObjectiveInfo> {
  return post<ObjectiveInfo>('scoreboard.createObjective', { name, criteria, displayName, ...boardParam(board) }, options);
}

export function deleteObjective(name: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.deleteObjective', { name, ...boardParam(board) }, options);
}

export function getObjectives(board?: string, options?: TaskOptions): Promise<ObjectiveInfo[]> {
  return post<ObjectiveInfo[]>('scoreboard.getObjectives', { ...boardParam(board) }, options);
}

export function setObjectiveDisplay(name: string, slot: string | null, board?: string, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('scoreboard.setObjectiveDisplay', { name, slot, ...boardParam(board) }, options);
}

export function getScore(objective: string, entry: string, board?: string, options?: TaskOptions): Promise<number | null> {
  return post<number | null>('scoreboard.getScore', { objective, entry, ...boardParam(board) }, options);
}

export function setScore(objective: string, entry: string, value: number, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setScore', { objective, entry, value, ...boardParam(board) }, options);
}

export function resetScore(objective: string, entry: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.resetScore', { objective, entry, ...boardParam(board) }, options);
}

// ── Teams ──

/**
 * 创建队伍。⚠️ **Folia 平台限制**：Folia 不支持注册新 team（registerNewTeam 抛
 * UnsupportedOperationException）——在 Folia 上本调用会 reject；已存在的 team 返回其信息。
 */
export function createTeam(name: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.createTeam', { name, ...boardParam(board) }, options);
}

export function deleteTeam(name: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.deleteTeam', { name, ...boardParam(board) }, options);
}

export function getTeam(name: string, board?: string, options?: TaskOptions): Promise<TeamInfo | null> {
  return post<TeamInfo | null>('scoreboard.getTeam', { name, ...boardParam(board) }, options);
}

export function getTeams(board?: string, options?: TaskOptions): Promise<TeamInfo[]> {
  return post<TeamInfo[]>('scoreboard.getTeams', { ...boardParam(board) }, options);
}

export function setTeamDisplayName(name: string, displayName: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setTeamDisplayName', { name, displayName, ...boardParam(board) }, options);
}

export function setTeamPrefix(name: string, prefix: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setTeamPrefix', { name, prefix, ...boardParam(board) }, options);
}

export function setTeamSuffix(name: string, suffix: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setTeamSuffix', { name, suffix, ...boardParam(board) }, options);
}

export function setTeamColor(name: string, color: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setTeamColor', { name, color, ...boardParam(board) }, options);
}

export function setTeamFriendlyFire(name: string, allow: boolean, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setTeamFriendlyFire', { name, allow, ...boardParam(board) }, options);
}

export function setTeamSeeInvisible(name: string, canSee: boolean, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setTeamSeeInvisible', { name, canSee, ...boardParam(board) }, options);
}

export function setTeamOption(name: string, option: string, value: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setTeamOption', { name, option, value, ...boardParam(board) }, options);
}

export function teamAddEntry(name: string, entry: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.teamAddEntry', { name, entry, ...boardParam(board) }, options);
}

export function teamRemoveEntry(name: string, entry: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.teamRemoveEntry', { name, entry, ...boardParam(board) }, options);
}

export function teamGetEntries(name: string, board?: string, options?: TaskOptions): Promise<string[]> {
  return post<string[]>('scoreboard.teamGetEntries', { name, ...boardParam(board) }, options);
}

export function setPlayerBoard(uuid: string, board?: string, options?: TaskOptions): Promise<void> {
  return post('scoreboard.setPlayerBoard', { uuid, ...boardParam(board) }, options);
}
