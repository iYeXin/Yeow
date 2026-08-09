# 进阶知识

> Yeow 的架构、线程模型、调度器、事件与回调、生命周期、通道、服务、运行时运维与安全的深入说明。

- [架构与线程模型](advanced/architecture.md) — 包结构、启动流程、线程模型、插件实体抽象、Worker（虚拟插件）、开发模式错误回显、资源路径机制
- [调度器与任务](advanced/scheduler.md) — 三级优先级调度器、异步 vs 同步、手动分片、任务执行时机
- [事件与回调](advanced/events.md) — 事件桥（EventBridge）、并发/串行、事件处理模式、统一回调系统
- [生命周期与热重载](advanced/lifecycle.md) — onInit/onLoad/onUnload、热重载、生产 reload/unload
- [环境能力与通道](advanced/channels.md) — $_send/$send、各消息通道、运行时配置
- [服务机制](advanced/service.md) — Plugin Service（插件间通信）与 Native Service（原生扩展）
- [运行时运维与安全](advanced/operations.md) — 预警引擎、动态扩容、全量分析、平台无关性、安全
