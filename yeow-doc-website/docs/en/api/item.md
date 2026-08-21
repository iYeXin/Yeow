# ItemStack (item data)

`ItemStack` is Yeow's **pure data** descriptor for an item — value semantics, serializable for transport, and **not bound to any real item instance**.

```ts
import { ItemStack } from 'yeow-api';
import type { ItemStack as ItemStackType } from 'yeow-api';

interface ItemStack {
  type: string;                                  // Material key, e.g. "minecraft:diamond_pickaxe"
  amount?: number;                               // amount (defaults to 1)
  meta?: ItemMeta;
}

interface ItemMeta {
  displayName?: string;                          // display name (MiniMessage)
  lore?: string[];                               // lore lines (MiniMessage, one per line)
  customModelData?: number;                      // custom model data
  unbreakable?: boolean;                         // whether it is unbreakable
  hideTooltip?: boolean;                         // whether to hide the tooltip
  enchantments?: Record<string, number>;         // enchantments: key → level (e.g. { "minecraft:fortune": 3 })
  itemFlags?: string[];                          // ItemFlag enum names (HIDE_ATTRIBUTES, etc.)
  damage?: number;                               // durability damage value (durability consumed)
  color?: string | { r: number; g: number; b: number };  // leather armor dye / custom potion color ("#RRGGBB" or an rgb object)
  potionEffects?: PotionEffectData[];            // custom potion effects (only take effect on potion-type items)
  skullOwner?: string;                           // player head: player name / UUID / base64 texture value
  attributeModifiers?: AttributeModifierData[];  // attribute modifiers
}

interface PotionEffectData {
  type: string;          // potion effect: minecraft registry key (e.g. "minecraft:speed"; also accepts legacy enum names "speed"/"SPEED")
  duration?: number;     // ticks (defaults to 200)
  amplifier?: number;    // level (defaults to 0)
  ambient?: boolean;     // ambient particles (beacon style, defaults to false)
  particles?: boolean;   // show particles (defaults to true)
}

interface AttributeModifierData {
  attribute: string;                        // minecraft registry key (e.g. "minecraft:attack_damage"; also accepts legacy Bukkit enum name "ATTACK_DAMAGE")
  amount: number;
  operation: 'ADD_NUMBER' | 'ADD_SCALED_AMOUNT' | 'MULTIPLY_SCALED_1';
  slot?: string;                            // mainhand / offhand / feet / legs / chest / head / body / any (defaults to any)
}
```

> The valid ranges of enchantment and attribute modifier keys (`minecraft:sharpness`, `minecraft:attack_damage`) and of `itemFlags` (ItemFlag) are in the [Values appendix](../specifications/values.md) (enchantments/attributes under "Version transition domain", ItemFlag under "Directly maintained enum list").
>
> The max durability of tools/armor etc. is obtained via `Material.getMaxDurability(type)`; see [Material API](./material.md) for details.

## Utility functions

The `ItemStack` namespace provides construction and manipulation utilities:

```ts
// Construction
const sword = ItemStack.create('minecraft:diamond_sword', 1, {
    displayName: '<red>Magic Sword</red>',
    enchantments: { 'minecraft:sharpness': 10 },
});

// Deep copy (snapshot semantics: modifying the copy does not affect the original)
const copy = ItemStack.clone(sword);

// Deep equality
ItemStack.equals(sword, copy);   // true
```

## Semantics

- **Pure data**: describes an item's "appearance" without pointing to any real item — reading, constructing, and passing it around produce no side effects
- **Value semantics**: when passed in as a parameter it is a snapshot; when returned (e.g. from `getItemInMainHand`, `inventory.getItem`) it is the serialized data at that moment, and later changes to the real item are not reflected on the returned object
- **Serializable**: consistent across plugins and platforms (JSON payload at the protocol layer)
- **Compatibility**: all meta fields are optional; fields the runtime does not support are silently ignored (safe across versions). Text fields (displayName/lore) support MiniMessage; color supports `"#RRGGBB"` or `{r,g,b}`

## Use cases

```js
import { World } from 'yeow-api';

// ① Construction (custom Inventory items, tools, inventory writes, etc.)
const pickaxe = ItemStack.create('minecraft:diamond_pickaxe', 1, {
    displayName: '<gradient:aqua:blue>钻石镐</gradient>',
    enchantments: { 'minecraft:fortune': 3 },
    unbreakable: true,
});

// ② Read the item in the player's hand (snapshot, including meta)
const held = await player.getItemInMainHand();   // ItemStack | null (null when the main hand is empty)
console.log(held?.type, held?.meta?.enchantments);

// ③ Write to an inventory / custom Inventory (full meta takes effect)
await player.inventory.setItem(0, pickaxe);
await player.inventory.getItem(0);               // ItemStack | null (read back including meta)

// ④ Pass as a tool to world operations (simulate tool properties)
const world = World.getSync('world');
const b = await world.getBlock(0, 65, 0);
await b.breakNaturally(pickaxe);                 // simulate breaking with this tool (affects drops/speed)
```

## Real item interaction

`ItemStack` is data; it does **not operate on real items**.
