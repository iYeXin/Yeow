# Potion API

Potion effect operations. Apply to **`LivingEntity`** (including Player — Player inherits LivingEntity, so it works directly).

```js
const entity = await LivingEntity.get(uuid);   // or grab an existing player / entity object directly
await entity.addPotionEffect({ type: 'minecraft:speed', duration: 200, amplifier: 1 });
```

## PotionEffect

```ts
interface PotionEffect {
  type: string;         // effect type: minecraft registry key (e.g. "minecraft:speed", "minecraft:poison"; input also accepts legacy enum names like "speed"/"SPEED")
  duration: number;     // duration (ticks, 20 = 1 second)
  amplifier: number;    // level (0 = level I, 1 = level II)
  ambient?: boolean;    // ambient effect (fewer particles), default true
  particles?: boolean;  // show particles, default true
  icon?: boolean;       // show an icon in the inventory, default true
}
```

> The valid values for potion effect keys (such as `minecraft:speed`) are described in the [Values Appendix · Version-Changing Domains](../specifications/values.md#四版本变迁域规则--引用).

## Methods (LivingEntity / Player instance methods)

```js
// Add a potion effect (type is a minecraft registry key)
await entity.addPotionEffect({ type: 'minecraft:speed', duration: 200, amplifier: 1 });
await entity.addPotionEffectSync({ type: 'minecraft:speed', duration: 200, amplifier: 1 });

// Remove a specific effect
await entity.removePotionEffect('minecraft:speed');

// Clear all effects
await entity.clearPotionEffects();

// Get the list of active effects (type is always a registry key)
const effects = await entity.getActivePotionEffects();
// → [{ type:"minecraft:speed", duration:180, amplifier:1, ambient:false, particles:true, icon:true }, ...]
```

## Example

```js
const player = await Player.get('Notch');
await player.addPotionEffect({ type: 'minecraft:regeneration', duration: 100, amplifier: 0 });

// Get and print all active effects
for (const e of await player.getActivePotionEffects()) {
    console.log(`${e.type} x${e.amplifier + 1} — ${e.duration} ticks left`);
}
```
