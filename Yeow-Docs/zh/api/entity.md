# Entity API

```js
import { Entity, LivingEntity } from 'yeow-api';
```

## 静态方法

```js
Entity.get(uuid)                // Promise<Entity | null>
Entity.getSync(uuid)            // Entity | null
```

## 构造

```js
new Entity(uuid)                // 通过 UUID 引用实体
```

## 属性

| 属性 | 类型 | 读写 | 说明 |
|------|------|:----:|------|
| `uuid` | `string` | 只读 | 实体 UUID |
| `type` | `string` | 只读 | 类型（ZOMBIE, PLAYER...） |
| `name` | `string` | 只读 | 显示名 |
| `customName` | `string \| null` | 读写 | 自定义名 |
| `world` | `string \| null` | 只读 | 所在世界 |
| `location` | `Location \| null` | 只读 | 当前位置 |
| `isGlowing` | `boolean` | 读写 | 发光 |
| `isInvulnerable` | `boolean` | 读写 | 无敌 |
| `isSilent` | `boolean` | 读写 | 静音 |
| `hasGravity` | `boolean` | 读写 | 重力 |
| `passengers` | `string[]` | 只读 | 乘客 UUID |
| `vehicle` | `string \| null` | 只读 | 载具 UUID |

## 方法

默认为异步（`Promise`），同步版本加 `Sync` 后缀。

```js
entity.remove()                       // Promise
entity.removeSync()
entity.teleport(loc)                  // Promise
entity.teleportSync(loc)
```

### 异步属性访问

| 同步 getter | 异步方法 | 返回 |
|------------|---------|------|
| `entity.type` | `entity.getType()` | `Promise<string>` |
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
| `entity.boundingBox` | `entity.getBoundingBox()` | `Promise<BoundingBox>`（`{ minX, minY, minZ, maxX, maxY, maxZ }`） |

异步 setter（同步 setter 即属性赋值）：

```js
entity.setCustomName(name)            // Promise
entity.setGlowing(flag)               // Promise
entity.setInvulnerable(flag)          // Promise
entity.setSilent(flag)                // Promise
entity.setGravity(flag)               // Promise
entity.setCustomNameVisible(flag)     // Promise
```

## 药水效果

作用于 LivingEntity 的药水效果 API 参见 [Potion 文档](potion.md)。

## LivingEntity

Player 继承自 LivingEntity，额外属性：

```js
entity.health                // 读/写
entity.maxHealth             // 只读
entity.isDead                // 只读
```

异步版本：

```js
entity.getHealth()           // Promise<number>
entity.setHealth(value)      // Promise
entity.getMaxHealth()        // Promise<number>
entity.isDeadAsync()         // Promise<boolean>
```

## 示例

```js
const e = await Entity.get(uuid);
if (e) {
    console.log(e.type, e.location?.world);
    e.isGlowing = true;
    await e.teleport(new Location(0, 80, 0, 0, 0, 'world'));

    // 药水效果
    await addPotionEffect(e.uuid, {
        type: 'speed', duration: 200, amplifier: 0
    });
}
```
