# Advanced Knowledge

> In-depth explanation of Yeow's architecture, thread model, scheduler, events and callbacks, lifecycle, channels, services and security mechanisms.

- [Architecture & Thread Model](advanced/architecture.md) — Package structure, startup flow, thread model, plugin entity abstraction, Worker (virtual plugin), timer resource management, platform independence, development mode error echo
- [Scheduler & Tasks](advanced/scheduler.md) — Three-level priority scheduler (time slice budget / automatic demotion / idle spin), async vs sync, manual chunking, task configuration (TaskOptions)
- [Events & Callbacks](advanced/events.md) — Event bridge (EventBridge), concurrent/serial, event handling modes, event reentrant deadlock
- [Lifecycle & Hot Reload](advanced/lifecycle.md) — onInit/onLoad/onUnload, hot reload, production reload/unload, force kill mechanism
- [Environment Capabilities & Channels](advanced/channels.md) — $_send/$send, each message channel explanation
- [Service Mechanism](advanced/service.md) — Plugin Service (inter-plugin communication) and Native Service (native extension) mechanisms
- [Folia Support (Experimental)](advanced/folia.md) — Regionalized multi-threaded platform runtime architecture, implications, platform transparency and deployment
- [About Yeow](advanced/about.md) — Positioning, design goals, basic principles, future planning and vision

> Runtime alerts and performance analysis see [Runtime Warning Guide](runtime-warning.md); Runtime operations (`/yeow` commands and configuration) see [Runtime Operations](operations.md); Permissions and security (sensitive permission declaration, native approval, fs path isolation) see [Permissions & Native Service Trust](permissions.md).