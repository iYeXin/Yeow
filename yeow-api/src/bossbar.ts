import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { BossBarHandle } from './instance-id.js';
import { resolveUuid } from './target.js';
import type { PlayerTarget } from './target.js';

export interface BossBarOptions {
  color?: string;
  style?: string;
  progress?: number;
  visible?: boolean;
}

/**
 * BossBar —— 面向玩家的血条对象（OOP）。
 *
 * ```js
 * const bar = await BossBar.create('<red>Loading...</red>', { color: 'RED', progress: 0.0 });
 * await bar.addPlayer(player);          // 接受 Player 对象或 uuid
 * await bar.setProgress(0.5);
 * await bar.destroy();
 * ```
 *
 * 事件比对：`bar.toString()` 返回底层句柄 id（与 `inventoryClick` 等比对）。
 * BossBar 颜色/样式/Flag 值域见 [值域附录 · 直接维护的枚举清单]。
 */
export class BossBar {
  private constructor(readonly id: string) {}

  /** 创建血条（title 支持 MiniMessage）。 */
  static async create(title: string, options?: BossBarOptions, taskOptions?: TaskOptions): Promise<BossBar> {
    const id = new BossBarHandle().toString();
    await post('bossbar.create', { id, title, ...options }, taskOptions);
    return new BossBar(id);
  }

  /** 底层句柄 id（用于事件比对）。 */
  get handle(): string { return this.id; }
  toString(): string { return this.id; }

  // ── 标题 / 进度 / 颜色 / 样式 / 可见性 ──

  set title(v: string) { call('bossbar.setTitle', { id: this.id, title: v }); }
  setTitle(title: string, options?: TaskOptions): Promise<void> { return post('bossbar.setTitle', { id: this.id, title }, options); }
  setTitleSync(title: string, options?: TaskOptions): void { call('bossbar.setTitle', { id: this.id, title }, options); }

  set progress(v: number) { call('bossbar.setProgress', { id: this.id, progress: v }); }
  setProgress(progress: number, options?: TaskOptions): Promise<void> { return post('bossbar.setProgress', { id: this.id, progress }, options); }
  setProgressSync(progress: number, options?: TaskOptions): void { call('bossbar.setProgress', { id: this.id, progress }, options); }

  set color(v: string) { call('bossbar.setColor', { id: this.id, color: v }); }
  setColor(color: string, options?: TaskOptions): Promise<void> { return post('bossbar.setColor', { id: this.id, color }, options); }
  setColorSync(color: string, options?: TaskOptions): void { call('bossbar.setColor', { id: this.id, color }, options); }

  set style(v: string) { call('bossbar.setStyle', { id: this.id, style: v }); }
  setStyle(style: string, options?: TaskOptions): Promise<void> { return post('bossbar.setStyle', { id: this.id, style }, options); }
  setStyleSync(style: string, options?: TaskOptions): void { call('bossbar.setStyle', { id: this.id, style }, options); }

  set visible(v: boolean) { call('bossbar.setVisible', { id: this.id, visible: v }); }
  setVisible(visible: boolean, options?: TaskOptions): Promise<void> { return post('bossbar.setVisible', { id: this.id, visible }, options); }
  setVisibleSync(visible: boolean, options?: TaskOptions): void { call('bossbar.setVisible', { id: this.id, visible }, options); }

  // ── 玩家绑定 ──

  /** 绑定玩家（接受 `Player` 对象或 uuid）。 */
  addPlayer(player: PlayerTarget, options?: TaskOptions): Promise<void> {
    return post('bossbar.addPlayer', { id: this.id, uuid: resolveUuid(player) }, options);
  }
  addPlayerSync(player: PlayerTarget, options?: TaskOptions): void {
    call('bossbar.addPlayer', { id: this.id, uuid: resolveUuid(player) }, options);
  }

  /** 移除玩家（接受 `Player` 对象或 uuid）。 */
  removePlayer(player: PlayerTarget, options?: TaskOptions): Promise<void> {
    return post('bossbar.removePlayer', { id: this.id, uuid: resolveUuid(player) }, options);
  }
  removePlayerSync(player: PlayerTarget, options?: TaskOptions): void {
    call('bossbar.removePlayer', { id: this.id, uuid: resolveUuid(player) }, options);
  }

  /** 移除全部绑定的玩家。 */
  removeAllPlayers(options?: TaskOptions): Promise<void> {
    return post('bossbar.removeAll', { id: this.id }, options);
  }
  removeAllPlayersSync(options?: TaskOptions): void {
    call('bossbar.removeAll', { id: this.id }, options);
  }

  // ── Flags ──

  addFlag(flag: string, options?: TaskOptions): Promise<void> {
    return post('bossbar.addFlag', { id: this.id, flag }, options);
  }
  addFlagSync(flag: string, options?: TaskOptions): void {
    call('bossbar.addFlag', { id: this.id, flag }, options);
  }

  removeFlag(flag: string, options?: TaskOptions): Promise<void> {
    return post('bossbar.removeFlag', { id: this.id, flag }, options);
  }
  removeFlagSync(flag: string, options?: TaskOptions): void {
    call('bossbar.removeFlag', { id: this.id, flag }, options);
  }

  /** 销毁血条（此后句柄失效）。 */
  destroy(options?: TaskOptions): Promise<void> {
    return post('bossbar.destroy', { id: this.id }, options);
  }
  destroySync(options?: TaskOptions): void {
    call('bossbar.destroy', { id: this.id }, options);
  }
}
