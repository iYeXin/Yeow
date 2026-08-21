# Roadmap (TODO)

> Vision and future planning see [About Yeow](advanced/about.md). The following are implementation plans for each version, not yet implemented. API stability subject to [Overview](overview.md) — no backward compatibility guarantee before v1, plans may adjust with development.

## Yeow v1

### More Complete API and Event Coverage

Expand yeow-api and runtime task/event bridge capability boundaries

### Development Debugging Tools

Enhance plugin development and runtime diagnostic experience:

- Runtime debugger (breakpoints / single-step / variable inspection) — In development mode use Node.js (V8) to execute JS, obtaining complete debugger capabilities
- Visualization tools: Scheduler task queue / message queue real-time view

### Folia Support (Implemented, Experimental)

Yeow runtime has deep adaptation for [Folia](https://github.com/PaperMC/Folia) (regionalized multi-threaded branch): Tasks/events/commands/permissions fully aligned with Paper, same plugin package dual-platform compatible. See [Advanced Knowledge · Folia](advanced/folia.md).

### Optimization: Task Scheduler and Event Distribution Mechanism

Refactor task scheduler and event distribution mechanism:

- Partially decouple `task` channel binding with main thread
- Introduce **async result callback** for some events that don't need current tick decision (e.g., `permissionCheck`)
- Reduce main thread burden

### WASM Native Support

Yeow is attempting to introduce **WASM**:

- Solve JS **insufficient performance** in specific scenarios
- Explore **efficient interoperability solutions** (platform layer ↔ WASM module)
- Explore **dependency package integration solutions** (npm packages carry `.wasm` artifacts, packaged with package during build)
- Prepare for future high-performance scenarios

### Yeow v2 — Full-stack Mod Development (Client Capabilities)

Yeow v2 will attempt to introduce **client capabilities** (full-stack mod development). In Yeow's vision, mod development is divided into **clearly distinct two parts** — server and client — because Minecraft itself is a classic C/S model:

- **Server** handles game logic
- **Client** handles local resources, graphics rendering, sound effects, key operations

Key design:

- **Plugin's client part and server part run on different JS threads**, communicating via **standardized network communication**
- **Treats single-player games equally**: Minecraft single-player mode essentially starts an internal integrated server — Yeow provides a new way to end previous mod development chaos (logic/physics end separation), **avoiding "single-player normal, crashes when placed on server"** (confusing client-exclusive classes) situations
- **Deployment is trimming**: A mod runs only server part on server, only client part on client; in single-player games, both run **simultaneously** in different threads
- **Never tries to be all-encompassing**: Yeow won't attempt large "do-everything" (e.g., allowing rendering pipeline manipulation), but provides **basic and commonly used interfaces** (key listening, HUD rendering, client sound effects etc.), underlying scenarios still need native development
- **Project structure**: Similar to `server/` and `client/` two directories, developers respectively import `@yeow/server-api` and `@yeow/client-api`
- **Build artifacts**: Still `.yeow.zip` — **Yeow v1 plugins can be considered mods containing only server part**

### Yeow Client Lite — Universal Client Capabilities

During v1 to v2 transition, Yeow considers developing **Yeow Client Lite** (client mod) and **standardizing specification**, providing consistent interfaces across different platforms (drawing simple HUD, listening to client keys etc.), for server to call:

- As long as a client has Yeow Client Lite installed, **in any server** can enjoy client capability enhancement (if corresponding Yeow plugin exists)
- In Yeow v1 and v2 server development, can use **universal client capabilities** provided by Yeow Client Lite, without specifically developing client