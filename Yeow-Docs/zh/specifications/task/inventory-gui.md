# Inventory 任务

物品栏和自定义 GUI 操作。

---

## 物品栏

`uuid` 为玩家 UUID。`slot` 为槽位索引（0-40：0-35 为主背包，36-39 为盔甲，40 为副手）。

### 读取/写入

| 任务 | 请求 | 返回 |
|------|------|------|
| `inventory.getItem` | `{ "uuid": "<uuid>", "slot": <int> }` | `{ "type": "<key>", "amount": <int> }` | `null` |
| `inventory.setItem` | `{ "uuid": "<uuid>", "slot": <int>, "itemType": "<material>", "amount": <int> }` | `true` |
| `inventory.addItem` | `{ "uuid": "<uuid>", "itemType": "<material>", "amount": <int> }` | `true` |
| `inventory.removeItem` | `{ "uuid": "<uuid>", "itemType": "<material>", "amount": <int> }` | `true` |
| `inventory.clear` | `{ "uuid": "<uuid>", "slot": <int> }` (slot 可选，不传则清空全部) | `true` |

`addItem` 行为：尝试将物品加入玩家库存；无法放入的部分掉落在地上。

---

## 自定义 GUI

### `gui.create`

创建自定义 GUI。

- **请求**：`{ "id": "<handle>", "size": <int>, "title": "<text>" }`
- **返回**：`string` (id, 与请求中传入的 id 相同)

`size` 必须为 9 的倍数（最大 54）。

### `gui.setItem`

设置 GUI 中的物品。

- **请求**：`{ "id": "<handle>", "slot": <int>, "item": <ItemStack> }`
- **返回**：`true`

### `gui.fill`

用同一物品填满整个 GUI。

- **请求**：`{ "id": "<handle>", "item": <ItemStack> }`
- **返回**：`true`

### `gui.clear`

清空 GUI。

- **请求**：`{ "id": "<handle>" }`
- **返回**：`true`

### `gui.open`

为玩家打开 GUI。

- **请求**：`{ "id": "<handle>", "uuid": "<uuid>" }`
- **返回**：`true`

### `gui.close`

关闭 GUI（所有正在查看的玩家）。

- **请求**：`{ "id": "<handle>" }`
- **返回**：`true`

### `gui.destroy`

销毁 GUI 并关闭所有查看者。

- **请求**：`{ "id": "<handle>" }`
- **返回**：`true`

### GUI 的资源生命周期

GUI 由插件通过 `gui.create` 创建。插件负责其生命周期，通过 `gui.destroy` 显式销毁，或通过 [gc-collect](../message/lifecycle.md#gc-collect) 自动回收。

### ItemStack 完整格式

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
    "enchantments": { "sharpness": 5, "unbreaking": 3 },
    "itemFlags": ["HIDE_ENCHANTS", "HIDE_ATTRIBUTES"]
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
| `meta.enchantments` | object | 否 | 附魔映射（key → 等级）。key 为 Paper 系附魔命名空间 key（如 `sharpness`） |
| `meta.itemFlags` | string[] | 否 | 物品标志（Paper 系 ItemFlag 枚举名，如 `HIDE_ENCHANTS`） |
