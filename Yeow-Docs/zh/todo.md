# 路线图（TODO）

以下为 Yeow v1 方向性规划，尚未实现。API 稳定性以 [概览](overview.md) 为准——v1 前不保证向后兼容，规划可能随开发调整。

## 开发调试工具

增强插件开发与运行时的诊断体验：

- 运行时调试器（断点 / 单步 / 变量查看）——基于 QuickJS 调试协议或独立实现
- 可视化性能面板：调度器 / 心跳 / 事件延迟的实时视图（当前有 `/yeow profile|track` 文本报告）
- 更完善的错误诊断：插件崩溃现场快照、跨线程消息追踪

## Folia 支持

适配 [Folia](https://github.com/PaperMC/Folia) 的多线程分区服务器模型：

- 调度器：任务按区域（region）调度，避免跨分区访问游戏状态
- 事件桥：区域线程上的事件分发语义
- 全局任务（非区域绑定）与异步 API 的线程模型适配

## Worker API（多线程）

基于插件实体抽象（[`PluginEntity`](specifications/adapter/index.md)，`isVirtual()` 预留标记）提供多线程 Worker 能力：

- 注册**虚拟插件**实体：独立线程 + 消息循环，接入事件 / 命令 / 调度器 / Profile 全链路
- Worker 场景：CPU 密集计算、并行批量任务、与 JS 插件线程隔离的长任务
- 与现有三级调度器的协作语义（Worker 消息不占用 JS 插件消息循环）
