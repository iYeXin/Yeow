# Entity 任务

实体相关操作，包含基础实体、生物（LivingEntity）、药水效果。

---

## 查找

### `entity.get`

按 UUID 查找实体。

- **请求**：`{ "uuid": "<uuid>" }`
- **返回**：`{ "uuid": "<uuid>" }` | `null`

---

## 属性读写

### 基础信息

| 任务                          | 请求                                       | 返回                           |
| ----------------------------- | ------------------------------------------ | ------------------------------ |
| `entity.getType`              | `{ "uuid": "<uuid>" }`                     | `string` (minecraft 注册键，如 `minecraft:zombie`) |
| `entity.getName`              | `{ "uuid": "<uuid>" }`                     | `string`                       |
| `entity.getCustomName`        | `{ "uuid": "<uuid>" }`                     | `string` (空串表示未设置)      |
| `entity.setCustomName`        | `{ "uuid": "<uuid>", "value": "<name>" }`  | `true`                         |
| `entity.setCustomNameVisible` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`                         |

### 位置与世界

| 任务                 | 请求                                                                                                                       | 返回                                                                                                     |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `entity.getWorld`    | `{ "uuid": "<uuid>" }`                                                                                                     | `string` (世界名)                                                                                        |
| `entity.getLocation` | `{ "uuid": "<uuid>" }`                                                                                                     | `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` |
| `entity.teleport`    | `{ "uuid": "<uuid>", "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` | `true`                                                                                                   |

### 状态开关

| 任务                     | 请求                                       | 返回      |
| ------------------------ | ------------------------------------------ | --------- |
| `entity.isGlowing`       | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setGlowing`      | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |
| `entity.isInvulnerable`  | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setInvulnerable` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |
| `entity.isSilent`        | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setSilent`       | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |
| `entity.hasGravity`      | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setGravity`      | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |

### 骑乘系统

| 任务                    | 请求                   | 返回                                                     |
| ----------------------- | ---------------------- | -------------------------------------------------------- |
| `entity.getPassengers`  | `{ "uuid": "<uuid>" }` | `string[]` (UUID 数组)                                   |
| `entity.getVehicle`     | `{ "uuid": "<uuid>" }` | `string` \| `null` (骑乘的实体 UUID)                     |
| `entity.getBoundingBox` | `{ "uuid": "<uuid>" }` | `{ "minX","minY","minZ","maxX","maxY","maxZ" }` (double) |

### 生命操作

| 任务            | 请求                   | 返回      | 说明           |
| --------------- | ---------------------- | --------- | -------------- |
| `entity.remove` | `{ "uuid": "<uuid>" }` | `true`    | 移除实体       |
| `entity.isDead` | `{ "uuid": "<uuid>" }` | `boolean` | 实体是否已死亡 |

---

## 生物（LivingEntity）— 扩展基础实体

### 生命值

| 任务                  | 请求                                      | 返回     |
| --------------------- | ----------------------------------------- | -------- |
| `entity.getHealth`    | `{ "uuid": "<uuid>" }`                    | `number` |
| `entity.setHealth`    | `{ "uuid": "<uuid>", "value": <double> }` | `true`   |
| `entity.getMaxHealth` | `{ "uuid": "<uuid>" }`                    | `number` |

---

## 药水效果

所有药水效果操作目标为 `LivingEntity`（生物）。

### 参数格式

```json
{
  "type": "minecraft:speed",
  "duration": 200,
  "amplifier": 1,
  "ambient": false,
  "particles": true,
  "icon": true
}
```

| 字段        | 类型   | 必填 | 默认 | 说明                                                                         |
| ----------- | ------ | ---- | ---- | ---------------------------------------------------------------------------- |
| `type`      | string | 是   | —    | 药水效果类型名（Paper 系 PotionEffectType 名称，小写，如 `speed`、`poison`） |
| `duration`  | int    | 否   | 200  | 持续时间（tick，20 = 1 秒）                                                  |
| `amplifier` | int    | 否   | 0    | 等级（0 = I 级，1 = II 级）                                                  |
| `ambient`   | bool   | 否   | true | 是否为环境效果（粒子更少）                                                   |
| `particles` | bool   | 否   | true | 是否显示粒子                                                                 |
| `icon`      | bool   | 否   | true | 是否在背包显示图标                                                           |

### 任务

| 任务                            | 请求                                         | 返回                                             |
| ------------------------------- | -------------------------------------------- | ------------------------------------------------ |
| `entity.addPotionEffect`        | `{ "uuid": "<uuid>", ...PotionEffect 参数 }` | `true`。`type` 大小写不敏感；`ambient`/`particles`/`icon` 缺省 `true`（与输入结构一致）；未知 `type` 返回错误 |
| `entity.removePotionEffect`     | `{ "uuid": "<uuid>", "type": "<potion>" }`   | `true`                                           |
| `entity.clearPotionEffects`     | `{ "uuid": "<uuid>" }`                       | `true`                                           |
| `entity.getActivePotionEffects` | `{ "uuid": "<uuid>" }`                       | `[{ "type": "<小写>", "duration": <int>, "amplifier": <int>, "ambient": <bool>, "particles": <bool>, "icon": <bool> }]` — 与输入相同结构 |

---

## 基础操作（2026-08-13）

| 任务                   | 请求                                                                                                                                                         | 返回                                                         |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------ |
| `entity.getVelocity`   | `{ "uuid": "<uuid>" }`                                                                                                                                       | `{ "x": <double>, "y": <double>, "z": <double> }`（方块/秒） |
| `entity.setVelocity`   | `{ "uuid": "<uuid>", "x": <double>, "y": <double>, "z": <double> }`                                                                                          | `true`                                                       |
| `entity.getFireTicks`  | `{ "uuid": "<uuid>" }`                                                                                                                                       | `int`（0 = 未着火）                                          |
| `entity.setFireTicks`  | `{ "uuid": "<uuid>", "value": <int> }`                                                                                                                       | `true`                                                       |
| `entity.getTicksLived` | `{ "uuid": "<uuid>" }`                                                                                                                                       | `int`（已存活刻数）                                          |
| `entity.setTicksLived` | `{ "uuid": "<uuid>", "value": <int> }`                                                                                                                       | `true`                                                       |
| `entity.isOnGround`    | `{ "uuid": "<uuid>" }`                                                                                                                                       | `boolean`                                                    |
| `entity.damage`        | `{ "uuid": "<uuid>", "amount": <double>, "damager": "<uuid>"? }`                                                                                             | `true`（damager 可选伤害来源实体）                           |
| `entity.setTarget`     | `{ "uuid": "<uuid>", "targetUuid": "<uuid>" }` 或 `{ "uuid": "<uuid>", "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "speed": <double>? }` | `true`                                                       |

> **setTarget 语义**（2026-08-13）：设置 AI 目标——**不保证必然生效**（取决于运行时、实体类型和寻路能力）。
> 目标为实体（`targetUuid`，`Mob.setTarget`）或位置（`world`+`x`+`y`+`z`，`Pathfinder.moveTo`，可带 `speed` 移动速度）。
> 需要生物实体（Mob）才生效；非生物/无法寻路的实体静默忽略。

> 涉及值域（实体类型、药水效果等）的格式规则与清单见 [值域附录](../values.md)。
