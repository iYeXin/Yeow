# 进阶知识

> Yeow 的架构、线程模型、调度器、事件与回调、生命周期、通道、服务与安全机制的深入说明。

- [架构与线程模型](advanced/architecture.md) — 包结构、启动流程、线程模型、插件实体抽象、Worker（虚拟插件）、定时器资源管理、平台无关性、开发模式错误回显
- [调度器与任务](advanced/scheduler.md) — 三级优先级调度器（时间片预算/自动降级/空闲自旋）、异步 vs 同步、手动分片、任务配置（TaskOptions）
- [事件与回调](advanced/events.md) — 事件桥（EventBridge）、并发/串行、事件处理模式、事件重入死锁
- [生命周期与热重载](advanced/lifecycle.md) — onInit/onLoad/onUnload、热重载、生产 reload/unload、强杀机制
- [环境能力与通道](advanced/channels.md) — $_send/$send、各消息通道说明
- [服务机制](advanced/service.md) — Plugin Service（插件间通信）与 Native Service（原生扩展）的机制
- [Folia 支持（实验性）](advanced/folia.md) — 区域化多线程平台的运行时架构、推论、平台透明与部署
- [关于 Yeow](advanced/about.md) — 定位、设计目标、基本原则、未来规划与愿景

> 运行时告警与性能分析见[运行时警告指南](runtime-warning.md)；运行时运维（`/yeow` 命令与配置）见[运行时运维](operations.md)；权限与安全（敏感权限声明、原生批准、fs 路径隔离）见[权限与原生服务可信性](permissions.md)。
