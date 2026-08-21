# Location API

A three-dimensional coordinate and orientation.

```js
import { Location } from 'yeow-api';
```

## Construction

```ts
new Location(x, y, z, yaw?, pitch?, world?)
```

| Parameter | Type | Description |
|------|------|------|
| `x` / `y` / `z` | `number` | Coordinates |
| `yaw` | `number` | Horizontal orientation (degrees), defaults to `0` |
| `pitch` | `number` | Vertical orientation (degrees), defaults to `0` |
| `world` | `string` | World name (optional) |

## Methods

```ts
Location.from(raw)              // { x, y, z, yaw, pitch, world } → Location
loc.toObject()                  // Location → { x, y, z, yaw, pitch, world }
```

## Example

```ts
import { Location, Player } from 'yeow-api';

// Teleport to the given coordinates
const p = await Player.get('uuid');
await p.teleport(new Location(100, 80, -200, 0, 0, 'world'));
```
