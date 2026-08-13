/**
 * ItemStack：物品纯数据描述符（值语义，快照——不绑定真实物品）。
 *
 * meta 字段设计（2026-08-13 扩展）：
 * - 遵循 Paper / Mojang 现行行为；字段名避开已废弃的 Java 方法名（如不叫 setDamage 语义）
 * - 全部字段可选；运行时不支持的字段静默忽略（跨版本兼容）
 * - 文本字段（displayName/lore）支持 MiniMessage
 */

/** 自定义药水效果（PotionMeta）。 */
export interface PotionEffectData {
  type: string;              // 药水效果名（如 'speed' / 'SPEED'，不区分大小写）
  duration?: number;         // 刻（默认 200）
  amplifier?: number;        // 等级（默认 0）
  ambient?: boolean;         // 是否环境粒子（信标样式，默认 false）
  particles?: boolean;       // 是否显示粒子（默认 true）
}

/** 属性修饰符（AttributeModifier）。 */
export interface AttributeModifierData {
  attribute: string;         // Bukkit Attribute 枚举名（如 'ATTACK_DAMAGE' / 'MOVEMENT_SPEED'）
  amount: number;
  operation: 'ADD_NUMBER' | 'ADD_SCALED_AMOUNT' | 'MULTIPLY_SCALED_1';
  slot?: string;             // 适用槽位：mainhand / offhand / feet / legs / chest / head / body / any（默认 any）
}

export interface ItemMeta {
  displayName?: string;                    // MiniMessage
  lore?: string[];                         // 每行 MiniMessage
  customModelData?: number;
  unbreakable?: boolean;
  hideTooltip?: boolean;
  enchantments?: Record<string, number>;   // 附魔 key（如 'minecraft:sharpness'）→ 等级
  itemFlags?: string[];                    // ItemFlag 枚举名（如 'HIDE_ATTRIBUTES'）
  damage?: number;                         // 耐久损伤值
  color?: string | { r: number; g: number; b: number };  // 皮革盔甲染色 / 自定义药水颜色（'#RRGGBB' 或 rgb 对象）
  potionEffects?: PotionEffectData[];      // 自定义药水效果（仅药水类物品生效）
  skullOwner?: string;                     // 玩家头颅：玩家名 / UUID / base64 纹理值
  attributeModifiers?: AttributeModifierData[];
}

export interface ItemStack {
  type: string;
  amount?: number;
  meta?: ItemMeta;
}

export namespace ItemStack {
  /** 构造物品（便利函数，等价于手写 `{ type, amount, meta }`）。 */
  export function create(type: string, amount = 1, meta?: ItemMeta): ItemStack {
    return meta ? { type, amount, meta } : { type, amount };
  }

  /** 深拷贝（快照语义：修改副本不影响原对象）。 */
  export function clone(item: ItemStack): ItemStack {
    return JSON.parse(JSON.stringify(item)) as ItemStack;
  }

  /** 深度相等（序列化比较）。 */
  export function equals(a: ItemStack, b: ItemStack): boolean {
    return JSON.stringify(a) === JSON.stringify(b);
  }
}
