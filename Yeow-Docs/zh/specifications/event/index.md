# Event 事件规范

## 概述

Yeow 事件系统是运行时向插件投递游戏事件的机制。插件通过 `event.subscribe` 订阅事件，运行时通过 `cb` 通道投递事件数据。

---

## 注册与取消

插件通过 task 通道的 `event.subscribe` 和 `event.unsubscribe` 进行事件订阅管理。详见 [Server & Event 任务规范](../task/server.md)。

## 字段适配规则

事件数据中：

- `player` 字段值为玩家 UUID 字符串，调用方可按 UUID 查找 Player
- `from`、`to`、`respawnLocation` 字段值为 `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` 的位置对象
- 事件以 `_cancellable` 字段标记是否可被取消

---

## 事件名称表

所有事件名使用 `camelCase` 格式。完整列表：

| 事件名                     | 类别   | 可取消 | 说明             |
| -------------------------- | ------ | ------ | ---------------- |
| `playerJoin`               | 玩家   | 否     | 玩家加入         |
| `playerQuit`               | 玩家   | 否     | 玩家退出         |
| `playerChat`               | 玩家   | 是     | 玩家聊天         |
| `playerMove`               | 玩家   | 是     | 玩家移动         |
| `playerInteract`           | 玩家   | 是     | 玩家互动         |
| `playerCommand`            | 玩家   | 是     | 玩家执行命令     |
| `playerDeath`              | 玩家   | 否     | 玩家死亡         |
| `playerRespawn`            | 玩家   | 否     | 玩家重生         |
| `playerTeleport`           | 玩家   | 是     | 玩家传送         |
| `playerItemConsume`        | 玩家   | 是     | 玩家消耗物品     |
| `playerDropItem`           | 玩家   | 是     | 玩家丢弃物品     |
| `playerPickupItem`         | 玩家   | 是     | 玩家拾取物品     |
| `playerBucketFill`         | 玩家   | 是     | 玩家用桶取液体   |
| `playerBucketEmpty`        | 玩家   | 是     | 玩家倒出桶中液体 |
| `playerExpChange`          | 玩家   | 否     | 玩家经验值变化   |
| `playerLevelChange`        | 玩家   | 否     | 玩家等级变化     |
| `playerGameModeChange`     | 玩家   | 否     | 玩家游戏模式变化 |
| `playerAdvancementDone`    | 玩家   | 否     | 玩家完成进度     |
| `playerToggleSneak`        | 玩家   | 否     | 玩家切换潜行     |
| `playerToggleFlight`       | 玩家   | 否     | 玩家切换飞行     |
| `foodLevelChange`          | 玩家   | 是     | 玩家饥饿值变化   |
| `entityDamage`             | 实体   | 是     | 实体受伤         |
| `entityDeath`              | 实体   | 否     | 实体死亡         |
| `entitySpawn`              | 实体   | 否     | 实体生成         |
| `entityExplode`            | 实体   | 是     | 实体爆炸         |
| `entityRegainHealth`       | 实体   | 否     | 实体回血         |
| `entityTarget`             | 实体   | 否     | 实体切换目标     |
| `projectileLaunch`         | 实体   | 否     | 弹射物发射       |
| `projectileHit`            | 实体   | 否     | 弹射物命中       |
| `blockBreak`               | 方块   | 是     | 方块破坏         |
| `blockPlace`               | 方块   | 是     | 方块放置         |
| `blockFade`                | 方块   | 是     | 方块消退         |
| `blockGrow`                | 方块   | 否     | 方块生长         |
| `blockSpread`              | 方块   | 否     | 方块蔓延         |
| `blockExplode`             | 方块   | 是     | 方块爆炸         |
| `inventoryOpen`            | 库存   | 是     | 打开库存         |
| `inventoryClose`           | 库存   | 否     | 关闭库存         |
| `inventoryClick`           | 库存   | 是     | 点击库存         |
| `serverPing`               | 服务器 | 否     | 服务器被 ping    |
| `serverCommand`            | 服务器 | 是     | 控制台命令       |
| `playerResourcePackStatus` | 资源包 | 否     | 资源包状态变化   |
| `permissionCheck`          | 权限   | 否     | Yeow 生态权限检查（非 Bukkit 事件，见下） |

## `permissionCheck`（Yeow 生态权限检查）

**Yeow 插件**通过 handler 返回 `{ "allowed": <bool> }` 拦截权限检查；**不返回视为未处理**。多个 handler 返回冲突时以**最后一个返回的为准**（不保证执行顺序）。

- **触发范围（仅限 Yeow 生态）**：
  - `player.hasPermission` 任务
  - Yeow 插件注册命令的**执行时检查**
  - **其他 Java 插件的 `hasPermission` / 命令执行不会触发**——本检查不捆绑其他生态
- **优先级**：`permissionCheck` 有结果时**覆盖 Bukkit `hasPermission`**；无处理时回退 Bukkit
- **节点融合**：权限节点仍同时注册进 Bukkit 权限系统（传统 Java 插件 / 权限插件可管理），只是 Yeow 检查优先级更高

| 字段 | 类型 | 说明 |
|------|------|------|
| `target` | string | 检查对象：玩家 UUID 或 `"CONSOLE"` |
| `node` | string | 权限节点（如 `myplugin.home`） |

示例：

```json
{ "t": "cb", "p": "<cbId>", "r": { "target": "<uuid>", "node": "myplugin.home" } }
// handler 返回：{ "allowed": true } 或 { "allowed": false }；或不返回（未处理）
```

---

## 模块文档

| 模块                                    | 说明                          |
| --------------------------------------- | ----------------------------- |
| [player-events](player-events.md)       | 21 个玩家事件                 |
| [entity-events](entity-events.md)       | 6 个实体事件 + 2 个弹射物事件 |
| [block-events](block-events.md)         | 6 个方块事件                  |
| [inventory-events](inventory-events.md) | 3 个库存事件                  |
| [server-events](server-events.md)       | 3 个服务器事件                |
