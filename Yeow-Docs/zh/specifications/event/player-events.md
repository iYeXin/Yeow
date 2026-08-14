# Player 事件

## `playerJoin`

| 字段          | 类型          | 说明                                       |
| ------------- | ------------- | ------------------------------------------ |
| `player`      | string (UUID) | 加入的玩家                                 |
| `joinMessage` | string        | 加入消息（**可回写**：`mods.joinMessage`） |

## `playerQuit`

| 字段          | 类型          | 说明                                       |
| ------------- | ------------- | ------------------------------------------ |
| `player`      | string (UUID) | 退出的玩家                                 |
| `quitMessage` | string        | 退出消息（**可回写**：`mods.quitMessage`） |

## `playerChat`

| 字段      | 类型          | 说明                                   |
| --------- | ------------- | -------------------------------------- |
| `player`  | string (UUID) | 发送消息的玩家                         |
| `message` | string        | 消息内容（**可回写**：`mods.message`） |
| `format`  | string        | 消息格式（**可回写**：`mods.format`）  |

## `playerMove`

| 字段     | 类型          | 说明                                                                                        |
| -------- | ------------- | ------------------------------------------------------------------------------------------- |
| `player` | string (UUID) | 移动的玩家                                                                                  |
| `from`   | Location      | 移动起始位置（只读）                                                                        |
| `to`     | Location      | 移动目标位置（**可回写**：`mods.to` 为 `{x,y,z,yaw?,pitch?,world?}`，world 缺省用当前世界） |

## `playerInteract`

| 字段       | 类型           | 说明                                                      |
| ---------- | -------------- | --------------------------------------------------------- |
| `player`   | string (UUID)  | 互动的玩家                                                |
| `action`   | string         | 互动类型（`LEFT_CLICK_AIR`、`RIGHT_CLICK_BLOCK` 等）      |
| `material` | string \| null | 手中物品 material key                                     |
| `block`    | object \| null | `{ "x": <int>, "y": <int>, "z": <int>, "type": "<key>" }` |

## `playerCommand`

| 字段      | 类型          | 说明                                                            |
| --------- | ------------- | --------------------------------------------------------------- |
| `player`  | string (UUID) | 执行命令的玩家                                                  |
| `message` | string        | 完整命令字符串（含 `/`）（**可回写**：`mods.message` 改写命令） |

## `playerDeath`

| 字段           | 类型                                                     | 说明                                                                                                                                                                                                             |
| -------------- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `player`       | string (UUID)                                            | 死亡的玩家                                                                                                                                                                                                       |
| `deathMessage` | [Message 对象](../task/player.md#message-对象可翻译组件) | 死亡消息：`{ "key": "<翻译键>", "args": [...], "text": "<纯文本>" }`——`key`/`args` 可翻译组件（原始消息为可翻译组件时）与 `text` 纯文本兜底**同时传递**。**可回写**：`mods.deathMessage` 为 Message 对象或字符串 |
| `deathType`    | string                                                   | 伤害类型 key，例如 `lava`                                                                                                                                                                                        |

> 插件可直接把 `deathMessage` 转发给发送消息 API（`broadcast`、`player.sendMessage` 等）——Message 对象天然兼容。

## `playerRespawn`

| 字段              | 类型          | 说明                                                                           |
| ----------------- | ------------- | ------------------------------------------------------------------------------ |
| `player`          | string (UUID) | 重生的玩家                                                                     |
| `respawnLocation` | Location      | 重生位置（**可回写**：`mods.respawnLocation` 为 `{x,y,z,yaw?,pitch?,world?}`） |

## `playerTeleport`

| 字段     | 类型          | 说明                                                   |
| -------- | ------------- | ------------------------------------------------------ |
| `player` | string (UUID) | 传送的玩家                                             |
| `from`   | Location      | 传送起始位置（只读）                                   |
| `to`     | Location      | 传送目标位置（**可回写**：`mods.to`，同 `playerMove`） |
| `cause`  | string        | 传送原因（`PLUGIN`、`COMMAND`、`SPECTATE` 等）（只读） |

## `playerItemConsume`

| 字段       | 类型          | 说明                    |
| ---------- | ------------- | ----------------------- |
| `player`   | string (UUID) | 消耗物品的玩家          |
| `itemType` | string        | 消耗物品的 material key |

## `playerDropItem`

| 字段       | 类型          | 说明              |
| ---------- | ------------- | ----------------- |
| `player`   | string (UUID) | 丢弃物品的玩家    |
| `itemType` | string        | 物品 material key |
| `amount`   | number        | 数量              |

## `playerPickupItem`

| 字段       | 类型          | 说明              |
| ---------- | ------------- | ----------------- |
| `player`   | string (UUID) | 拾取物品的玩家    |
| `itemType` | string        | 物品 material key |
| `amount`   | number        | 数量              |

## `playerBucketFill`

| 字段     | 类型          | 说明                |
| -------- | ------------- | ------------------- |
| `player` | string (UUID) | 操作的玩家          |
| `bucket` | string        | 桶物品 material key |

## `playerBucketEmpty`

| 字段     | 类型          | 说明                |
| -------- | ------------- | ------------------- |
| `player` | string (UUID) | 操作的玩家          |
| `bucket` | string        | 桶物品 material key |

## `playerExpChange`

| 字段     | 类型          | 说明       |
| -------- | ------------- | ---------- |
| `player` | string (UUID) | 玩家       |
| `amount` | number        | 经验变化量 |

## `playerLevelChange`

| 字段       | 类型          | 说明   |
| ---------- | ------------- | ------ |
| `player`   | string (UUID) | 玩家   |
| `oldLevel` | number        | 旧等级 |
| `newLevel` | number        | 新等级 |

## `playerGameModeChange`

| 字段          | 类型          | 说明                                                       |
| ------------- | ------------- | ---------------------------------------------------------- |
| `player`      | string (UUID) | 玩家                                                       |
| `newGameMode` | string        | 新模式（`CREATIVE`、`SURVIVAL`、`ADVENTURE`、`SPECTATOR`） |

## `playerAdvancementDone`

| 字段          | 类型                                                            | 说明                                                                                                      |
| ------------- | --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `player`      | string (UUID)                                                   | 玩家                                                                                                      |
| `advancement` | string                                                          | 进度命名空间 key（如 `minecraft:story/root`）                                                             |
| `title`       | [Message 对象](../task/player.md#message-对象可翻译组件) (可选) | 进度标题：可翻译组件（`{key, args, text}`，原版进度标题为可翻译组件）或纯文本（`{text}`）；隐藏进度时缺失 |
| `description` | [Message 对象](../task/player.md#message-对象可翻译组件) (可选) | 进度描述（同上）                                                                                          |

## `playerToggleSneak`

| 字段       | 类型          | 说明             |
| ---------- | ------------- | ---------------- |
| `player`   | string (UUID) | 玩家             |
| `sneaking` | boolean       | 是否进入潜行状态 |

## `playerToggleFlight`

| 字段     | 类型          | 说明             |
| -------- | ------------- | ---------------- |
| `player` | string (UUID) | 玩家             |
| `flying` | boolean       | 是否进入飞行状态 |

## `foodLevelChange`

| 字段           | 类型          | 说明                                        |
| -------------- | ------------- | ------------------------------------------- |
| `player`       | string (UUID) | 玩家                                        |
| `oldFoodLevel` | number        | 旧饥饿值                                    |
| `newFoodLevel` | number        | 新饥饿值（**可回写**：`mods.newFoodLevel`） |
