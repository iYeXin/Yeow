import { post } from './task.js';

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

export function createBoard(id: string): Promise<string> {
  return post<string>('scoreboard.createBoard', { id });
}

export function deleteBoard(id: string): Promise<void> {
  return post('scoreboard.deleteBoard', { id });
}

// ── Objectives ──

export function createObjective(name: string, criteria: string, displayName: string, board?: string): Promise<ObjectiveInfo> {
  return post<ObjectiveInfo>('scoreboard.createObjective', { name, criteria, displayName, ...boardParam(board) });
}

export function deleteObjective(name: string, board?: string): Promise<void> {
  return post('scoreboard.deleteObjective', { name, ...boardParam(board) });
}

export function getObjectives(board?: string): Promise<ObjectiveInfo[]> {
  return post<ObjectiveInfo[]>('scoreboard.getObjectives', { ...boardParam(board) });
}

export function setObjectiveDisplay(name: string, slot: string | null, board?: string): Promise<boolean> {
  return post<boolean>('scoreboard.setObjectiveDisplay', { name, slot, ...boardParam(board) });
}

export function getScore(objective: string, entry: string, board?: string): Promise<number | null> {
  return post<number | null>('scoreboard.getScore', { objective, entry, ...boardParam(board) });
}

export function setScore(objective: string, entry: string, value: number, board?: string): Promise<void> {
  return post('scoreboard.setScore', { objective, entry, value, ...boardParam(board) });
}

export function resetScore(objective: string, entry: string, board?: string): Promise<void> {
  return post('scoreboard.resetScore', { objective, entry, ...boardParam(board) });
}

// ── Teams ──

export function createTeam(name: string, board?: string): Promise<void> {
  return post('scoreboard.createTeam', { name, ...boardParam(board) });
}

export function deleteTeam(name: string, board?: string): Promise<void> {
  return post('scoreboard.deleteTeam', { name, ...boardParam(board) });
}

export function getTeam(name: string, board?: string): Promise<TeamInfo | null> {
  return post<TeamInfo | null>('scoreboard.getTeam', { name, ...boardParam(board) });
}

export function getTeams(board?: string): Promise<TeamInfo[]> {
  return post<TeamInfo[]>('scoreboard.getTeams', { ...boardParam(board) });
}

export function setTeamDisplayName(name: string, displayName: string, board?: string): Promise<void> {
  return post('scoreboard.setTeamDisplayName', { name, displayName, ...boardParam(board) });
}

export function setTeamPrefix(name: string, prefix: string, board?: string): Promise<void> {
  return post('scoreboard.setTeamPrefix', { name, prefix, ...boardParam(board) });
}

export function setTeamSuffix(name: string, suffix: string, board?: string): Promise<void> {
  return post('scoreboard.setTeamSuffix', { name, suffix, ...boardParam(board) });
}

export function setTeamColor(name: string, color: string, board?: string): Promise<void> {
  return post('scoreboard.setTeamColor', { name, color, ...boardParam(board) });
}

export function setTeamFriendlyFire(name: string, allow: boolean, board?: string): Promise<void> {
  return post('scoreboard.setTeamFriendlyFire', { name, allow, ...boardParam(board) });
}

export function setTeamSeeInvisible(name: string, canSee: boolean, board?: string): Promise<void> {
  return post('scoreboard.setTeamSeeInvisible', { name, canSee, ...boardParam(board) });
}

export function setTeamOption(name: string, option: string, value: string, board?: string): Promise<void> {
  return post('scoreboard.setTeamOption', { name, option, value, ...boardParam(board) });
}

export function teamAddEntry(name: string, entry: string, board?: string): Promise<void> {
  return post('scoreboard.teamAddEntry', { name, entry, ...boardParam(board) });
}

export function teamRemoveEntry(name: string, entry: string, board?: string): Promise<void> {
  return post('scoreboard.teamRemoveEntry', { name, entry, ...boardParam(board) });
}

export function teamGetEntries(name: string, board?: string): Promise<string[]> {
  return post<string[]>('scoreboard.teamGetEntries', { name, ...boardParam(board) });
}

export function setPlayerBoard(uuid: string, board?: string): Promise<void> {
  return post('scoreboard.setPlayerBoard', { uuid, ...boardParam(board) });
}
