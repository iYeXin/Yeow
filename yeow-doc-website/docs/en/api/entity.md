# Entity API

```js
import { Entity, LivingEntity } from 'yeow-api';
```

## Static Methods

```js
Entity.get(uuid)                // Promise<Entity | null>
Entity.getSync(uuid)            // Entity | null
```

## Constructor

```js
new Entity(uuid)                // Reference entity by UUID
```

## Properties

| Property | Type | Read/Write | Description |
| -------- | ---- | :--------: | ----------- |
| `uuid` | `string` | Read-only | Entity UUID |
| `type` | `string` | Read-only | Type (minecraft registration key, e.g., `minecraft:zombie`) |
| `name` | `string` | Read-only | Display name |
| `customName` | `string \| null` | Read/Write | Custom name |
| `world` | `string \| null` | Read-only | Current world |
| `location` | `Location \| null` | Read-only | Current location |
| `isGlowing` | `boolean` | Read/Write | Glowing |
| `isInvulnerable` | `boolean` | Read/Write | Invulnerable |
| `isSilent` | `boolean` | Read/Write | Silent |
| `hasGravity` | `boolean` | Read/Write | Has gravity |
| `passengers` | `string[]` | Read-only | Passenger UUIDs |
| `vehicle` | `string \| null` | Read-only | Vehicle UUID |

> Entity type keys (`minecraft:zombie` etc.) value domain see [Value Domain Appendix · Version Change Domain](../specifications/values.md#4-version-change-domain-rules--references).

## Methods

Default is async (`Promise`), synchronous version adds `Sync` suffix.

```js
entity.remove()                       // Promise
entity.removeSync()
entity.teleport(loc)                  // Promise
entity.teleportSync(loc)
```

### Async Property Access

| Sync Getter | Async Method | Return |
| ----------- | ------------ | ------ |
| `entity.type` | `entity.getType()` | `Promise<string>` (minecraft registration key, e.g., `minecraft:zombie`) |
| `entity.name` | `entity.getName()` | `Promise<string>` |
| `entity.customName` | `entity.getCustomName()` | `Promise<string \| null>` |
| `entity.world` | `entity.getWorld()` | `Promise<string \| null>` |
| `entity.location` | `entity.getLocation()` | `Promise<Location \| null>` |
| `entity.isGlowing` | `entity.isGlowingAsync()` | `Promise<boolean>` |
| `entity.isInvulnerable` | `entity.isInvulnerableAsync()` | `Promise<boolean>` |
| `entity.isSilent` | `entity.isSilentAsync()` | `Promise<boolean>` |
| `entity.hasGravity` | `entity.hasGravityAsync()` | `Promise<boolean>` |
| `entity.passengers` | `entity.getPassengers()` | `Promise<string[]>` |
| `entity.vehicle` | `entity.getVehicle()` | `Promise<string \| null>` |
| `entity.boundingBox` | `entity.getBoundingBox()` | `Promise<BoundingBox>` (`{ minX, minY, minZ, maxX, maxY, maxZ }`) |

Async setter (sync setter is property assignment):

```js
entity.setCustomName(name)            // Promise
entity.setGlowing(flag)               // Promise
entity.setInvulnerable(flag)          // Promise
entity.setSilent(flag)                // Promise
entity.setGravity(flag)               // Promise
entity.setCustomNameVisible(flag)     // Promise
```

## Potion Effects

Potion effects API acting on LivingEntity see [Potion documentation](potion.md).

## LivingEntity

Player inherits from LivingEntity, additional properties:

```js
entity.health                // Read/Write
entity.maxHealth             // Read-only
entity.isDead                // Read-only
```

Async versions:

```js
entity.getHealth()           // Promise<number>
entity.setHealth(value)      // Promise
entity.getMaxHealth()        // Promise<number>
entity.isDeadAsync()         // Promise<boolean>
```

## Basic Operations

All `Entity` (velocity/fireTicks/ticksLived/isOnGround) and `LivingEntity` (damage/setTarget):

```js
// Velocity vector (blocks/second)
entity.velocity = { x: 0, y: 1, z: 0 };      // Read/Write
await entity.setVelocity({ x: 0, y: 1, z: 0 });

// On fire / ticks lived / on ground
entity.fireTicks = 20;                        // Read/Write (0 = not on fire)
entity.ticksLived;                            // Read-only
entity.isOnGround;                            // Read-only

// Damage
await entity.damage(5);                       // Apply 5 damage
await entity.damage(5, 'damager-uuid');       // With damage source

// AI target (no guarantee of effectiveness — depends on entity type/pathfinding ability)
await entity.setTarget({ targetUuid: 'target-entity-uuid' });                    // Entity target
await entity.setTarget({ world: 'world', x: 100, y: 64, z: 100, speed: 1.0 });   // Position target (Pathfinder)
```

> **setTarget semantics**: Requires mob entity (Mob) to take effect; non-mob/entities without pathfinding silently ignore. Position targets use Pathfinder pathfinding, with `speed` (default 1.0) controlling movement speed.

## Example

```js
const e = await Entity.get(uuid);
if (e) {
    console.log(e.type, e.location?.world);
    e.isGlowing = true;
    await e.teleport(new Location(0, 80, 0, 0, 0, 'world'));

    // Potion effects (LivingEntity instance method; type is minecraft registration key)
    await e.addPotionEffect({
        type: 'minecraft:speed', duration: 200, amplifier: 0
    });
}
```