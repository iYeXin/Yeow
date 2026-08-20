import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { resolveUuid } from './target.js';
import type { PlayerTarget } from './target.js';

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

/** 线缆 board 参数：自定义计分板注入 `{ board: <id> }`，主计分板为空。 */
function wireBoard(boardId: string | null): Record<string, unknown> {
  return boardId != null ? { board: boardId } : {};
}

/** 计分板 entry 解析：Player 对象取其玩家名（Bukkit 计分板按玩家名登记），字符串原样。 */
function resolveEntry(t: PlayerTarget): string {
  return typeof t === 'string' ? t : t.name;
}

/**
 * Scoreboard —— 计分板对象（OOP）。主计分板用 `Scoreboard.main()`，自定义用
 * `Scoreboard.create(id)`；Objective / Team / Score 操作都在对应对象上调用，
 * 不再反复传 `board`/裸名。
 *
 * ```js
 * const board = await Scoreboard.create('myBoard');
 * const obj = await board.createObjective('kills', 'dummy', '<red>Kills</red>');
 * await obj.setDisplay('SIDEBAR');
 * await obj.setScore(player, 42);          // 接受 Player 对象或 entry 字符串
 * const team = await board.createTeam('red');
 * await team.add(player);
 * await board.attach(player);              // 为该玩家设置个人计分板
 * await board.destroy();
 * ```
 *
 * > **Folia 限制**：Folia 不支持注册新 objective/team（`createObjective` / `createTeam`
 * > 会 reject）；读取与修改已存在对象（`setScore` / `setTeamPrefix` 等）可用。
 */
export class Scoreboard {
  private constructor(readonly id: string | null) {}

  /** 主计分板（服务器默认）。 */
  static main(): Scoreboard {
    return new Scoreboard(null);
  }

  /** 创建自定义计分板（返回自身句柄）。 */
  static async create(id: string, options?: TaskOptions): Promise<Scoreboard> {
    await post('scoreboard.createBoard', { id }, options);
    return new Scoreboard(id);
  }

  static createSync(id: string, options?: TaskOptions): Scoreboard {
    call('scoreboard.createBoard', { id }, options);
    return new Scoreboard(id);
  }

  /** 销毁自定义计分板（主计分板不可销毁，抛错）。 */
  destroy(options?: TaskOptions): Promise<void> {
    if (this.id == null) return Promise.reject(new Error('cannot destroy main scoreboard'));
    return post('scoreboard.deleteBoard', { id: this.id }, options);
  }
  destroySync(options?: TaskOptions): void {
    if (this.id == null) throw new Error('cannot destroy main scoreboard');
    call('scoreboard.deleteBoard', { id: this.id }, options);
  }

  /** 为该玩家设置其个人计分板为本计分板（接受 `Player` 对象或 uuid；重置用 `Scoreboard.main().attach(player)`）。 */
  attach(player: PlayerTarget, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setPlayerBoard', { uuid: resolveUuid(player), ...wireBoard(this.id) }, options);
  }
  attachSync(player: PlayerTarget, options?: TaskOptions): void {
    call('scoreboard.setPlayerBoard', { uuid: resolveUuid(player), ...wireBoard(this.id) }, options);
  }

  // ── Objective ──

  createObjective(name: string, criteria: string, displayName: string, options?: TaskOptions): Promise<Objective> {
    return post<{ name: string; criteria: string }>('scoreboard.createObjective', { name, criteria, displayName, ...wireBoard(this.id) }, options)
      .then((o) => new Objective(this.id, o.name, o.criteria, null));
  }
  createObjectiveSync(name: string, criteria: string, displayName: string, options?: TaskOptions): Objective {
    const o = call<{ name: string; criteria: string }>('scoreboard.createObjective', { name, criteria, displayName, ...wireBoard(this.id) }, options);
    return new Objective(this.id, o.name, o.criteria, null);
  }

  getObjectives(options?: TaskOptions): Promise<Objective[]> {
    return post<ObjectiveInfo[]>('scoreboard.getObjectives', { ...wireBoard(this.id) }, options)
      .then((list) => list.map((o) => new Objective(this.id, o.name, o.criteria, o.displaySlot)));
  }
  getObjectivesSync(options?: TaskOptions): Objective[] {
    return call<ObjectiveInfo[]>('scoreboard.getObjectives', { ...wireBoard(this.id) }, options)
      .map((o) => new Objective(this.id, o.name, o.criteria, o.displaySlot));
  }

  // ── Team ──

  createTeam(name: string, options?: TaskOptions): Promise<Team> {
    return post('scoreboard.createTeam', { name, ...wireBoard(this.id) }, options)
      .then(() => this.getTeam(name))
      .then((t) => {
        if (!t) throw new Error('team created but not found: ' + name);
        return t;
      });
  }
  createTeamSync(name: string, options?: TaskOptions): Team {
    call('scoreboard.createTeam', { name, ...wireBoard(this.id) }, options);
    const t = this.getTeamSync(name);
    if (!t) throw new Error('team created but not found: ' + name);
    return t;
  }

  getTeam(name: string, options?: TaskOptions): Promise<Team | null> {
    return post<TeamInfo | null>('scoreboard.getTeam', { name, ...wireBoard(this.id) }, options)
      .then((info) => (info ? new Team(this.id, info) : null));
  }
  getTeamSync(name: string, options?: TaskOptions): Team | null {
    const info = call<TeamInfo | null>('scoreboard.getTeam', { name, ...wireBoard(this.id) }, options);
    return info ? new Team(this.id, info) : null;
  }

  getTeams(options?: TaskOptions): Promise<Team[]> {
    return post<TeamInfo[]>('scoreboard.getTeams', { ...wireBoard(this.id) }, options)
      .then((list) => list.map((info) => new Team(this.id, info)));
  }
  getTeamsSync(options?: TaskOptions): Team[] {
    return call<TeamInfo[]>('scoreboard.getTeams', { ...wireBoard(this.id) }, options)
      .map((info) => new Team(this.id, info));
  }
}

/** 计分项（objective）句柄。 */
export class Objective {
  constructor(
    private readonly boardId: string | null,
    public readonly name: string,
    public readonly criteria?: string,
    public readonly displaySlot: string | null = null,
  ) {}

  /** 设置显示位置（slot 为 BELOW_NAME/PLAYER_LIST/SIDEBAR；null 取消显示）。 */
  setDisplay(slot: string | null, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('scoreboard.setObjectiveDisplay', { name: this.name, slot, ...wireBoard(this.boardId) }, options);
  }
  setDisplaySync(slot: string | null, options?: TaskOptions): boolean {
    return call<boolean>('scoreboard.setObjectiveDisplay', { name: this.name, slot, ...wireBoard(this.boardId) }, options);
  }

  /** 设置分数（target 为 `Player` 对象或 entry 字符串）。 */
  setScore(target: PlayerTarget, value: number, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setScore', { objective: this.name, entry: resolveEntry(target), value, ...wireBoard(this.boardId) }, options);
  }
  setScoreSync(target: PlayerTarget, value: number, options?: TaskOptions): void {
    call('scoreboard.setScore', { objective: this.name, entry: resolveEntry(target), value, ...wireBoard(this.boardId) }, options);
  }

  /** 查询分数（无记录返回 null）。 */
  getScore(target: PlayerTarget, options?: TaskOptions): Promise<number | null> {
    return post<number | null>('scoreboard.getScore', { objective: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }
  getScoreSync(target: PlayerTarget, options?: TaskOptions): number | null {
    return call<number | null>('scoreboard.getScore', { objective: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }

  /** 重置（清除）分数。 */
  resetScore(target: PlayerTarget, options?: TaskOptions): Promise<void> {
    return post('scoreboard.resetScore', { objective: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }
  resetScoreSync(target: PlayerTarget, options?: TaskOptions): void {
    call('scoreboard.resetScore', { objective: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }

  /** 删除此计分项。 */
  delete(options?: TaskOptions): Promise<void> {
    return post('scoreboard.deleteObjective', { name: this.name, ...wireBoard(this.boardId) }, options);
  }
  deleteSync(options?: TaskOptions): void {
    call('scoreboard.deleteObjective', { name: this.name, ...wireBoard(this.boardId) }, options);
  }
}

/** 队伍（team）句柄；携带读取时刻的快照字段。 */
export class Team {
  readonly name: string;
  readonly displayName: string;
  readonly prefix: string;
  readonly suffix: string;
  readonly color: string;
  readonly allowFriendlyFire: boolean;
  readonly canSeeFriendlyInvisibles: boolean;
  readonly entries: string[];
  readonly options: TeamInfo['options'];

  constructor(private readonly boardId: string | null, info: TeamInfo) {
    this.name = info.name;
    this.displayName = info.displayName;
    this.prefix = info.prefix;
    this.suffix = info.suffix;
    this.color = info.color;
    this.allowFriendlyFire = info.allowFriendlyFire;
    this.canSeeFriendlyInvisibles = info.canSeeFriendlyInvisibles;
    this.entries = info.entries;
    this.options = info.options;
  }

  setDisplayName(v: string, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setTeamDisplayName', { name: this.name, displayName: v, ...wireBoard(this.boardId) }, options);
  }
  setDisplayNameSync(v: string, options?: TaskOptions): void {
    call('scoreboard.setTeamDisplayName', { name: this.name, displayName: v, ...wireBoard(this.boardId) }, options);
  }

  setPrefix(v: string, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setTeamPrefix', { name: this.name, prefix: v, ...wireBoard(this.boardId) }, options);
  }
  setPrefixSync(v: string, options?: TaskOptions): void {
    call('scoreboard.setTeamPrefix', { name: this.name, prefix: v, ...wireBoard(this.boardId) }, options);
  }

  setSuffix(v: string, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setTeamSuffix', { name: this.name, suffix: v, ...wireBoard(this.boardId) }, options);
  }
  setSuffixSync(v: string, options?: TaskOptions): void {
    call('scoreboard.setTeamSuffix', { name: this.name, suffix: v, ...wireBoard(this.boardId) }, options);
  }

  setColor(v: string, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setTeamColor', { name: this.name, color: v, ...wireBoard(this.boardId) }, options);
  }
  setColorSync(v: string, options?: TaskOptions): void {
    call('scoreboard.setTeamColor', { name: this.name, color: v, ...wireBoard(this.boardId) }, options);
  }

  setFriendlyFire(allow: boolean, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setTeamFriendlyFire', { name: this.name, allow, ...wireBoard(this.boardId) }, options);
  }
  setFriendlyFireSync(allow: boolean, options?: TaskOptions): void {
    call('scoreboard.setTeamFriendlyFire', { name: this.name, allow, ...wireBoard(this.boardId) }, options);
  }

  setSeeInvisible(canSee: boolean, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setTeamSeeInvisible', { name: this.name, canSee, ...wireBoard(this.boardId) }, options);
  }
  setSeeInvisibleSync(canSee: boolean, options?: TaskOptions): void {
    call('scoreboard.setTeamSeeInvisible', { name: this.name, canSee, ...wireBoard(this.boardId) }, options);
  }

  setOption(option: string, value: string, options?: TaskOptions): Promise<void> {
    return post('scoreboard.setTeamOption', { name: this.name, option, value, ...wireBoard(this.boardId) }, options);
  }
  setOptionSync(option: string, value: string, options?: TaskOptions): void {
    call('scoreboard.setTeamOption', { name: this.name, option, value, ...wireBoard(this.boardId) }, options);
  }

  /** 添加成员（`Player` 对象取其名，或 entry 字符串）。 */
  add(target: PlayerTarget, options?: TaskOptions): Promise<void> {
    return post('scoreboard.teamAddEntry', { name: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }
  addSync(target: PlayerTarget, options?: TaskOptions): void {
    call('scoreboard.teamAddEntry', { name: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }

  /** 移除成员。 */
  remove(target: PlayerTarget, options?: TaskOptions): Promise<void> {
    return post('scoreboard.teamRemoveEntry', { name: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }
  removeSync(target: PlayerTarget, options?: TaskOptions): void {
    call('scoreboard.teamRemoveEntry', { name: this.name, entry: resolveEntry(target), ...wireBoard(this.boardId) }, options);
  }

  /** 成员列表（快照）。 */
  getEntries(options?: TaskOptions): Promise<string[]> {
    return post<string[]>('scoreboard.teamGetEntries', { name: this.name, ...wireBoard(this.boardId) }, options);
  }
  getEntriesSync(options?: TaskOptions): string[] {
    return call<string[]>('scoreboard.teamGetEntries', { name: this.name, ...wireBoard(this.boardId) }, options);
  }

  /** 删除队伍。 */
  delete(options?: TaskOptions): Promise<void> {
    return post('scoreboard.deleteTeam', { name: this.name, ...wireBoard(this.boardId) }, options);
  }
  deleteSync(options?: TaskOptions): void {
    call('scoreboard.deleteTeam', { name: this.name, ...wireBoard(this.boardId) }, options);
  }
}
