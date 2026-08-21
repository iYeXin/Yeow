# Entity Tasks

Entity-related operations, including base entities, living entities (LivingEntity), and potion effects.

---

## Lookup

### `entity.get`

Looks up an entity by UUID.

- **Request**: `{ "uuid": "<uuid>" }`
- **Return**: `{ "uuid": "<uuid>" }` | `null`

---

## Attribute Read/Write

### Basic Information

| Task                          | Request                                       | Return                           |
| ----------------------------- | ------------------------------------------ | ------------------------------ |
| `entity.getType`              | `{ "uuid": "<uuid>" }`                     | `string` (minecraft registry key, e.g. `minecraft:zombie`) |
| `entity.getName`              | `{ "uuid": "<uuid>" }`                     | `string`                       |
| `entity.getCustomName`        | `{ "uuid": "<uuid>" }`                     | `string` (empty string means not set)      |
| `entity.setCustomName`        | `{ "uuid": "<uuid>", "value": "<name>" }`  | `true`                         |
| `entity.setCustomNameVisible` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`                         |

### Location and World

| Task                 | Request                                                                                                                       | Return                                                                                                     |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `entity.getWorld`    | `{ "uuid": "<uuid>" }`                                                                                                     | `string` (world name)                                                                                        |
| `entity.getLocation` | `{ "uuid": "<uuid>" }`                                                                                                     | `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` |
| `entity.teleport`    | `{ "uuid": "<uuid>", "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` | `true`                                                                                                   |

### State Toggles

| Task                     | Request                                       | Return      |
| ------------------------ | ------------------------------------------ | --------- |
| `entity.isGlowing`       | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setGlowing`      | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |
| `entity.isInvulnerable`  | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setInvulnerable` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |
| `entity.isSilent`        | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setSilent`       | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |
| `entity.hasGravity`      | `{ "uuid": "<uuid>" }`                     | `boolean` |
| `entity.setGravity`      | `{ "uuid": "<uuid>", "value": <boolean> }` | `true`    |

### Riding System

| Task                    | Request                   | Return                                                     |
| ----------------------- | ---------------------- | -------------------------------------------------------- |
| `entity.getPassengers`  | `{ "uuid": "<uuid>" }` | `string[]` (UUID array)                                   |
| `entity.getVehicle`     | `{ "uuid": "<uuid>" }` | `string` \| `null` (UUID of the ridden entity)                     |
| `entity.getBoundingBox` | `{ "uuid": "<uuid>" }` | `{ "minX","minY","minZ","maxX","maxY","maxZ" }` (double) |

### Lifecycle Operations

| Task            | Request                   | Return      | Description           |
| --------------- | ---------------------- | --------- | -------------- |
| `entity.remove` | `{ "uuid": "<uuid>" }` | `true`    | Removes the entity       |
| `entity.isDead` | `{ "uuid": "<uuid>" }` | `boolean` | Whether the entity is dead |

---

## LivingEntity — extends the base entity

### Health

| Task                  | Request                                      | Return     |
| --------------------- | ----------------------------------------- | -------- |
| `entity.getHealth`    | `{ "uuid": "<uuid>" }`                    | `number` |
| `entity.setHealth`    | `{ "uuid": "<uuid>", "value": <double> }` | `true`   |
| `entity.getMaxHealth` | `{ "uuid": "<uuid>" }`                    | `number` |

---

## Potion Effects

All potion effect operations target `LivingEntity`.

### Parameter Format

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

| Field        | Type   | Required | Default | Description                                                                         |
| ----------- | ------ | ---- | ---- | ---------------------------------------------------------------------------- |
| `type`      | string | Yes   | —    | Potion effect type name (Paper PotionEffectType name, lowercase, e.g. `speed`, `poison`) |
| `duration`  | int    | No   | 200  | Duration (in ticks, 20 = 1 second)                                                  |
| `amplifier` | int    | No   | 0    | Level (0 = level I, 1 = level II)                                                  |
| `ambient`   | bool   | No   | true | Whether it is an ambient effect (fewer particles)                                                   |
| `particles` | bool   | No   | true | Whether to show particles                                                                 |
| `icon`      | bool   | No   | true | Whether to show the icon in the inventory                                                           |

### Tasks

| Task                            | Request                                         | Return                                             |
| ------------------------------- | -------------------------------------------- | ------------------------------------------------ |
| `entity.addPotionEffect`        | `{ "uuid": "<uuid>", ...PotionEffect params }` | `true`. `type` is case-insensitive; `ambient`/`particles`/`icon` default to `true` (same structure as the input); an unknown `type` returns an error |
| `entity.removePotionEffect`     | `{ "uuid": "<uuid>", "type": "<potion>" }`   | `true`                                           |
| `entity.clearPotionEffects`     | `{ "uuid": "<uuid>" }`                       | `true`                                           |
| `entity.getActivePotionEffects` | `{ "uuid": "<uuid>" }`                       | `[{ "type": "<lowercase>", "duration": <int>, "amplifier": <int>, "ambient": <bool>, "particles": <bool>, "icon": <bool> }]` — same structure as the input |

---

## Basic Operations (2026-08-13)

| Task                   | Request                                                                                                                                                         | Return                                                         |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------ |
| `entity.getVelocity`   | `{ "uuid": "<uuid>" }`                                                                                                                                       | `{ "x": <double>, "y": <double>, "z": <double> }` (blocks/second) |
| `entity.setVelocity`   | `{ "uuid": "<uuid>", "x": <double>, "y": <double>, "z": <double> }`                                                                                          | `true`                                                       |
| `entity.getFireTicks`  | `{ "uuid": "<uuid>" }`                                                                                                                                       | `int` (0 = not on fire)                                          |
| `entity.setFireTicks`  | `{ "uuid": "<uuid>", "value": <int> }`                                                                                                                       | `true`                                                       |
| `entity.getTicksLived` | `{ "uuid": "<uuid>" }`                                                                                                                                       | `int` (ticks lived)                                          |
| `entity.setTicksLived` | `{ "uuid": "<uuid>", "value": <int> }`                                                                                                                       | `true`                                                       |
| `entity.isOnGround`    | `{ "uuid": "<uuid>" }`                                                                                                                                       | `boolean`                                                    |
| `entity.damage`        | `{ "uuid": "<uuid>", "amount": <double>, "damager": "<uuid>"? }`                                                                                             | `true` (damager is the optional damaging entity)                           |
| `entity.setTarget`     | `{ "uuid": "<uuid>", "targetUuid": "<uuid>" }` or `{ "uuid": "<uuid>", "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "speed": <double>? }` | `true`                                                       |

> **setTarget semantics** (2026-08-13): sets an AI goal — **not guaranteed to take effect** (it depends on the runtime, entity type, and pathfinding capability).
> The target is an entity (`targetUuid`, `Mob.setTarget`) or a location (`world`+`x`+`y`+`z`, `Pathfinder.moveTo`, optionally with a `speed` movement speed).
> Only works on mob entities; non-mob/entities that cannot pathfind are silently ignored.

> For the format rules and full lists of value ranges involved (entity types, potion effects, etc.), see the [Values Appendix](../values.md).
