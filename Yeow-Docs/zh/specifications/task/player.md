# Player 任务

玩家相关操作。所有任务通过 `task` 通道发送。

---

## 查找

### `player.get`

按 UUID 或玩家名查找在线玩家。

- **请求**：`{ "identifier": "<uuid|name>" }`
- **返回**：`{ "uuid": "<uuid>", "name": "<name>" }` | `null`

### `player.getAll`

获取所有在线玩家。

- **请求**：`{}`
- **返回**：`[{ "uuid": "<uuid>", "name": "<name>" }, ...]`

---

## 属性读写

以下任务字段中 `uuid` 为玩家 UUID 字符串。

### 基础属性

| 任务 | 请求 | 返回 |
|------|------|------|
| `player.getPing` | `{ "uuid": "<uuid>" }` | `number` (ms) |
| `player.getGamemode` | `{ "uuid": "<uuid>" }` | `"creative" \| "survival" \| "adventure" \| "spectator"` |
| `player.setGamemode` | `{ "uuid": "<uuid>", "value": "<gamemode>" }` | `true` |
| `player.getHealth` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setHealth` | `{ "uuid": "<uuid>", "value": <double> }` | `true` |
| `player.getFood` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setFood` | `{ "uuid": "<uuid>", "value": <int> }` | `true` |
| `player.getExp` | `{ "uuid": "<uuid>" }` | `number` (0.0-1.0) |
| `player.setExp` | `{ "uuid": "<uuid>", "value": <float> }` | `true` |
| `player.getLevel` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setLevel` | `{ "uuid": "<uuid>", "value": <int> }` | `true` |
| `player.getSaturation` | `{ "uuid": "<uuid>" }` | `number` |
| `player.getTotalExperience` | `{ "uuid": "<uuid>" }` | `number` |

### 布尔属性

| 任务 | 请求 | 返回 |
|------|------|------|
| `player.isOp` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.isOnline` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.isFlying` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.setFlying` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true` |
| `player.isSneaking` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.isSprinting` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.getBedLocation` | `{ "uuid": "<uuid>" }` | `{ "x","y","z","yaw","pitch","world" }` \| `null` |
| `player.getAllowFlight` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.setAllowFlight` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true` |

### 速度属性

| 任务 | 请求 | 返回 |
|------|------|------|
| `player.getWalkSpeed` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setWalkSpeed` | `{ "uuid": "<uuid>", "value": <float> }` | `true` |
| `player.getFlySpeed` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setFlySpeed` | `{ "uuid": "<uuid>", "value": <float> }` | `true` |

### 位置与世界

| 任务 | 请求 | 返回 |
|------|------|------|
| `player.getWorld` | `{ "uuid": "<uuid>" }` | `string` (世界名) |
| `player.getLocation` | `{ "uuid": "<uuid>" }` | `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` |
| `player.teleport` | `{ "uuid": "<uuid>", "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` | `true` |
| `player.getDisplayName` | `{ "uuid": "<uuid>" }` | `string` |
| `player.setDisplayName` | `{ "uuid": "<uuid>", "value": "<name>" }` | `true` |

---

## 交互

### 消息与通知

| 任务 | 请求 | 返回 | 说明 |
|------|------|------|------|
| `player.sendMessage` | `{ "uuid": "<uuid>", "message": <Message> }` | `true` | 发送消息（`message` 为 [Message 对象](#message-对象可翻译组件) 或纯文本） |
| `player.sendActionBar` | `{ "uuid": "<uuid>", "message": <Message> }` | `true` | 发送操作栏消息（同上） |
| `player.sendTitle` | `{ "uuid": "<uuid>", "title": "<text>", "subtitle": "<text>", "fadeIn": <int>, "stay": <int>, "fadeOut": <int> }` | `true` | 发送标题/副标题。`fadeIn`/`stay`/`fadeOut` 单位为 tick（默认 10/70/20） |
| `player.playSound` | `{ "uuid": "<uuid>", "sound": "<key>", "volume": <float>, "pitch": <float> }` | `true` | 播放音效（sound 为 Paper 系 Sound 枚举名，如 `block.note_block.pling`） |
| `player.stopSound` | `{ "uuid": "<uuid>", "sound": "<key>" }` | `true` | 停止指定音效 |
| `player.stopAllSounds` | `{ "uuid": "<uuid>" }` | `true` | 停止所有音效 |
| `player.kick` | `{ "uuid": "<uuid>", "reason": "<text>" }` | `true` | 踢出玩家 |
| `player.giveExp` | `{ "uuid": "<uuid>", "amount": <int> }` | `true` | 给予经验值 |
| `player.hasPermission` | `{ "uuid": "<uuid>", "permission": "<node>" }` | `boolean` | 检查权限 |
| `player.performCommand` | `{ "uuid": "<uuid>", "command": "<cmd>" }` | `boolean` | 以玩家身份执行命令（**不含 `/` 前缀**，如 `say hi`；与服务器 `command.dispatch`（控制台）相对） |

### Message 对象（可翻译组件）

涉及文本的载荷（如 `message` 字段）接受 **Message 对象** 或纯字符串：

```json
// 可翻译组件 + 纯文本兜底（key 与 text 可同时存在）
{ "key": "death.attack.player", "args": ["Steve", "Zombie"], "text": "§cSteve 被 Zombie 杀死了" }
// 纯文本（MiniMessage/legacy 解析）
{ "text": "<red>你死了</red>" }
// 纯字符串等价于 { "text": "<string>" }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `key` | string | Minecraft 翻译键（如 `death.attack.player`）；存在时构造可翻译组件 |
| `args` | array | 翻译参数：string / number / 嵌套 Message（可选） |
| `text` | string | 纯文本兜底（`key` 缺失时使用；与 `key` 同时存在时二者都传递） |

**实现建议**：所有实现至少应支持 `text` 字段（纯文本）；`key`/`args` 为可翻译组件支持。`key` 与 `text` 同时存在时**两者都传递**（`key` 用于本地化，`text` 为跨实现转发兜底）。

### 资源包

| 任务 | 请求 | 返回 | 说明 |
|------|------|------|------|
| `player.sendResourcePack` | `{ "uuid": "<uuid>", "url": "<url>", "hash": "<sha1>", "prompt": <Message>, "force": <boolean> }` | `true` | 提示下载资源包。`hash` 为 SHA-1 十六进制字符串；`prompt` 为 [Message 对象](#message-对象可翻译组件) 或纯文本 |

### 手持物品

| 任务 | 请求 | 返回 | 说明 |
|------|------|------|------|
| `player.getItemInMainHand` | `{ "uuid": "<uuid>" }` | `ItemStack` \| `null` | 读取主手物品，空手返回 `null` |
| `player.getItemInOffHand` | `{ "uuid": "<uuid>" }` | `ItemStack` \| `null` | 读取副手物品，空手返回 `null` |

`ItemStack` 返回格式（**纯数据**，值为读取时刻的快照）：

```json
{
  "type": "minecraft:diamond_sword",
  "amount": 1,
  "meta": {
    "displayName": "Sharp Sword",
    "lore": ["A very sharp sword"],
    "customModelData": 100,
    "unbreakable": true,
    "enchantments": { "minecraft:sharpness": 5 }
  }
}
```

---

## 文本格式约定

所有可接收文本的 `message`、`title`、`subtitle`、`displayName`、`prompt`、`reason` 字段支持：

- [MiniMessage](https://docs.advntr.dev/minimessage/format) 格式（以 `<` 开头）
- 传统 `§` 段分符格式（向后兼容）
