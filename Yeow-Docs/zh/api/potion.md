# Potion API

药水效果操作，作用于 `LivingEntity`（包括 Player）。

```js
import { addPotionEffect, removePotionEffect, clearPotionEffects, getActivePotionEffects } from 'yeow-api';
```

## PotionEffect

```ts
interface PotionEffect {
  type: string;         // 效果类型名（小写，如 "speed"、"poison"）
  duration: number;     // 持续时间（tick，20 = 1秒）
  amplifier: number;    // 等级（0 = I级，1 = II级）
  ambient?: boolean;    // 环境效果（粒子更少），默认 true
  particles?: boolean;  // 显示粒子，默认 true
  icon?: boolean;       // 背包显示图标，默认 true
}
```

## 方法

```js
// 添加药水效果
await addPotionEffect(uuid, {
    type: 'speed', duration: 200, amplifier: 1
});

// 移除指定效果
await removePotionEffect(uuid, 'speed');

// 清除所有效果
await clearPotionEffects(uuid);

// 获取活跃效果列表
const effects = await getActivePotionEffects(uuid);
// → [{ type:"speed", duration:180, amplifier:1, ambient:false, particles:true, icon:true }, ...]
```

## 示例

```js
const player = await Player.get('Notch');
await addPotionEffect(player.uuid, {
    type: 'regeneration', duration: 100, amplifier: 0
});

// 获取并显示所有活跃效果
for (const e of await getActivePotionEffects(player.uuid)) {
    console.log(`${e.type} x${e.amplifier + 1} — ${e.duration} ticks left`);
}
```
