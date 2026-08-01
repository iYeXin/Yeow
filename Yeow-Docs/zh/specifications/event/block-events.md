# Block 事件

## `blockBreak`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 破坏方块的玩家 |
| `block` | string | 被破坏方块的 material key |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |

## `blockPlace`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 放置方块的玩家 |
| `block` | string | 放置方块的 material key |
| `blockAgainst` | string | 所放置面的方块的 material key |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |

## `blockFade`

| 字段 | 类型 | 说明 |
|------|------|------|
| `block` | string | 消退方块的 material key |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |

## `blockGrow`

| 字段 | 类型 | 说明 |
|------|------|------|
| `block` | string | 生长方块的 material key |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |

## `blockSpread`

| 字段 | 类型 | 说明 |
|------|------|------|
| `block` | string | 蔓延方块的 material key |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |

## `blockExplode`

| 字段 | 类型 | 说明 |
|------|------|------|
| `block` | string | 爆炸方块的 material key |
| `x` | number | X 坐标 |
| `y` | number | Y 坐标 |
| `z` | number | Z 坐标 |
