# Particle API

Particle effect spawning.

```js
import { spawnParticle } from 'yeow-api';
```

## Methods

```js
await spawnParticle({
    particle: 'flame',
    world: 'world',
    x: 0, y: 65, z: 0,
    count: 10,
    offsetX: 0.5, offsetY: 0.5, offsetZ: 0.5,
    speed: 0.05,
    force: false,          // whether it is visible from far away
});
```

## ParticleOptions

| Field | Type | Description |
|------|------|------|
| `particle` | string | Particle type: minecraft registry key (e.g. `minecraft:flame`; also accepts the legacy uppercase enum name `FLAME`) |
| `world` | string | World name |
| `x` / `y` / `z` | number | Coordinates |
| `count` | number | Count (defaults to 1) |
| `offsetX` / `offsetY` / `offsetZ` | number | Spread range |
| `speed` | number | Extra speed parameter |
| `force` | boolean | Visible from far away |

> For the valid range of particle type keys (`minecraft:flame`, etc.), see [Values appendix · Version transition domain](../specifications/values.md#iv-version-varying-domains-rules--references).

## Colored particles

```js
// Colored dust particle
await spawnParticle({
    particle: 'minecraft:dust',
    world: 'world', x: 0, y: 65, z: 0,
    count: 20,
    color: { r: 255, g: 100, b: 100, size: 2 },
});
```

## Block/item particles

```js
// Block particle (falling_dust / block_marker)
await spawnParticle({
    particle: 'FALLING_DUST',
    world: 'world', x: 0, y: 65, z: 0,
    blockType: 'minecraft:diamond_block',
});

// Item particle
await spawnParticle({
    particle: 'ITEM',
    world: 'world', x: 0, y: 65, z: 0,
    item: { type: 'minecraft:diamond', amount: 1 },
});
```
