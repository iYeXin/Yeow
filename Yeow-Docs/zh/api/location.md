# Location API

三维坐标与朝向。

```js
import { Location } from 'yeow-api';
```

## 构造

```ts
new Location(x, y, z, yaw?, pitch?, world?)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `x` / `y` / `z` | `number` | 坐标 |
| `yaw` | `number` | 水平朝向（度），默认 `0` |
| `pitch` | `number` | 垂直朝向（度），默认 `0` |
| `world` | `string` | 世界名（可选） |

## 方法

```ts
Location.from(raw)              // { x, y, z, yaw, pitch, world } → Location
loc.toObject()                  // Location → { x, y, z, yaw, pitch, world }
```

## 示例

```ts
import { Location, Player } from 'yeow-api';

// 传送到指定坐标
const p = await Player.get('uuid');
await p.teleport(new Location(100, 80, -200, 0, 0, 'world'));
```
