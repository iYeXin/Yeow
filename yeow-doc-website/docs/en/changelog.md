# Changelog

> Recording from 2026-08-08. **Brief version**: Daily entry overview. Detailed version see repository root `changelog.md`.

---

## 2026-08-20

- Version 0.5.0: yeow-api / create-yeow / yeow-runtime / yeow-template full version upgrade (BossBar/Scoreboard OOP + breaking change eliminating uuid friction; template depends on yeow-api ^0.5.0)
- API refactor: BossBar / Scoreboard changed to OOP; eliminate "must pass uuid" friction (potion/advancement/sound moved up as instance methods, target parameters accept objects)
- Value domain appendix structure adjustment: Block state directly maintained + new reference implementation section
- Block state state retains type (number/boolean)
- Block state: New common key-value specification table
- New `Material.getMaxDurability` (and synchronous version)
- Documentation cleanup: API index rewrite and ⭐ marker reassignment / Task·event statistics verification / Remove date annotations / Value domain references / http-server delete examples
- Development mode hot reload allows reloading permissions (modifying `permissions` takes effect with hot reload)

## 2026-08-19

- fetch `arrayBuffer` + init.js split into polyfill.js (TextEncoder / TextDecoder)
- util naming convention: Default async, Sync suffix + global TextEncoder/TextDecoder
- Game rule value domain: Output camelCase + input truly lenient

## 2026-08-18

- New `world.isChunkGenerated`
- Remove `Player#setBorder` (client boundary; retain `world.setBorder*`)
- assets channel: Remove permission interception + dest强制限定 plugin directory

## 2026-08-17

- Version upgrade 0.4.2 / 0.4.3 / 0.4.4 (yeow-api / create-yeow)
- http.request response body Uint8Array-ified + fetch on-demand decoding (responseEncoding, body binary, remove requestSync)
- `$_send` closure-ified: Externally only usable `$send`
- New debug:payload (payload echo)
- dev-server: Remove proxy capability
- Fix getItemInMainHand NPE (attributeModifiers is null)
- Fix concurrent event mode field writeback failure (deathMessage etc.)
- Player inherits LivingEntity / Entity (setVelocity etc. available)

## 2026-08-16

- yeow-fflate 0.3.1: ZipReader
- Version upgrade (0.4.1 / 0.1.2)
- New `player.sendBlockChange` (client fake block)
- Consistency fix (overall read-through review)
- http-server respond: body + encoding (remove bodyBase64)
- yeow-server: mount / mountAssets directory request try index.html
- fs.list returns entry names (no longer leaks absolute paths)

## 2026-08-15

- Value domain appendix structure adjustment: Platform enums directly maintained + version change domain regularization
- Value domain format unification (R1-R5 framework): Potion/particle/attribute key-ified + appendix completion
- Protocol/API consistency revision (P1-P4): Entity type key-ified, enum lowercase, item/coordinate unified
- Dev mode stack trace fix: Unhandled rejection can also restore complete async chain (P1-P6)
- init.js fix: interval parameters / GC flush / fetch hanging / duplicate logs
- dir channel merged into env (pluginDir) + log channel level support
- Event handler throwing error no longer hangs event bridge (event.complete finally releases)
- Remove dedupe plugin: yeow-api multiple copies can safely coexist
- fs API: Binary-first (Node-style encoding) + remove Base64 dedicated API
- assets.read merge: Default Uint8Array + explicit encoding
- Version upgrade (0.4.0)
- Streaming API: File stream + chunked gzip; util limits configurable; http callback fix
- yeow-utils split into yeow-command + yeow-server (independent npm packages)
- Version upgrade (0.3.10 / 0.1.30)

## 2026-08-14

- Three fixes: dev-server Chinese garbled / event player zero-roundtrip construction / log prefix alignment
- PlayerDeath ghost trigger: Root cause located and fixed
- Event triggered within task → 5s deadlock (Paper scheduler) fix
- Paper scheduler restores pre-split thread model (setBlock throughput regression)
- New util channel (gzip + UTF-8 ↔ byte conversion)
- Profiler: Virtual plugins (Worker) do not alert heartbeat timeout by default
- CommandBuilder overload matching validates enum values
- path module compatible with Windows backslash paths

## 2026-08-13

- Callback system cross-generation crosstalk fix (PlayerDeath ghost trigger root cause)
- yeow-utils Command API supports Permission objects
- Version upgrade (0.3.2 / 0.1.23)
- Timer channel fix (three items)
- Event writeback field expansion (common stable fields) + Folia aligns with Paper
- Event writeback mechanism expansion (three methods) and death message writeback
- Folia scheduler hardening (ghost execution blocking / LOW starvation protection / unload cleanup)
- Version upgrade (0.3.0 release)
- API coverage expansion (Entity / WorldBorder / Tab / batch tasks / Inventory contents)
- Folia: Tasks / events / permissions fully aligned with Paper
- Folia: Real-machine verification fix
- Folia: Scheduler v1 polish and watchdog
- PDC / ItemStack API expansion
- Inventory unified refactor
- Documentation (event reentrant deadlock special section etc.)
- Runtime fix: 8 audit-confirmed bugs
- quickjs-wrapper 3.9.0: `QuickJSContext.interrupt()`
- Task executor audit fix: 30 items
- Documentation structure optimization

## 2026-08-12

- Folia: Scheduler scaffolding + runtime split completion

## 2026-08-11

- Runtime architecture: core / paper dual module split
- Protocol layer: instance id opaque handle
- Scheduler fix

## 2026-08-10

- Scheduler: Event spin no-budget drain

## 2026-08-09

- Permission system (permissionCheck / registerPermission)
- CommandSender type refactor
- Java plugin integration API
- dev-server AI / headless mode
- Documentation and templates

## 2026-08-08

- Text and Message objects
- Worker API (virtual plugins)
- HTTP / yeow-utils
- Other APIs
- Documentation site and AI workflow