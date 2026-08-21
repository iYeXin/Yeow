# Overview

Yeow is a plugin development framework for Minecraft servers (**Paper and [Folia](https://papermc.io/software/folia/) dual-platform compatible**): write plugins with **TypeScript / JavaScript**, and the runtime (QuickJS engine) starts an **independent JS thread** for each plugin, communicating with the game thread via a message bridge.

Plugin code does not block the server main thread, and a crash in one plugin does not affect others; build outputs platform-independent `.yeow.zip` plugin packages, and any runtime implementing the [Platform Specification](specifications/README.md) can run the same plugin. **The same plugin package can be directly interchanged between Paper and Folia** (Folia servers only need to use the Folia version runtime, plugins automatically enjoy its multi-threaded advantages, see [Advanced Knowledge · Folia](advanced/folia.md)).

> **Yeow Beta is now available on PaperMC Hangar**: [https://hangar.papermc.io/iYeXin/Yeow/versions](https://hangar.papermc.io/iYeXin/Yeow/versions)

The documentation is extensive, not sure where to start? Find your entry point based on your role:

## Role-Based Guide

| Your Role          | Goal                                  | Start Here                                                                                                       |
| ------------------ | ------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| **Beginner**       | Understand what Yeow can do, get started with first plugin | [Quick Start](getting-started.md) Create project from scratch and deploy; [Build & Distribution](distribution.md) Understand two artifact formats |
| **Plugin Developer** | Write plugins daily, look up API    | [Quick Start](getting-started.md) Master lifecycle and async/sync conventions; [API Reference](api/README.md) Look up docs by module |
| **Advanced Learner** | Understand underlying mechanisms, troubleshoot performance issues | [Advanced Knowledge](advanced.md) Architecture, thread model, three-level scheduler; [Runtime Warning](runtime-warning.md) Alert types and solutions |
| **Dependency Package Developer** | Encapsulate reusable npm packages | [Writing Dependency Packages](package-author.md) Package structure and resource encapsulation; [Encapsulating Service Packages](package-service.md) Three Service patterns |
| **Plugin User**    | Server admin: install and manage plugins | [Runtime Operations](operations.md) `/yeow` management commands and configuration; [Build & Distribution](distribution.md) Deployment methods and one-click install |
| **Platform Implementer** | Implement Yeow-compatible runtime | [Platform Specification](specifications/README.md) Package structure, message protocol, task/event/runtime environment standards |
| **Adapter Developer** | Enable other languages/engines to access Yeow | [Adapter Specification](specifications/adapter/index.md) `PluginEntity` interface, registration API and message contracts |

## Documentation Map

```
Quick Start      getting-started.md     Create → Develop → Build → Deploy (first plugin, async-first)
Permissions & Security permissions.md    Sensitive permission declaration, native service trust and approval
Runtime Operations operations.md         /yeow management commands, runtime configuration (config.yml)
API Reference    api/README.md           Complete index grouped by module (Player / World / Event / Service …)
Advanced Knowledge advanced.md           Architecture, thread model, scheduler, hot reload, security
Build & Distribution distribution.md    .jar and .yeow.zip two formats, one-click install
Writing Dependency Packages package-author.md  Package structure, resource encapsulation, build automation
Encapsulating Service  package-service.md      SDK / JS service / native service three encapsulation modes
Runtime Warning  runtime-warning.md      Alert types, causes, solutions
Roadmap          todo.md                 Development debugging tools / Folia support / Worker API (v1 planning)
Platform Specification specifications/  Protocol layer (package structure, messages, tasks, events, runtime)
```

## Key Concepts Overview

- **Lifecycle**: Game API operations must be performed within `onLoad`; only callback registration before `onInit`
- **Async-First**: API returns `Promise` by default, synchronous operations add `Sync` suffix; use `await` loops for large operations instead of synchronous blocking
- **Permission Declaration**: Sensitive operations like `fs` / `http` / native services / resource extraction must be declared in `yeow.config.json`, undeclared calls return errors
- **Plugin Package**: `.yeow.zip` is platform-independent, place in `plugins/Yeow/` for automatic loading, or `/yeow install <url>` for one-click installation

> Requirements: Node.js 22+ (development) · Java 21+ (optional, required for development server).

> **Version Range**: Yeow plugins target **Minecraft 1.18+** (lower versions may theoretically run but could have functional issues). Development server defaults to Paper **1.21.4** — this is not a hard requirement, can be configured to any supported Paper version in `yeow.config.json`'s `paperVersion` / `paperJar`.