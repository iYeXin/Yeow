import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import type { ItemStack } from './item.js';
import { InstanceId } from './instance-id.js';

/**
 * Inventory —— 统一容器抽象，三种持有者：
 * - **玩家物品栏**：`player.inventory`
 * - **容器方块**（Chest / Furnace / Hopper / Barrel / Dispenser / Dropper / BrewingStand 等）：`block.getInventory()`
 * - **自定义 Inventory**（自定义箱子界面，原 GUI）：`Inventory.create(size, title)`
 *
 * ```js
 * // 玩家
 * await player.inventory.setItem(0, ItemStack.create('minecraft:diamond'));
 * // 容器方块（需方块有 location）
 * const chest = await world.getBlock(x, y, z);
 * await chest.getInventory().getItem(0);
 * // 自定义 Inventory
 * const inv = await Inventory.create(27, '<gold>Shop</gold>');
 * await inv.open(player.uuid);
 * eventOn('inventoryClick', (e) => {
 *   if (e.inventoryId === inv.toString()) { handleClick(e); }
 * });
 * await inv.destroy();
 * ```
 */
export class Inventory {
  /** 寻址方式：'player' | 'block' | 'custom'。 */
  private readonly kind: 'player' | 'block' | 'custom';
  /** 玩家寻址：uuid。 */
  private readonly uuid?: string;
  /** 方块寻址：世界坐标。 */
  private readonly block?: { world: string; x: number; y: number; z: number };
  /** 自定义寻址：句柄 id。 */
  private readonly id?: string;

  private constructor(kind: 'player' | 'block' | 'custom', uuid?: string, block?: { world: string; x: number; y: number; z: number }, id?: string) {
    this.kind = kind;
    this.uuid = uuid;
    this.block = block;
    this.id = id;
  }

  /** 玩家物品栏（等价于 `new Inventory('player', uuid)`，通常经 `player.inventory` 获取）。 */
  static ofPlayer(uuid: string): Inventory {
    return new Inventory('player', uuid);
  }

  /** 容器方块（需方块有 location；通常经 `block.getInventory()` 获取）。 */
  static ofBlock(world: string, x: number, y: number, z: number): Inventory {
    return new Inventory('block', undefined, { world, x, y, z });
  }

  /** 创建自定义 Inventory（size 为槽位数，须为 9 的倍数：9/18/27/36/45/54；title 支持 MiniMessage）。 */
  static async create(size: number, title: string, options?: TaskOptions): Promise<Inventory> {
    const h = new InstanceId();
    await post('inventory.create', { id: h.toString(), size, title }, options);
    return new Inventory('custom', undefined, undefined, h.toString());
  }

  /** 自定义 Inventory 的句柄 id（与 inventoryClick/Close 事件的 `inventoryId` 字段一致）。 */
  toString(): string {
    return this.id ?? '';
  }

  /** 持有者类型：'PLAYER' | 'CUSTOM' | 方块实体类型名（如 'CHEST'）。 */
  getType(options?: TaskOptions): Promise<string> {
    return post<string>('inventory.getType', this.address(), options);
  }

  /** 容器槽位数。 */
  getSize(options?: TaskOptions): Promise<number> {
    return post<number>('inventory.getSize', this.address(), options);
  }

  /** 全槽位快照数组（空槽为 null，长度 = 容器槽位数）。 */
  getContents(options?: TaskOptions): Promise<(ItemStack | null)[]> {
    return post<(ItemStack | null)[]>('inventory.getContents', this.address(), options);
  }
  getContentsSync(options?: TaskOptions): (ItemStack | null)[] {
    return call<(ItemStack | null)[]>('inventory.getContents', this.address(), options);
  }

  /** 整容器写入（items 长度可与容器不匹配：短数组只写前段，长数组忽略超出；null 清空对应槽位）。 */
  setContents(items: (ItemStack | null)[], options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.setContents', { ...this.address(), items }, options);
  }
  setContentsSync(items: (ItemStack | null)[], options?: TaskOptions): boolean {
    return call<boolean>('inventory.setContents', { ...this.address(), items }, options);
  }

  /** 读取槽位物品快照（含 meta；空槽返回 null）。 */
  getItem(slot: number, options?: TaskOptions): Promise<ItemStack | null> {
    return post<ItemStack | null>('inventory.getItem', { ...this.address(), slot }, options);
  }
  getItemSync(slot: number, options?: TaskOptions): ItemStack | null {
    return call<ItemStack | null>('inventory.getItem', { ...this.address(), slot }, options);
  }

  /** 设置槽位（完整 ItemStack 含 meta；传 null 清空槽位）。 */
  setItem(slot: number, item: ItemStack | null, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.setItem', { ...this.address(), slot, item }, options);
  }
  setItemSync(slot: number, item: ItemStack | null, options?: TaskOptions): boolean {
    return call<boolean>('inventory.setItem', { ...this.address(), slot, item }, options);
  }

  /** 批量设置多个槽位（分页/布局用）；item 传 null 清空对应槽位。 */
  setItems(slots: number[], item: ItemStack | null, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.setItems', { ...this.address(), slots, item }, options);
  }

  /** 用同一物品填充全部槽位。 */
  fill(item: ItemStack, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.fill', { ...this.address(), item }, options);
  }

  /** 添加物品到空位：返回**未放入数量**（0 = 全部放入；玩家物品栏溢出部分掉落在地上，同样返回 0）。 */
  addItem(item: ItemStack, options?: TaskOptions): Promise<number> {
    return post<number>('inventory.addItem', { ...this.address(), item }, options);
  }
  addItemSync(item: ItemStack, options?: TaskOptions): number {
    return call<number>('inventory.addItem', { ...this.address(), item }, options);
  }

  /** 移除指定物品（按类型 + meta 匹配，amount 默认 1）。返回**未移除数量**（0 = 全部移除成功）。 */
  removeItem(item: ItemStack, options?: TaskOptions): Promise<number> {
    return post<number>('inventory.removeItem', { ...this.address(), item }, options);
  }
  removeItemSync(item: ItemStack, options?: TaskOptions): number {
    return call<number>('inventory.removeItem', { ...this.address(), item }, options);
  }

  /** 清空（slot 可选，不传清空全部）。 */
  clear(slot?: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.clear', { ...this.address(), slot }, options);
  }
  clearSync(slot?: number, options?: TaskOptions): boolean {
    return call<boolean>('inventory.clear', { ...this.address(), slot }, options);
  }

  // ── 自定义 Inventory 专属 ─────────────────────────────────────────

  /** 为玩家打开（自定义 Inventory）。 */
  open(uuid: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.open', { ...this.address(), uuid }, options);
  }

  /** 关闭所有查看者（自定义 Inventory）。 */
  close(options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.close', this.address(), options);
  }

  /** 仅关闭指定玩家（自定义 Inventory）。 */
  closePlayer(uuid: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.closePlayer', { ...this.address(), uuid }, options);
  }

  /** 当前查看者 uuid 列表（自定义 Inventory）。 */
  getViewers(options?: TaskOptions): Promise<string[]> {
    return post<string[]>('inventory.getViewers', this.address(), options);
  }

  /** 销毁并关闭所有查看者（自定义 Inventory）。 */
  destroy(options?: TaskOptions): Promise<boolean> {
    return post<boolean>('inventory.destroy', this.address(), options);
  }

  private address(): Record<string, unknown> {
    if (this.kind === 'player') return { uuid: this.uuid };
    if (this.kind === 'block') return { ...this.block! };
    return { id: this.id };
  }
}
