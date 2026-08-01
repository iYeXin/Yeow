# Particle API

粒子效果生成。

```js
import { spawnParticle } from 'yeow-api';
```

## 方法

```js
await spawnParticle({
    particle: 'flame',
    world: 'world',
    x: 0, y: 65, z: 0,
    count: 10,
    offsetX: 0.5, offsetY: 0.5, offsetZ: 0.5,
    speed: 0.05,
    force: false,          // 远距离是否可见
});
```

## ParticleOptions

| 字段 | 类型 | 说明 |
|------|------|------|
| `particle` | string | 粒子类型名（大写 Bukkit Particle 枚举名，如 `FLAME`） |
| `world` | string | 世界名 |
| `x` / `y` / `z` | number | 坐标 |
| `count` | number | 数量（默认 1） |
| `offsetX` / `offsetY` / `offsetZ` | number | 扩散范围 |
| `speed` | number | 额外速度参数 |
| `force` | boolean | 远距离可见 |

## 染色粒子

```js
// 彩色 dust 粒子
await spawnParticle({
    particle: 'DUST',
    world: 'world', x: 0, y: 65, z: 0,
    count: 20,
    color: { r: 255, g: 100, b: 100, size: 2 },
});
```

## 方块/物品粒子

```js
// 方块粒子（falling_dust / block_marker）
await spawnParticle({
    particle: 'FALLING_DUST',
    world: 'world', x: 0, y: 65, z: 0,
    blockType: 'minecraft:diamond_block',
});

// 物品粒子
await spawnParticle({
    particle: 'ITEM',
    world: 'world', x: 0, y: 65, z: 0,
    item: { type: 'minecraft:diamond', amount: 1 },
});
```
