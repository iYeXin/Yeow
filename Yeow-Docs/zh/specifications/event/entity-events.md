# Entity 事件

## `entityDamage`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 受伤的实体 |
| `damage` | number | 伤害值（**可回写**：`mods.damage` 覆盖伤害值） |
| `cause` | string | 伤害类型（`ENTITY_ATTACK`、`FALL`、`LAVA` 等） |
| `entityType` | string | 实体类型枚举名 |

## `entityDeath`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 死亡的实体 |
| `entityType` | string | 实体类型枚举名 |
| `entityName` | string | 实体名称 |

## `entitySpawn`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 生成的实体 |
| `entityType` | string | 实体类型枚举名 |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |
| `world` | string | 世界名 |

## `entityExplode`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 爆炸的实体 |
| `entityType` | string | 实体类型枚举名 |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |
| `blockCount` | number | 预计摧毁方块数 |

## `entityRegainHealth`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 回血的实体 |
| `amount` | number | 回血量（**可回写**：`mods.amount` 覆盖回血量） |
| `reason` | string | 回血原因（`SATIATED`、`REGEN`、`EATING` 等） |

## `entityTarget`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 切换目标的实体 |
| `target` | string \| null | 目标实体 UUID（**可回写**：`mods.target` 为目标 UUID 或 `null`（清除目标）；`null` 表示取消目标） |

---

## Projectile 事件

### `projectileLaunch`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 弹射物实体 |
| `projectileType` | string | 弹射物类型（`ARROW`、`SNOWBALL` 等） |
| `shooter` | string \| undefined | 发射者 UUID（仅生物发射时存在） |

### `projectileHit`

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity` | string (UUID) | 弹射物实体 |
| `projectileType` | string | 弹射物类型 |
| `hitEntity` | string \| null | 命中的实体 UUID |
| `hitBlock` | object \| null | `{ "x": <int>, "y": <int>, "z": <int>, "type": "<key>" }` |
