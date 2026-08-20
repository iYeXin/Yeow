# Potion API

药水效果操作。作用于 **`LivingEntity`**（含 Player——Player 继承 LivingEntity，直接可用）。

```js
const entity = await LivingEntity.get(uuid);   // 或直接拿已有的 player / entity 对象
await entity.addPotionEffect({ type: 'minecraft:speed', duration: 200, amplifier: 1 });
```

## PotionEffect

```ts
interface PotionEffect {
  type: string;         // 效果类型：minecraft 注册键（如 "minecraft:speed"、"minecraft:poison"；入参兼容旧式枚举名 "speed"/"SPEED"）
  duration: number;     // 持续时间（tick，20 = 1秒）
  amplifier: number;    // 等级（0 = I级，1 = II级）
  ambient?: boolean;    // 环境效果（粒子更少），默认 true
  particles?: boolean;  // 显示粒子，默认 true
  icon?: boolean;       // 背包显示图标，默认 true
}
```

> 药水效果键（`minecraft:speed` 等）的取值域见 [值域附录 · 版本变迁域](../specifications/values.md#四版本变迁域规则--引用)。

## 方法（LivingEntity / Player 实例方法）

```js
// 添加药水效果（type 为 minecraft 注册键）
await entity.addPotionEffect({ type: 'minecraft:speed', duration: 200, amplifier: 1 });
await entity.addPotionEffectSync({ type: 'minecraft:speed', duration: 200, amplifier: 1 });

// 移除指定效果
await entity.removePotionEffect('minecraft:speed');

// 清除所有效果
await entity.clearPotionEffects();

// 获取活跃效果列表（type 一律为注册键）
const effects = await entity.getActivePotionEffects();
// → [{ type:"minecraft:speed", duration:180, amplifier:1, ambient:false, particles:true, icon:true }, ...]
```

## 示例

```js
const player = await Player.get('Notch');
await player.addPotionEffect({ type: 'minecraft:regeneration', duration: 100, amplifier: 0 });

// 获取并显示所有活跃效果
for (const e of await player.getActivePotionEffects()) {
    console.log(`${e.type} x${e.amplifier + 1} — ${e.duration} ticks left`);
}
```
