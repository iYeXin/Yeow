export interface ItemStack {
  type: string;
  amount?: number;
  meta?: {
    displayName?: string;
    lore?: string[];
    customModelData?: number;
    unbreakable?: boolean;
    hideTooltip?: boolean;
    enchantments?: Record<string, number>;
    itemFlags?: string[];
  };
}
