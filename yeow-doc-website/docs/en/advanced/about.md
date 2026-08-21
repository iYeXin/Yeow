# About Yeow

## Positioning

Yeow v1 is a plugin development framework for **Minecraft**: write plugins in **TypeScript / JavaScript**, with the runtime spawning an **independent JS thread** for each plugin and interacting with the game main thread via a message bridge. The build output is a **platform-agnostic** plugin package (`.yeow.zip`) that any runtime implementing the platform specification can run.

## Design Goals

- **Modern Engineering**: TypeScript-first, npm ecosystem, hot reload, build-as-artifact — bringing Web frontend best practices to Minecraft plugin development.
- **Thread Isolation**: Plugin code does not block the server main thread; each plugin runs in an independent thread, so one plugin crashing does not affect others.
- **Platform Agnostic**: Plugin packages are pure ZIP files (JS + resources + metadata) with no dependency on Java / Paper-based systems — the protocol layer is open, and any platform can implement a runtime.
- **Native Capability Extension**: Native Service allows plugins to carry and invoke native programs (Go/Rust/C++), accommodating heavy computation scenarios.

## Core Principles

Yeow's core principle is **avoiding platform dependencies**: plugins depend only on the protocol layer (message channels, tasks, events, permission models) and not on any host platform's proprietary APIs. The `Yeow` runtime for Paper-based systems (Paper/Purpur/Leaf, etc.) is merely the official reference implementation.

### No API for Calling Java Methods

Yeow **will not** provide an API to call Java methods directly from JS. Reasons:

1. **Technical Difficulty**: JS runs in an isolated QuickJS thread/context. Directly calling Java methods requires a cross-thread synchronous bridge, object reference and lifecycle management, and type system mapping — high complexity and difficult to guarantee safety (main thread blocking, reference leaks, thread safety).
2. **Breaking Cross-Platform Compatibility**: Allowing Java method calls means plugin code depends on specific implementations (Paper classes, versioned CraftBukkit packages). The same plugin cannot run on other runtimes (other platforms / future implementations), directly violating the core principle of platform agnosticism.

Scenarios requiring Java-side capabilities are resolved through the **protocol layer**: game operations go through the task/event/command bridge; inter-plugin communication goes through Service; native computation goes through Native Service; other Java plugins can call services registered by Yeow plugins through the [Java Integration Interface](../specifications/java-api.md).

## Future Roadmap

### Yeow v1

Yeow v1 is the matured output of a **proof of concept**: limited by personal capability and time, the full vision of Yeow could not be completely implemented, so **a subset of the vision** was chosen for focused implementation within a single domain (**Minecraft server-side development**). What v1 validates is the feasibility of these core hypotheses in the server-side scenario: writing game plugins in JS/TS, independent threads, platform-agnostic plugin packages, and an open protocol layer.

### Yeow v2 — Full-Stack Mod Development (Client Capabilities)

Yeow v2 will attempt to introduce **client capabilities** (full-stack mod development). In Yeow's vision, mod development is divided into **two clearly distinct parts** — server-side and client-side — because Minecraft itself is a classic C/S (client-server) model:

- **Server-side** handles game logic.
- **Client-side** handles local resources, rendering, sound effects, and key input.

Key design:

- **A mod's client-side part and server-side part run on different JS threads**, communicating via **standardized network messaging**.
- **Yeow treats singleplayer the same way**: Minecraft singleplayer mode essentially launches an internal integrated server — Yeow provides a new way to end the chaos of previous mod development (split between logic side and physical side), **avoiding the "works in singleplayer, crashes on a server" scenario** (mixed client-only classes).
- **On-demand loading**: A mod only runs the server-side part on the server and only the client-side part on the client; in singleplayer, both **run simultaneously** in different threads.
- **Not trying to do everything**: Yeow will not attempt an all-encompassing approach (e.g., allowing manipulation of the rendering pipeline), but instead will provide **basic and commonly used interfaces** (key listeners, HUD rendering, client-side sound effects, etc.), leaving low-level scenarios to native development.
- **Project structure**: Similar to having `server/` and `client/` directories, with developers importing `@yeow/server-api` and `@yeow/client-api` respectively.
- **Build output**: Still `.yeow.zip` — **Yeow v1 plugins can be considered mods that only contain the server-side part**.

### WASM Native Support

Yeow is experimenting with introducing **WASM**: to address **performance limitations** of JS in specific scenarios, and to explore **efficient interoperability solutions** (platform layer ↔ WASM modules) and **dependency package integration** (npm packages carrying `.wasm` artifacts), preparing for future high-performance scenarios.

### Yeow Client Lite — Universal Client Capabilities

During the transition from v1 to v2, Yeow is considering developing **Yeow Client Lite** (a client mod) and **standardizing its specification**, providing consistent interfaces across different platforms (drawing simple HUDs, listening to client-side key presses, playing sound effects, etc.) for server-side invocation:

- As long as a client has Yeow Client Lite installed, it can enjoy enhanced client-side capabilities **on any server** (if a corresponding Yeow plugin exists).
- In Yeow v1 and v2 server-side development, you can use **universal client capabilities** provided by Yeow Client Lite without needing to develop a dedicated client.

### A Better Protocol

The protocol definition process in Yeow v1 was not rigorous, with many incomplete and inappropriate aspects. Some task details are at a low abstraction level and are easily affected by version changes. Yeow v2 aims to introduce a more rigorous protocol design process.

## Openness and Standards

Yeow's goal is to become the **Web Standard for Minecraft**: an open, cross-implementation, sustainably evolving plugin development standard. Any platform can implement a runtime to run the same plugins.

## Vision

Yeow's vision is to build **a future-oriented, open, and universal starting point for creation**.

**YEOW = <span style="color: var(--vp-c-brand-1);">Y</span>our <span style="color: var(--vp-c-brand-1);">E</span>ntry to an <span style="color: var(--vp-c-brand-1);">O</span>pen <span style="color: var(--vp-c-brand-1);">W</span>orld.**
