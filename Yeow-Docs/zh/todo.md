# 路线图（TODO）

以下为 Yeow v1 方向性规划，尚未实现。API 稳定性以 [概览](overview.md) 为准——v1 前不保证向后兼容，规划可能随开发调整。

## 更完善的 API 和事件覆盖面

扩展 yeow-api 与运行时任务/事件桥的能力边界：

- **API 覆盖**：补充 Inventory 操作（点击模拟、槽位拖拽）、完整实体属性（AI/寻路、装备、Boss 属性）、世界生成器接口、容器方块交互、计分板边栏等高频需求
- **事件覆盖**：增加更多 Bukkit 事件类型（如 `blockFromTo`、`entityChangeBlock`、`vehicle*`、`worldLoad`/`worldSave`、`playerEditBook` 等），对齐主流 Bukkit 事件面
- **事件粒度**：事件订阅条件（按实体类型/世界过滤）、事件修改结果（`mods` 扩展更多可写字段）
- 覆盖情况以 [事件规范](specifications/event/index.md) 与 [任务规范](specifications/task/index.md) 为准逐步补齐

## 开发调试工具

增强插件开发与运行时的诊断体验：

- 运行时调试器（断点 / 单步 / 变量查看）——开发模式下使用 Node.js（V8）执行 JS，获得完整的调试器能力
- 可视化工具：调度器任务队列 / 消息队列的实时视图

## Folia 支持

Yeow 运行时尝试适配 [Folia](https://github.com/PaperMC/Folia)

## Worker API（多线程）

基于插件实体抽象（[`PluginEntity`](specifications/adapter/index.md)，`isVirtual()` 预留标记）通过 Worker API 为插件提供多线程能力：

- 注册**虚拟插件**实体：独立线程 + 消息循环，接入事件 / 命令 / 调度器 / Profile 全链路
- Worker 场景：CPU 密集计算、并行批量任务、与 JS 插件线程隔离的长任务
