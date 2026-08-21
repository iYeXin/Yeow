# API Reference

Complete API index grouped by module. Marked ⭐ are **common APIs** (core capabilities most plugins will use).

## Player & Entity

| Documentation | Description |
| ------------- | ----------- |
| ⭐ [Player](player.md) | Player: Properties, location, messages, teleport, permissions, held items |
| ⭐ [Entity](entity.md) | Entity/Living: Type, location, status, health, speed, target (Player also belongs to entity) |
| ⭐ [Location](location.md) | Coordinates and orientation (parameter/return universal) |

## World & Blocks

| Documentation | Description |
| ------------- | ----------- |
| ⭐ [World](world.md) | World: Time, weather, difficulty, game rules, blocks, entities, explosions |
| ⭐ [Block](block.md) | Block: Data descriptor (type/state), world operations |
| ⭐ [Material](material.md) | Material-level static judgment (isSolid/isAir/getMaxDurability) + registry query |
| [Chunk](chunk.md) | Chunk snapshot (batch block index, advanced performance tool) |

## Entity Attachments

| Documentation | Description |
| ------------- | ----------- |
| [Potion](potion.md) | Potion effects: Add/remove/clear/query |
| [Particle](particle.md) | Particle generation |

## Items & Data

| Documentation | Description |
| ------------- | ----------- |
| ⭐ [ItemStack](item.md) | Item pure data descriptor: type/amount/meta |
| ⭐ [Inventory](inventory.md) | Unified container: Player inventory / container blocks / custom Inventory |
| ⭐ [PDC](pdc.md) | Persistent data container: Player/Block instance methods, JSON auto serialization |
| [BossBar](bossbar.md) | Boss bar: Title, progress, color, player binding |
| [Scoreboard](scoreboard.md) | Scoreboard: Objectives, teams |
| [Advancement](advancement.md) | Advancement: Grant/revoke |
| [Recipe](recipe.md) | Recipe: Add/remove |

## Events & Commands

| Documentation | Description |
| ------------- | ----------- |
| ⭐ [Event](event.md) | Event subscription: `eventOn` / `eventOff` |
| ⭐ [Command](command.md) | Command registration + Tab completion |

## Permissions

| Documentation | Description |
| ------------- | ----------- |
| ⭐ [Permission](permission.md) | Permission nodes: `registerPermission`, command permissions, `permissionCheck` ecosystem hook |

## Files & Network

| Documentation | Description |
| ------------- | ----------- |
| ⭐ [FS](fs.md) | File system read/write (including `path` tools) |
| [Assets](assets.md) | Packaged resources: `getAssetsPath` (`yeow-dev`) + read/extract |
| [HTTP](http.md) | Underlying HTTP client: `request` / global `fetch` |
| [HTTP Server](http-server.md) | HTTP server (`yeow-server` `createServer`) |
| [Service](service.md) | Inter-plugin/native services: Registration, request, subscription (**more advanced**, most plugins don't use) |

## Server & Runtime Environment

| Documentation | Description |
| ------------- | ----------- |
| [Server](server.md) | Server-level: Broadcast, MOTD, version, TPS |
| [Env](env.md) | Runtime environment info + microsecond timestamp |
| [Worker](worker.md) | Virtual plugins (independent thread): `createWorker` (**advanced**) |
| [Util](util.md) | Data tools: gzip, UTF-8 ↔ bytes |

## Text & Logging

| Documentation | Description |
| ------------- | ----------- |
| [Text](text.md) | Text and MiniMessage: Markup syntax, escape rules |
| [Log](log.md) | Logging: `log` / `Logger` / `console` |