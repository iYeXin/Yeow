# Player 事件

## `playerJoin`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 加入的玩家 |
| `joinMessage` | string | 加入消息 |

## `playerQuit`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 退出的玩家 |
| `quitMessage` | string | 退出消息 |

## `playerChat`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 发送消息的玩家 |
| `message` | string | 消息内容 |
| `format` | string | 消息格式 |

## `playerMove`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 移动的玩家 |
| `from` | Location | 移动起始位置 |
| `to` | Location | 移动目标位置 |

## `playerInteract`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 互动的玩家 |
| `action` | string | 互动类型（`LEFT_CLICK_AIR`、`RIGHT_CLICK_BLOCK` 等） |
| `material` | string \| null | 手中物品 material key |
| `block` | object \| null | `{ "x": <int>, "y": <int>, "z": <int>, "type": "<key>" }` |

## `playerCommand`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 执行命令的玩家 |
| `message` | string | 完整命令字符串（含 `/`） |

## `playerDeath`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 死亡的玩家 |
| `deathMessage` | string | 死亡消息 |
| `deathType` | string | 伤害类型 key |

## `playerRespawn`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 重生的玩家 |
| `respawnLocation` | Location | 重生位置 |

## `playerTeleport`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 传送的玩家 |
| `from` | Location | 传送起始位置 |
| `to` | Location | 传送目标位置 |
| `cause` | string | 传送原因（`PLUGIN`、`COMMAND`、`SPECTATE` 等） |

## `playerItemConsume`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 消耗物品的玩家 |
| `itemType` | string | 消耗物品的 material key |

## `playerDropItem`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 丢弃物品的玩家 |
| `itemType` | string | 物品 material key |
| `amount` | number | 数量 |

## `playerPickupItem`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 拾取物品的玩家 |
| `itemType` | string | 物品 material key |
| `amount` | number | 数量 |

## `playerBucketFill`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 操作的玩家 |
| `bucket` | string | 桶物品 material key |

## `playerBucketEmpty`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 操作的玩家 |
| `bucket` | string | 桶物品 material key |

## `playerExpChange`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 玩家 |
| `amount` | number | 经验变化量 |

## `playerLevelChange`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 玩家 |
| `oldLevel` | number | 旧等级 |
| `newLevel` | number | 新等级 |

## `playerGameModeChange`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 玩家 |
| `newGameMode` | string | 新模式（`CREATIVE`、`SURVIVAL`、`ADVENTURE`、`SPECTATOR`） |

## `foodLevelChange`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 玩家 |
| `oldFoodLevel` | number | 旧饥饿值 |
| `newFoodLevel` | number | 新饥饿值 |
