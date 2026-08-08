# 路线图（TODO）

以下为 Yeow v1 方向性规划，尚未实现。API 稳定性以 [概览](overview.md) 为准——v1 前不保证向后兼容，规划可能随开发调整。

## 更完善的 API 和事件覆盖面

扩展 yeow-api 与运行时任务/事件桥的能力边界

## 开发调试工具

增强插件开发与运行时的诊断体验：

- 运行时调试器（断点 / 单步 / 变量查看）——开发模式下使用 Node.js（V8）执行 JS，获得完整的调试器能力
- 可视化工具：调度器任务队列 / 消息队列的实时视图

## Folia 支持

Yeow 运行时尝试适配 [Folia](https://github.com/PaperMC/Folia)

## Worker API（多线程）——已实现

基于插件实体抽象（[`PluginEntity`](specifications/adapter/index.md)，`isVirtual()` 标记）的 Worker API 已落地：

- 注册**虚拟插件**实体：独立线程 + 消息循环，接入事件 / 命令 / 调度器 / Profile 全链路
- Worker 场景：CPU 密集计算、并行批量任务、与 JS 插件线程隔离的长任务
- 详见 [Worker API](api/worker.md) 与 [worker 通道规范](specifications/message/worker.md)

> 规划中：完整的 Worker 开发体验（独立工具链、更细的打包配置）
