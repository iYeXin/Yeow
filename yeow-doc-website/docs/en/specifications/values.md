# Value Appendix: Enums and Set Rules

> Scope: Format rules and listings for enum/set values in task payloads and events.

> This appendix is part of the Yeow specification.

---

## I. Format Rules

| Rule               | Canonical Format                                  | Applicable Domains                                                                              |
| ------------------ | ------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| **R1 Registry Key** | `namespace:path` lowercase (e.g., `minecraft:zombie`) | Blocks/Items/Materials, Entity Types, Biomes, Sounds, Particles, Enchantments, Potion Effects, Attributes, Damage Types, Advancement Keys, Recipe Keys |
| **R2 Lowercase Canonical String** | Lowercase (e.g., `survival`)             | Gamemode, Difficulty                                                                            |
| **R3 Vanilla Naming** | Vanilla naming (camelCase, e.g., `keepInventory`) | Game Rules                                                                                    |
| **R4 Translation Key** | Vanilla translation key (lowercase dot-separated, e.g., `death.attack.lava`) | `Message.key`                                                                         |
| **R5 Reserved Enum** | UPPERCASE (e.g., `CHEST`)                        | See Section II listing                                                                          |

**Direction Rule**: Input parameters should accept multiple formats (including: 1. omitting the `minecraft` namespace 2. case-insensitive), while output values must strictly follow the rules below.

**Runtime Dynamic Enums**: `server.getBlocks` / `server.getItems` / `server.getMaterials` (blocks/items/materials), `world.getGameRules` (game rules), `world.getBiome(x, y, z)` (single-point biome).

**Complete Value Table**:

| Value Domain                                                                                         | Canonical Format                                               | Output                 |
| ---------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- | ---------------------- |
| Block / Item / Material (`type`, `blockType`, `item.type`)                                           | R1 Key                                                        | R1 Key                 |
| Entity Type (`entity.getType`, `spawnEntity.type`, event `entityType`/`projectileType`)              | R1 Key                                                        | R1 Key                 |
| Biome (`world.getBiome`)                                                                             | R1 Key                                                        | R1 Key                 |
| Sound (`sound`)                                                                                      | R1 Key                                                        | — (input only)         |
| Particle (`spawnParticle.particle`)                                                                  | R1 Key                                                        | — (input only)         |
| Enchantment (`meta.enchantments` key)                                                                | R1 Key                                                        | R1 Key                 |
| Potion Effect (`type`)                                                                               | R1 Key (`minecraft:speed`)                                    | R1 Key                 |
| Attribute (`meta.attributeModifiers[].attribute`)                                                    | R1 Key (`minecraft:attack_damage`)                            | R1 Key                 |
| Damage Type (`playerDeath.deathType`)                                                                | R1 Key (`minecraft:fall`)                                     | R1 Key                 |
| Advancement Key (`advancement.*` `key`)                                                              | R1 Key                                                        | —                      |
| Recipe Key (`recipe.*` `key`)                                                                        | R1 Key                                                        | R1 Key                 |
| Gamemode / Difficulty                                                                                | R2 lowercase                                                   | R2 lowercase           |
| Game Rules                                                                                           | R3 camelCase                                                   | R3 camelCase           |
| Block State (`world.getBlock` `state`)                                                               | Keys lowercase, values preserve type (number/boolean/string); key name version changes see Section IV | Strictly lowercase, values preserve type |
| Interaction Action (`playerInteract.action`), Resource Pack Status (`playerResourcePackStatus.status`) | R5 UPPERCASE (maintained directly)                            | R5 UPPERCASE           |
| `Message.key`                                                                                        | R4 Translation Key                                             | —                      |
| R5 Enums (see Section II)                                                                            | R5 UPPERCASE                                                   | R5 UPPERCASE           |
| Reference Implementations (`entityDamage.cause` / `playerTeleport.cause` / `entityRegainHealth.reason`, see Section III) | R5 UPPERCASE (**non-normative, no guarantee**) | R5 UPPERCASE           |

---

## II. Directly Maintained Enum Listing

This listing is guaranteed by the Yeow specification; runtimes should fully implement the format rules and all enum values:

### Gamemode (`player.getGamemode` / `setGamemode`)

`survival` / `creative` / `adventure` / `spectator`

### Difficulty (`world.getDifficulty` / `setDifficulty`)

`peaceful` / `easy` / `normal` / `hard`

### Player Interaction Action (`playerInteract.action`, PlayerInteractAction)

`LEFT_CLICK_BLOCK` / `RIGHT_CLICK_BLOCK` / `LEFT_CLICK_AIR` / `RIGHT_CLICK_AIR` / `PHYSICAL`

### Resource Pack Status (`playerResourcePackStatus.status`, ResourcePackStatus)

`SUCCESSFULLY_LOADED` / `DECLINED` / `FAILED_DOWNLOAD` / `ACCEPTED` / `DOWNLOADED` / `INVALID_URL` / `FAILED_RELOAD` / `DISCARDED`

### Environment (`world.getEnvironment`)

`NORMAL` / `NETHER` / `THE_END` / `CUSTOM`

### World Type (`world.getWorldType`)

`NORMAL` / `FLAT` / `LARGE_BIOMES` / `AMPLIFIED` (returns `null` when unsupported by the platform)

### Team Color (`scoreboard.setTeamColor`, ChatColor color component)

`BLACK` `DARK_BLUE` `DARK_GREEN` `DARK_AQUA` `DARK_RED` `DARK_PURPLE` `GOLD` `GRAY` `DARK_GRAY` `BLUE` `GREEN` `AQUA` `RED` `LIGHT_PURPLE` `YELLOW` `WHITE`

> ChatColor also has formatting codes `MAGIC` / `BOLD` / `STRIKETHROUGH` / `UNDERLINE` / `ITALIC` / `RESET` (for text formatting, not used for team colors).

### Scoreboard Display Slot (`scoreboard.setObjectiveDisplay`)

`BELOW_NAME` / `PLAYER_LIST` / `SIDEBAR` / `SIDEBAR_TEAM_BLACK` / `SIDEBAR_TEAM_DARK_BLUE` / `SIDEBAR_TEAM_DARK_GREEN` / `SIDEBAR_TEAM_DARK_AQUA` / `SIDEBAR_TEAM_DARK_RED` / `SIDEBAR_TEAM_DARK_PURPLE` / `SIDEBAR_TEAM_GOLD` / `SIDEBAR_TEAM_GRAY` / `SIDEBAR_TEAM_DARK_GRAY` / `SIDEBAR_TEAM_BLUE` / `SIDEBAR_TEAM_GREEN` / `SIDEBAR_TEAM_AQUA` / `SIDEBAR_TEAM_RED` / `SIDEBAR_TEAM_LIGHT_PURPLE` / `SIDEBAR_TEAM_YELLOW` / `SIDEBAR_TEAM_WHITE` (`null` clears the display)

### Team Options (`scoreboard.setTeamOption`)

| Field    | Values                                                                  |
| -------- | ----------------------------------------------------------------------- |
| `option` | `NAME_TAG_VISIBILITY` / `DEATH_MESSAGE_VISIBILITY` / `COLLISION_RULE`   |
| `value`  | `ALWAYS` / `NEVER` / `FOR_OTHER_TEAMS` / `FOR_OWN_TEAM`                |

### BossBar (`bossbar.*`)

| Field   | Values                                                                                       |
| ------- | -------------------------------------------------------------------------------------------- |
| `color` | `PINK` / `BLUE` / `RED` / `GREEN` / `YELLOW` / `PURPLE` / `WHITE` (default `PURPLE`)        |
| `style` | `SOLID` / `SEGMENTED_6` / `SEGMENTED_10` / `SEGMENTED_12` / `SEGMENTED_20` (default `SOLID`) |
| `flag`  | `DARKEN_SKY` / `PLAY_BOSS_MUSIC` / `CREATE_FOG`                                              |

### Click Action (`inventoryClick.action`, ClickType)

`LEFT` / `SHIFT_LEFT` / `RIGHT` / `SHIFT_RIGHT` / `WINDOW_BORDER_LEFT` / `WINDOW_BORDER_RIGHT` / `MIDDLE` / `NUMBER_KEY` / `DOUBLE_CLICK` / `DROP` / `CONTROL_DROP` / `CREATIVE` / `SWAP_OFFHAND` / `UNKNOWN`

### ItemFlag (`ItemStack.meta.itemFlags`)

`HIDE_ENCHANTS` / `HIDE_ATTRIBUTES` / `HIDE_UNBREAKABLE` / `HIDE_DESTROYS` / `HIDE_PLACED_ON` / `HIDE_ADDITIONAL_TOOLTIP` / `HIDE_DYE` / `HIDE_ARMOR_TRIM` / `HIDE_STORED_ENCHANTS` / `HIDE_ITEM_SPECIFICS`

### Inventory Type (`inventory.getType`, event `inventoryType`, InventoryType)

`CHEST` / `DISPENSER` / `DROPPER` / `FURNACE` / `WORKBENCH` / `CRAFTING` / `ENCHANTING` / `BREWING` / `PLAYER` / `CREATIVE` / `MERCHANT` / `ENDER_CHEST` / `ANVIL` / `SMITHING` / `BEACON` / `HOPPER` / `SHULKER_BOX` / `BARREL` / `BLAST_FURNACE` / `LECTERN` / `SMOKER` / `LOOM` / `CARTOGRAPHY` / `GRINDSTONE` / `STONECUTTER` / `COMPOSTER` / `CHISELED_BOOKSHELF` / `JUKEBOX` / `DECORATED_POT` / `CRAFTER` / `SMITHING_NEW`

> Custom Inventory is `CUSTOM`; player inventory is `PLAYER`.

---

## III. Reference Implementations (non-normative, non-mandatory)

The following enums are **reference implementations**: the Yeow specification **does not mandate their implementation and does not guarantee value stability**; they only list commonly seen reference values. The format follows uppercase enums (R5 style); plugins **should not depend on** their completeness.

### Damage Cause (`entityDamage.cause`, DamageCause)

`KILL` / `WORLD_BORDER` / `CONTACT` / `ENTITY_ATTACK` / `ENTITY_SWEEP_ATTACK` / `PROJECTILE` / `SUFFOCATION` / `FALL` / `FIRE` / `FIRE_TICK` / `MELTING` / `LAVA` / `DROWNING` / `BLOCK_EXPLOSION` / `ENTITY_EXPLOSION` / `VOID` / `LIGHTNING` / `SUICIDE` / `STARVATION` / `POISON` / `MAGIC` / `WITHER` / `FALLING_BLOCK` / `THORNS` / `DRAGON_BREATH` / `CUSTOM` / `FLY_INTO_WALL` / `HOT_FLOOR` / `CAMPFIRE` / `CRAMMING` / `DRYOUT` / `FREEZE` / `SONIC_BOOM`

### Teleport Cause (`playerTeleport.cause`, TeleportCause)

`ENDER_PEARL` / `COMMAND` / `PLUGIN` / `NETHER_PORTAL` / `END_GATEWAY` / `SPECTATE` / `ENDERMAN` / `CHORUS_FRUIT` / `DISMOUNT` / `HORSE` / `UNKNOWN`

### Regain Health Reason (`entityRegainHealth.reason`, RegainReason)

`REGEN` / `SATURATED` / `EATING` / `ENDER_CRYSTAL` / `MAGIC` / `MAGIC_REGEN` / `WITHER` / `WITHER_SPAWN` / `CUSTOM`

---

## IV. Version-Varying Domains (Rules + References)

This listing follows Minecraft version changes; the Yeow specification does not mandate specific enum values but requires runtimes to implement the following format rules:

### Block / Item / Material

R1 Key, lowercase (e.g., `minecraft:stone`, `minecraft:diamond_pickaxe`); block states are key-value objects (`state`, values preserve type — number/boolean/string, see "Block States" in Section II). Dynamic enums: `getBlocks` / `getItems` / `getMaterials`. [Minecraft Wiki – Blocks](https://minecraft.wiki/w/Java_Edition_data_values#Blocks) · [Items](https://minecraft.wiki/w/Java_Edition_data_values#Items)

### Block State (`state` key names)

**Implementations should ensure common key names and their corresponding value semantics are stable** (e.g., `axis` / `facing` / `waterlogged` / `level`). **The complete key set changes with versions**; below is the current key name **listing** (refer to [Minecraft Wiki – Block states](https://minecraft.wiki/w/Block_states)):

```
unstable,half,axis,age,type,waterlogged,east,north,south,up,west,facing,shape,ominous,vault_state,trial_spawner_state,powered,rotation,side_chain,face,in_wall,open,hinge,distance,persistent,stage,level,down,triggered,dusted,crafting,orientation,conditional,occupied,part,candles,lit,has_record,hatch,creaking_heart_state,natural,tilt,hydration,bloom,can_summon,shrieking,power,sculk_sensor_phase,attached,copper_golem_pose,hanging,eye,segment_amount,drag,berries,extended,short,mode,pickles,eggs,thickness,vertical_direction,enabled,snowy,signal_fire,potent_sulfur_state,leaves,flower_amount,delay,locked,disarmed,moisture,bottom,tip,bites,honey_level,has_book,has_bottle_0,has_bottle_1,has_bottle_2,charges,attachment,inverted,slot_0_occupied,slot_1_occupied,slot_2_occupied,slot_3_occupied,slot_4_occupied,slot_5_occupied,layers,instrument,note,cracked
```

**Common Key Value Specifications** (normative: the key names and value semantics in the table below are guaranteed stable by the specification):

| Key                                                                                                     | Type           | Allowed Values                                                                                                                              |
| ------------------------------------------------------------------------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `waterlogged` / `lit` / `powered` / `open` / `snowy` / `hanging` / `enabled` / `attached` / `natural` | Boolean        | `true` / `false`                                                                                                                            |
| `axis`                                                                                                  | Enum           | `x` / `y` / `z` (logs/hay blocks/purpillar, etc.)                                                                                           |
| `facing`                                                                                                | Enum           | `north` / `south` / `east` / `west` / `up` / `down`; some blocks only four directions: `north` / `south` / `east` / `west` (e.g., levers, buttons, pistons) |
| `half`                                                                                                  | Enum           | `top` / `bottom` (doors, slabs, double-height plants; doors also have `upper`/`lower`)                                                      |
| `face`                                                                                                  | Enum           | `floor` / `wall` / `ceiling` (buttons, levers)                                                                                              |
| `type`                                                                                                  | Enum           | Varies by block: double slabs `top` / `bottom` / `double`, tall plants `upper` / `lower`, note block `note`, cactus `top` / `middle` / `bottom` |
| `level`                                                                                                 | Integer        | Upper limit varies by block: water `0`–`15`, cauldron `0`–`3`; snow layers see `layers` (`1`–`8`)                                           |
| `age`                                                                                                   | Integer        | Starting at `0`, upper limit varies by crop (e.g., wheat `0`–`7`)                                                                           |
| `rotation`                                                                                              | Integer        | `0`–`15` (skulls, item displays)                                                                                                            |
| `north` / `south` / `east` / `west` / `up` / `down`                                                    | Boolean or Enum | Connection: mostly boolean `true` / `false`; walls are `none` / `low` / `tall`; glass panes/iron bars are boolean                            |

> The table above is the **canonical value domain** for common keys; other keys (e.g., `vault_state`, `trial_spawner_state`, and other special block-specific keys) have values that vary by version/block — refer to [Minecraft Wiki – Block states](https://minecraft.wiki/w/Block_states) for authoritative values.

### Entity Type

R1 Key (e.g., `minecraft:zombie`); [Minecraft Wiki – Entities](https://minecraft.wiki/w/Java_Edition_data_values#Entities) · [Mob List](https://minecraft.wiki/w/Mob)

### Biome

R1 Key (e.g., `minecraft:plains`). [Minecraft Wiki – Biome](https://minecraft.wiki/w/Biome)

### Sound

R1 Key (e.g., `block.note_block.pling`). [Minecraft Wiki – Sounds.json](https://minecraft.wiki/w/Sounds.json)

### Particle

R1 Key (e.g., `minecraft:flame`); [Paper Javadoc – Particle](https://jd.papermc.io/paper/1.21.4/org/bukkit/Particle.html)

### Enchantment

R1 Key (e.g., `minecraft:fortune`). [Minecraft Wiki – Enchanting](https://minecraft.wiki/w/Enchanting)

### Potion Effect

R1 Key (e.g., `minecraft:speed`); [Minecraft Wiki – Status Effect](https://minecraft.wiki/w/Status_effect)

### Attribute

R1 Key (e.g., `minecraft:attack_damage`); [Minecraft Wiki – Attribute](https://minecraft.wiki/w/Attribute) · [Paper Javadoc – Attribute](https://jd.papermc.io/paper/1.21.4/org/bukkit/attribute/Attribute.html)

### Damage Type (`playerDeath.deathType`)

R1 Key (e.g., `minecraft:fall`, `minecraft:mob_attack`); `UNKNOWN` when indeterminate. [Minecraft Wiki – Damage Type](https://minecraft.wiki/w/Damage_type)

### Game Rules

R3 camelCase names, case-insensitive. Dynamic enum: `world.getGameRules`.

### Translation Keys

R4 vanilla translation keys, lowercase dot-separated (e.g., `death.attack.lava`, `item.minecraft.diamond`); authoritative from vanilla lang files, custom keys require resource packs. [Minecraft Wiki – Language](https://minecraft.wiki/w/Language)
