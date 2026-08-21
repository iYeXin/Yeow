# Entity Events

## `entityDamage`

| Field        | Type          | Description |
|--------------|---------------|-------------|
| `entity`     | string (UUID) | The damaged entity |
| `damage`     | number        | The damage amount (**writable**: `mods.damage` overrides the damage amount) |
| `cause`      | string        | The damage cause (`ENTITY_ATTACK`, `FALL`, `LAVA`, etc.) |
| `entityType` | string        | The entity type (Minecraft registry key, e.g. `minecraft:zombie`) |

## `entityDeath`

| Field        | Type          | Description |
|--------------|---------------|-------------|
| `entity`     | string (UUID) | The entity that died |
| `entityType` | string        | The entity type (Minecraft registry key, e.g. `minecraft:zombie`) |
| `entityName` | string        | The entity name |

## `entitySpawn`

| Field        | Type          | Description |
|--------------|---------------|-------------|
| `entity`     | string (UUID) | The spawned entity |
| `entityType` | string        | The entity type (Minecraft registry key, e.g. `minecraft:zombie`) |
| `x`          | number        | X coordinate |
| `y`          | number        | Y coordinate |
| `z`          | number        | Z coordinate |
| `world`      | string        | World name |

## `entityExplode`

| Field        | Type          | Description |
|--------------|---------------|-------------|
| `entity`     | string (UUID) | The exploding entity |
| `entityType` | string        | The entity type (Minecraft registry key, e.g. `minecraft:zombie`) |
| `x`          | number        | X coordinate |
| `y`          | number        | Y coordinate |
| `z`          | number        | Z coordinate |
| `blockCount` | number        | The estimated number of blocks to be destroyed |

## `entityRegainHealth`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `entity` | string (UUID) | The entity that regained health |
| `amount` | number        | The amount of health regained (**writable**: `mods.amount` overrides the regained amount) |
| `reason` | string        | The reason for regaining health (`SATIATED`, `REGEN`, `EATING`, etc.) |

## `entityTarget`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `entity` | string (UUID) | The entity that changed target |
| `target` | string \| null | The target entity UUID (**writable**: `mods.target` is the target UUID or `null` (clears the target); `null` means cancel the target) |

---

## Projectile Events

### `projectileLaunch`

| Field          | Type          | Description |
|----------------|---------------|-------------|
| `entity`       | string (UUID) | The projectile entity |
| `projectileType` | string      | The projectile type (Minecraft registry key, e.g. `minecraft:arrow`, `minecraft:snowball`) |
| `shooter`      | string \| undefined | The shooter's UUID (only present when launched by a living entity) |

### `projectileHit`

| Field          | Type          | Description |
|----------------|---------------|-------------|
| `entity`       | string (UUID) | The projectile entity |
| `projectileType` | string      | The projectile type (Minecraft registry key) |
| `hitEntity`    | string \| null | The UUID of the hit entity |
| `hitBlock`     | object \| null | `{ "x": <int>, "y": <int>, "z": <int>, "type": "<key>" }` |
