# Yeow v0

**Write Minecraft Paper plugins with JavaScript / TypeScript. QuickJS engine.**

Yeow is a plugin development framework for Minecraft Paper servers: write plugin logic with modern frontend engineering (TypeScript, npm, esbuild), and the runtime starts an **independent QuickJS thread** for each plugin, communicating with the game main thread via a message bridge. Plugin code does not block the server main thread, and a crash in one plugin does not affect other plugins.

```bash
npm create yeow@latest -- -y     # Create project
cd my-plugin
npm install
npm run dev                      # One-click start Paper + second-level hot reload
npm run build                    # Output standard Paper JAR + platform-independent .yeow.zip
```

---

## Why Use Yeow

### Modern Engineering Experience

- **TypeScript First** — Full type inference: command parameters, event payloads, and API return values all have complete types, with editor auto-completion + compile-time checks
- **npm Ecosystem** — Plugin logic and resources can be packaged as npm packages for reuse (`yeow-api`, `yeow-command`, `yeow-server`, custom packages); `assets/` resources are automatically packaged by dependency namespace
- **Hot Reload** — Changes to `src/` or `assets/` take effect automatically without restarting the server; production environments can use `/yeow reload/unload` for management
- **Build-as-Artifact** — `npm run build` outputs **standard Paper JAR** + **platform-independent `.yeow.zip`**: JAR goes into `plugins/`, zip goes into `plugins/Yeow/` for automatic loading (or `/yeow install` for one-click installation)
- **Declarative Permissions** — Sensitive message nodes like `fs:server.*`, `fs:outer.*`, `http:*`, `service:registerNative` require authorization declaration; undeclared calls return errors (assets channel has no permission interception — read-only packaging resources or extract to plugin data directory)

### Thread Separation

- **Independent JS thread per plugin** (independent QuickJS context) — Global isolation between plugins, a crash in one plugin does not affect others
- **JS logic separated from game main thread** — Plugin code does not block the server main thread
- **Three-level priority scheduler** (HIGH/NORMAL/LOW) — Time slice budget + automatic demotion + idle spin, preventing a single plugin from monopolizing the global tick
- **Async-first API** — `Promise` by default, synchronous operations add `Sync` suffix, choose as needed

### Cross-Platform

- Plugin packages are standard ZIP (`.yeow.zip`, containing packaged JS, resources, metadata, and permission declarations), **no Java environment dependency**
- Place in `plugins/Yeow/` for automatic scanning and loading, or use `/yeow load` for dynamic loading
- **Paper and [Folia](https://papermc.io/software/folia/) dual-platform compatible**: The same `.yeow.zip` / `.jar` plugin package can be directly interchanged, with identical API usage; Folia servers only need to install the Folia version of [Yeow Runtime](https://hangar.papermc.io/iYeXin/Yeow/versions) (available on Hangar)
- **Deep Folia adaptation**: Yeow implements an independent regionized scheduler for Folia (region residency, hot-spot migration, budget control, non-blocking delivery), plugins automatically enjoy Folia's multi-threaded parallel advantages without any changes — see [Advanced Knowledge · Folia](advanced/folia.md)
- Any runtime implementing the [Platform Specification](specifications/README.md) can run the same plugin; Folia is just one example of Yeow's cross-platform capability, with potential future support for Fabric / NeoForge and other platforms
- Paper series (Paper/Purpur/Leaf etc.) `yeow-runtime` is the official implementation example

### Native Capability Extension

- **Native Service** — Plugins can embed native programs like Go / Rust / C++ (`assets/` carries binaries, runtime automatically extracts and spawns by platform), called via `serviceRequest`, suitable for heavy computation like image processing, machine learning
- **Runtime Health Detection** — Automatic alerts for heartbeat timeout, event/completion timeout, plugin hanging; dynamic expansion of tick budget during queue backlog

## Comparison with Other Solutions

|              | Yeow                | Traditional Java Plugins | Skript        | Script Engine Plugins (Nashorn/Rhino) |
| ------------ | ------------------- | ------------------------ | ------------- | ------------------------------------- |
| Development Language | TypeScript/JS   | Java                     | Skript DSL    | JS (restricted)                       |
| Type Safety  | ✅ Full type inference | ✅                       | ❌            | ❌                                    |
| npm Dependency Reuse | ✅             | Partial                  | ❌            | ❌                                    |
| Hot Reload   | ✅ Second-level      | ❌ Requires restart      | Partial       | Partial                               |
| Thread Isolation | ✅ Independent thread per plugin | Main thread | Main thread | Main thread |
| Performance  | QuickJS (near-native) | Native              | Interpreted   | Interpreted                           |
| Platform Portability | ✅ Plugin package platform-independent | ❌ JVM only | ❌ Paper series only | ❌ JVM only |

---

## Documentation and Toolchain

| Documentation                          | Description                                    |
| -------------------------------------- | ---------------------------------------------- |
| [Quick Start](getting-started.md)      | Create project → Develop → Build → Deploy      |
| [Build & Distribution](distribution.md) | JAR / `.yeow.zip` two formats and one-click install |
| [Permissions & Native Service Trust](permissions.md) | Sensitive permission declaration, SHA-256 trust, native service approval |
| [Runtime Operations](operations.md)    | `/yeow` management commands, runtime configuration (config.yml) |
| [API Reference](api/README.md)         | Complete index grouped by module               |
| [Writing Dependency Packages](package-author.md) | Package structure, resources, build automation |
| [Encapsulating Service Packages](package-service.md) | SDK / JS service / native service three encapsulation modes |
| [Advanced Knowledge](advanced.md)      | Architecture, thread model, scheduler          |
| [Platform Specification](specifications/README.md) | Protocol layer (for other runtime implementers) |

**Toolchain**: `create-yeow` scaffolding, dev-server, build — see [CLI Reference](cli.md). Requirements: Node.js 18+ · Java 21+ (optional, required for development server).

**Runtime Download**: [Hangar](https://hangar.papermc.io/iYeXin/Yeow/versions) · **Project Source**: [GitHub](https://github.com/iyexin/yeow)