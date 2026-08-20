# 值域附录：枚举与集合规则

> 范围：task 任务与事件载荷中的枚举/集合值格式规则与清单。

> 此附录收录于 Yeow 规范。

---

## 一、格式规则

| 规则              | 规范格式                                       | 适用值域                                                                                       |
| ----------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **R1 注册键**     | `namespace:path` 小写（如 `minecraft:zombie`） | 方块/物品/材料、实体类型、生物群系、音效、粒子、附魔、药水效果、属性、伤害类型、进度键、配方键 |
| **R2 小写规范串** | 小写（如 `survival`）                          | 游戏模式、难度                                                                                 |
| **R3 原版命名**   | 原版命名（驼峰，如 `keepInventory`）           | 游戏规则                                                                                       |
| **R4 翻译键**     | 原版翻译键（小写点分，如 `death.attack.lava`） | `Message.key`                                                                                  |
| **R5 保留枚举**   | 大写（如 `CHEST`）                             | 见第二节清单                                                                                   |

**方向规则**：入参应当允许多种格式（包括：1. 省略 minecraft 命名空间 2. 不区分大小写），出参应当严格遵循下述规则。

**运行时动态枚举**：`server.getBlocks` / `server.getItems` / `server.getMaterials`（方块/物品/材料）、`world.getGameRules`（游戏规则）、`world.getBiome(x, y, z)`（单点生物群系）。

**值域总表**：

| 值域                                                                                              | 规范格式                                                     | 出参                 |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ | -------------------- |
| 方块 / 物品 / 材料（`type`、`blockType`、`item.type`）                                            | R1 键                                                        | R1 键                |
| 实体类型（`entity.getType`、`spawnEntity.type`、事件 `entityType`/`projectileType`）              | R1 键                                                        | R1 键                |
| 生物群系（`world.getBiome`）                                                                      | R1 键                                                        | R1 键                |
| 音效（`sound`）                                                                                   | R1 键                                                        | —（仅入参）          |
| 粒子（`spawnParticle.particle`）                                                                  | R1 键                                                        | —（仅入参）          |
| 附魔（`meta.enchantments` 键）                                                                    | R1 键                                                        | R1 键                |
| 药水效果（`type`）                                                                                | R1 键（`minecraft:speed`）                                   | R1 键                |
| 属性（`meta.attributeModifiers[].attribute`）                                                     | R1 键（`minecraft:attack_damage`）                           | R1 键                |
| 伤害类型（`playerDeath.deathType`）                                                               | R1 键（`minecraft:fall`）                                    | R1 键                |
| 进度键（`advancement.*` 的 `key`）                                                                | R1 键                                                        | —                    |
| 配方键（`recipe.*` 的 `key`）                                                                     | R1 键                                                        | R1 键                |
| 游戏模式 / 难度                                                                                   | R2 小写                                                      | R2 小写              |
| 游戏规则                                                                                          | R3 驼峰                                                      | R3 驼峰              |
| 方块状态（`world.getBlock` 的 `state`）                                                           | 键小写、值保留类型（数字/布尔/字符串）；键名版本变迁见第四节 | 严格小写、值保留类型 |
| 交互动作（`playerInteract.action`）、资源包状态（`playerResourcePackStatus.status`）              | R5 大写（直接维护）                                          | R5 大写              |
| `Message.key`                                                                                     | R4 翻译键                                                    | —                    |
| R5 枚举（见第二节）                                                                               | R5 大写                                                      | R5 大写              |
| 参考实现（`entityDamage.cause` / `playerTeleport.cause` / `entityRegainHealth.reason`，见第三节） | R5 大写（**不强制、不保证**）                                | R5 大写              |

---

## 二、直接维护的枚举清单

此清单由 Yeow 规范保证，运行时应当完整实现格式规范与所有枚举值：

### 游戏模式（`player.getGamemode` / `setGamemode`）

`survival` / `creative` / `adventure` / `spectator`

### 难度（`world.getDifficulty` / `setDifficulty`）

`peaceful` / `easy` / `normal` / `hard`

### 玩家交互动作（`playerInteract.action`，PlayerInteractAction）

`LEFT_CLICK_BLOCK` / `RIGHT_CLICK_BLOCK` / `LEFT_CLICK_AIR` / `RIGHT_CLICK_AIR` / `PHYSICAL`

### 资源包状态（`playerResourcePackStatus.status`，ResourcePackStatus）

`SUCCESSFULLY_LOADED` / `DECLINED` / `FAILED_DOWNLOAD` / `ACCEPTED` / `DOWNLOADED` / `INVALID_URL` / `FAILED_RELOAD` / `DISCARDED`

### 环境（`world.getEnvironment`）

`NORMAL` / `NETHER` / `THE_END` / `CUSTOM`

### 世界类型（`world.getWorldType`）

`NORMAL` / `FLAT` / `LARGE_BIOMES` / `AMPLIFIED`（平台不支持时返回 `null`）

### 队伍颜色（`scoreboard.setTeamColor`，ChatColor 颜色部分）

`BLACK` `DARK_BLUE` `DARK_GREEN` `DARK_AQUA` `DARK_RED` `DARK_PURPLE` `GOLD` `GRAY` `DARK_GRAY` `BLUE` `GREEN` `AQUA` `RED` `LIGHT_PURPLE` `YELLOW` `WHITE`

> ChatColor 另有格式码 `MAGIC` / `BOLD` / `STRIKETHROUGH` / `UNDERLINE` / `ITALIC` / `RESET`（文本格式用，不用于队伍颜色）。

### 计分板展示槽（`scoreboard.setObjectiveDisplay`）

`BELOW_NAME` / `PLAYER_LIST` / `SIDEBAR` / `SIDEBAR_TEAM_BLACK` / `SIDEBAR_TEAM_DARK_BLUE` / `SIDEBAR_TEAM_DARK_GREEN` / `SIDEBAR_TEAM_DARK_AQUA` / `SIDEBAR_TEAM_DARK_RED` / `SIDEBAR_TEAM_DARK_PURPLE` / `SIDEBAR_TEAM_GOLD` / `SIDEBAR_TEAM_GRAY` / `SIDEBAR_TEAM_DARK_GRAY` / `SIDEBAR_TEAM_BLUE` / `SIDEBAR_TEAM_GREEN` / `SIDEBAR_TEAM_AQUA` / `SIDEBAR_TEAM_RED` / `SIDEBAR_TEAM_LIGHT_PURPLE` / `SIDEBAR_TEAM_YELLOW` / `SIDEBAR_TEAM_WHITE`（`null` 清除显示）

### 队伍选项（`scoreboard.setTeamOption`）

| 字段     | 值                                                                    |
| -------- | --------------------------------------------------------------------- |
| `option` | `NAME_TAG_VISIBILITY` / `DEATH_MESSAGE_VISIBILITY` / `COLLISION_RULE` |
| `value`  | `ALWAYS` / `NEVER` / `FOR_OTHER_TEAMS` / `FOR_OWN_TEAM`               |

### BossBar（`bossbar.*`）

| 字段    | 值                                                                                         |
| ------- | ------------------------------------------------------------------------------------------ |
| `color` | `PINK` / `BLUE` / `RED` / `GREEN` / `YELLOW` / `PURPLE` / `WHITE`（默认 `PURPLE`）         |
| `style` | `SOLID` / `SEGMENTED_6` / `SEGMENTED_10` / `SEGMENTED_12` / `SEGMENTED_20`（默认 `SOLID`） |
| `flag`  | `DARKEN_SKY` / `PLAY_BOSS_MUSIC` / `CREATE_FOG`                                            |

### 点击动作（`inventoryClick.action`，ClickType）

`LEFT` / `SHIFT_LEFT` / `RIGHT` / `SHIFT_RIGHT` / `WINDOW_BORDER_LEFT` / `WINDOW_BORDER_RIGHT` / `MIDDLE` / `NUMBER_KEY` / `DOUBLE_CLICK` / `DROP` / `CONTROL_DROP` / `CREATIVE` / `SWAP_OFFHAND` / `UNKNOWN`

### ItemFlag（`ItemStack.meta.itemFlags`）

`HIDE_ENCHANTS` / `HIDE_ATTRIBUTES` / `HIDE_UNBREAKABLE` / `HIDE_DESTROYS` / `HIDE_PLACED_ON` / `HIDE_ADDITIONAL_TOOLTIP` / `HIDE_DYE` / `HIDE_ARMOR_TRIM` / `HIDE_STORED_ENCHANTS` / `HIDE_ITEM_SPECIFICS`

### 物品栏类型（`inventory.getType`、事件 `inventoryType`，InventoryType）

`CHEST` / `DISPENSER` / `DROPPER` / `FURNACE` / `WORKBENCH` / `CRAFTING` / `ENCHANTING` / `BREWING` / `PLAYER` / `CREATIVE` / `MERCHANT` / `ENDER_CHEST` / `ANVIL` / `SMITHING` / `BEACON` / `HOPPER` / `SHULKER_BOX` / `BARREL` / `BLAST_FURNACE` / `LECTERN` / `SMOKER` / `LOOM` / `CARTOGRAPHY` / `GRINDSTONE` / `STONECUTTER` / `COMPOSTER` / `CHISELED_BOOKSHELF` / `JUKEBOX` / `DECORATED_POT` / `CRAFTER` / `SMITHING_NEW`

> 自定义 Inventory 为 `CUSTOM`；玩家物品栏为 `PLAYER`。

---

## 三、参考实现（非规范、不强制）

以下枚举为**参考实现**：Yeow 规范**不强制要求实现、不保证值稳定**，仅列出常见参考值。格式保持大写枚举（R5 风格），插件**不应依赖**其完整性。

### 伤害原因（`entityDamage.cause`，DamageCause）

`KILL` / `WORLD_BORDER` / `CONTACT` / `ENTITY_ATTACK` / `ENTITY_SWEEP_ATTACK` / `PROJECTILE` / `SUFFOCATION` / `FALL` / `FIRE` / `FIRE_TICK` / `MELTING` / `LAVA` / `DROWNING` / `BLOCK_EXPLOSION` / `ENTITY_EXPLOSION` / `VOID` / `LIGHTNING` / `SUICIDE` / `STARVATION` / `POISON` / `MAGIC` / `WITHER` / `FALLING_BLOCK` / `THORNS` / `DRAGON_BREATH` / `CUSTOM` / `FLY_INTO_WALL` / `HOT_FLOOR` / `CAMPFIRE` / `CRAMMING` / `DRYOUT` / `FREEZE` / `SONIC_BOOM`

### 传送原因（`playerTeleport.cause`，TeleportCause）

`ENDER_PEARL` / `COMMAND` / `PLUGIN` / `NETHER_PORTAL` / `END_GATEWAY` / `SPECTATE` / `ENDERMAN` / `CHORUS_FRUIT` / `DISMOUNT` / `HORSE` / `UNKNOWN`

### 回血原因（`entityRegainHealth.reason`，RegainReason）

`REGEN` / `SATURATED` / `EATING` / `ENDER_CRYSTAL` / `MAGIC` / `MAGIC_REGEN` / `WITHER` / `WITHER_SPAWN` / `CUSTOM`

---

## 四、版本变迁域（规则 + 引用）

此清单跟随 Minecraft 版本变化，Yeow 规范对具体的枚举值不作要求，但要求运行时实现下面的格式规范：

### 方块 / 物品 / 材料

R1 键，小写（如 `minecraft:stone`、`minecraft:diamond_pickaxe`）；方块状态为键值对象（`state`，值保留类型——数字/布尔/字符串，见第二节「方块状态」）。动态枚举：`getBlocks` / `getItems` / `getMaterials`。[Minecraft Wiki – 方块](https://minecraft.wiki/w/Java_Edition_data_values#Blocks) · [物品](https://minecraft.wiki/w/Java_Edition_data_values#Items)

### 方块状态（`state` 键名）

**实现应当确保常见键名及对应值语义稳定**（如 `axis` / `facing` / `waterlogged` / `level`）。**完整键集合随版本变化**，以下为当前键名**清单**（参考 [Minecraft Wiki – Block states](https://minecraft.wiki/w/Block_states)）：

```
unstable,half,axis,age,type,waterlogged,east,north,south,up,west,facing,shape,ominous,vault_state,trial_spawner_state,powered,rotation,side_chain,face,in_wall,open,hinge,distance,persistent,stage,level,down,triggered,dusted,crafting,orientation,conditional,occupied,part,candles,lit,has_record,hatch,creaking_heart_state,natural,tilt,hydration,bloom,can_summon,shrieking,power,sculk_sensor_phase,attached,copper_golem_pose,hanging,eye,segment_amount,drag,berries,extended,short,mode,pickles,eggs,thickness,vertical_direction,enabled,snowy,signal_fire,potent_sulfur_state,leaves,flower_amount,delay,locked,disarmed,moisture,bottom,tip,bites,honey_level,has_book,has_bottle_0,has_bottle_1,has_bottle_2,charges,attachment,inverted,slot_0_occupied,slot_1_occupied,slot_2_occupied,slot_3_occupied,slot_4_occupied,slot_5_occupied,layers,instrument,note,cracked
```

**常用键值规范**（正规：下表键名与值语义由规范保证稳定）：

| 键                                                                                                    | 类型         | 允许值                                                                                                                         |
| ----------------------------------------------------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| `waterlogged` / `lit` / `powered` / `open` / `snowy` / `hanging` / `enabled` / `attached` / `natural` | 布尔         | `true` / `false`                                                                                                               |
| `axis`                                                                                                | 枚举         | `x` / `y` / `z`（原木/干草块/紫颂柱等）                                                                                        |
| `facing`                                                                                              | 枚举         | `north` / `south` / `east` / `west` / `up` / `down`；部分方块仅四向：`north` / `south` / `east` / `west`（如拉杆、按钮、活塞） |
| `half`                                                                                                | 枚举         | `top` / `bottom`（门、台阶、双层植物；门另含 `upper`/`lower`）                                                                 |
| `face`                                                                                                | 枚举         | `floor` / `wall` / `ceiling`（按钮、拉杆）                                                                                     |
| `type`                                                                                                | 枚举         | 随方块：双层台阶 `top` / `bottom` / `double`、高植物 `upper` / `lower`、音符盒 `note`、仙人掌 `top` / `middle` / `bottom`      |
| `level`                                                                                               | 整数         | 随方块上限不同：水 `0`–`15`、炼药锅 `0`–`3`；雪层见 `layers`（`1`–`8`）                                                        |
| `age`                                                                                                 | 整数         | `0` 起，上限随作物不同（如 小麦 `0`–`7`）                                                                                      |
| `rotation`                                                                                            | 整数         | `0`–`15`（头颅、物品展示）                                                                                                     |
| `north` / `south` / `east` / `west` / `up` / `down`                                                   | 布尔 或 枚举 | 连接：多数布尔 `true` / `false`；墙为 `none` / `low` / `tall`；玻璃板/铁栏杆为布尔                                             |

> 上表为常用键的**规范化值域**；其余键（如 `vault_state`、`trial_spawner_state` 等特殊方块专有键）值随版本/方块变化，现场以 [Minecraft Wiki – Block states](https://minecraft.wiki/w/Block_states) 为准。

### 实体类型

R1 键（如 `minecraft:zombie`）；[Minecraft Wiki – 实体](https://minecraft.wiki/w/Java_Edition_data_values#Entities) · [生物 ID 表](https://minecraft.wiki/w/Mob)

### 生物群系

R1 键（如 `minecraft:plains`）。[Minecraft Wiki – Biome](https://minecraft.wiki/w/Biome)

### 音效

R1 键（如 `block.note_block.pling`）。[Minecraft Wiki – Sounds.json](https://minecraft.wiki/w/Sounds.json)

### 粒子

R1 键（如 `minecraft:flame`）；[Paper Javadoc – Particle](https://jd.papermc.io/paper/1.21.4/org/bukkit/Particle.html)

### 附魔

R1 键（如 `minecraft:fortune`）。[Minecraft Wiki – Enchanting](https://minecraft.wiki/w/Enchanting)

### 药水效果

R1 键（如 `minecraft:speed`）；[Minecraft Wiki – Status effect](https://minecraft.wiki/w/Status_effect)

### 属性

R1 键（如 `minecraft:attack_damage`）；[Minecraft Wiki – Attribute](https://minecraft.wiki/w/Attribute) · [Paper Javadoc – Attribute](https://jd.papermc.io/paper/1.21.4/org/bukkit/attribute/Attribute.html)

### 伤害类型（`playerDeath.deathType`）

R1 键（如 `minecraft:fall`、`minecraft:mob_attack`）；无法判定时为 `UNKNOWN`。[Minecraft Wiki – Damage type](https://minecraft.wiki/w/Damage_type)

### 游戏规则

R3 驼峰名，大小写不敏感。动态枚举：`world.getGameRules`。

### 翻译键

R4 原版翻译键，小写点分（如 `death.attack.lava`、`item.minecraft.diamond`）；以原版 lang 文件为准，自定义键需资源包提供。[Minecraft Wiki – Language](https://minecraft.wiki/w/Language)
