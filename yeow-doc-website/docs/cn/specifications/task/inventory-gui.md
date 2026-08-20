# Inventory 任务

统一容器操作（2026-08-13 重构：原 gui.* 任务族并入 inventory.*）。

**三寻址**（任务参数按持有者携带对应字段）：

| 持有者 | 寻址字段 | 说明 |
|------|----------|------|
| 玩家物品栏 | `uuid` | 玩家 UUID |
| 容器方块 | `world` + `x` + `y` + `z` | Container 方块实体（Chest / Furnace / Hopper / Barrel / Dispenser / Dropper / BrewingStand 等）；非容器抛错 |
| 自定义 Inventory（原 GUI） | `id` | `inventory.create` 创建的句柄 id |

`slot` 为槽位索引（玩家：0-35 主背包，36-39 盔甲，40 副手；方块：0 ~ 容器槽位）。

---

## 内容操作（三寻址通用）

| 任务 | 请求 | 返回 |
|------|------|------|
| `inventory.getItem` | `{ 寻址, "slot": <int> }` | `ItemStack` \| `null`（含 meta） |
| `inventory.setItem` | `{ 寻址, "slot": <int>, "item": <ItemStack \| null> }` | `true` |
| `inventory.setItems` | `{ 寻址, "slots": [<int>, ...], "item": <ItemStack \| null> }` | `true` |
| `inventory.fill` | `{ 寻址, "item": <ItemStack> }` | `true` |
| `inventory.addItem` | `{ 寻址, "item": <ItemStack> }` | `int`（未放入数量；0 = 全部放入。玩家物品栏溢出部分掉落在地上，仍返回 0） |
| `inventory.removeItem` | `{ 寻址, "item": <ItemStack> }` | `int`（未移除数量；0 = 全部移除。按类型 + meta 匹配，amount 默认 1） |
| `inventory.clear` | `{ 寻址, "slot": <int>? }`（slot 可选，不传清空全部） | `true` |
| `inventory.getSize` | `{ 寻址 }` | `int`（容器槽位数） |
| `inventory.getType` | `{ 寻址 }` | `string`（`"PLAYER"` / `"CUSTOM"` / 方块实体类型名如 `"CHEST"`） |
| `inventory.getContents` | `{ 寻址 }` | `(ItemStack \| null)[]`（全槽位快照，空槽为 null，长度 = 容器槽位数） |
| `inventory.setContents` | `{ 寻址, "items": [(ItemStack \| null), ...] }` | `true`（整容器写入；短数组只写前段，长数组忽略超出） |

## 自定义 Inventory 生命周期（`id` 寻址）

| 任务 | 请求 | 返回 |
|------|------|------|
| `inventory.create` | `{ "id": "<handle>", "size": <int>, "title": "<text>" }` | `string`（id，与请求一致） |
| `inventory.open` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `inventory.close` | `{ "id": "<handle>" }` | `true`（关闭所有查看者） |
| `inventory.closePlayer` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `inventory.getViewers` | `{ "id": "<handle>" }` | `string[]`（查看者 uuid 列表） |
| `inventory.destroy` | `{ "id": "<handle>" }` | `true`（销毁并关闭所有查看者） |

`size` 必须为 9 的倍数（最大 54）。自定义 Inventory 的 `_plugin` 归属由运行时注入，插件卸载/热重载时自动清理（`inventory.destroy` 未调用时）。

### 事件联动（inventoryId）

`inventoryClick` / `inventoryClose` 事件对发生在自定义 Inventory 上的交互携带 `inventoryId` 字段（`inventory.create` 传入的 handle id）——事件桥通过 Inventory 反查表识别。非自定义 Inventory（背包、箱子等）不携带。

---

## ItemStack 完整格式

`ItemStack` 为**纯数据**载荷（值语义快照，不绑定真实物品）——字段与语义见 [ItemStack API](../../api/item.md)：

```json
{
  "type": "minecraft:diamond_sword",
  "amount": 1,
  "meta": {
    "displayName": "<text>",
    "lore": ["<line1>", "<line2>"],
    "customModelData": 123,
    "unbreakable": true,
    "hideTooltip": false,
    "enchantments": { "minecraft:sharpness": 5, "minecraft:unbreaking": 3 },
    "itemFlags": ["HIDE_ENCHANTS", "HIDE_ATTRIBUTES"],
    "damage": 3,
    "color": "#FF0000",
    "potionEffects": [{ "type": "minecraft:speed", "duration": 200, "amplifier": 1 }],
    "skullOwner": "Notch",
    "attributeModifiers": [{ "attribute": "minecraft:attack_damage", "amount": 5, "operation": "ADD_NUMBER", "slot": "mainhand" }]
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | Material 命名空间 key |
| `amount` | int | 否(默认1) | 数量 |
| `meta` | object | 否 | 物品元数据 |
| `meta.displayName` | string | 否 | 展示名称（MiniMessage 格式） |
| `meta.lore` | string[] | 否 | 物品描述 |
| `meta.customModelData` | int | 否 | 自定义模型数据 |
| `meta.unbreakable` | bool | 否 | 不可破坏 |
| `meta.hideTooltip` | bool | 否 | 隐藏提示框 |
| `meta.enchantments` | object | 否 | 附魔映射（key → 等级）。key 为附魔命名空间 key（如 `minecraft:sharpness`） |
| `meta.itemFlags` | string[] | 否 | 物品标志（ItemFlag 枚举名，如 `HIDE_ENCHANTS`） |
| `meta.damage` | int | 否 | 耐久损伤值（被损耗的耐久） |
| `meta.color` | string \| object | 否 | 皮革盔甲染色 / 自定义药水颜色（`"#RRGGBB"` 或 `{r,g,b}`） |
| `meta.potionEffects` | object[] | 否 | 自定义药水效果（仅药水类物品生效）：`{type, duration?, amplifier?, ambient?, particles?}`（`type` 为 minecraft 注册键，如 `minecraft:speed`；兼容旧式枚举名） |
| `meta.skullOwner` | string | 否 | 玩家头颅：玩家名 / UUID / base64 纹理值 |
| `meta.attributeModifiers` | object[] | 否 | 属性修饰符：`{attribute, amount, operation, slot?}`（`attribute` 为 minecraft 注册键如 `minecraft:attack_damage`，兼容旧式枚举名；operation: `ADD_NUMBER`/`ADD_SCALED_AMOUNT`/`MULTIPLY_SCALED_1`；slot: `mainhand`/`offhand`/`feet`/`legs`/`chest`/`head`/`body`/`any`） |

> 扩展字段（damage/color/potionEffects/skullOwner/attributeModifiers，2026-08-13）：运行时不支持的字段**静默忽略**（跨版本兼容）。
