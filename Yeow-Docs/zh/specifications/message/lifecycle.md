# Lifecycle 通道

插件生命周期确认和资源回收通知。

## `unloadDone`

通知运行时插件已解除加载完毕。

- **请求**：`{ "type": "unloadDone" }`
- **返回**：不产生返回值

插件在 `__yeowUnloadCbs` 所有回调执行完毕后发送此消息。运行时收到后应安全关闭插件线程。

---

## `gc-collect`

通知运行时特定资源已不再被 JS 端引用，可以释放。

- **请求**：`{ "type": "gc-collect", "ids": ["gui_3", "boss_5"] }`
- **返回**：不产生返回值

**工作机制：**

1. JS 端通过 `FinalizationRegistry`（或等效机制）将不再使用的资源标识符推入 `__yeowGcQueue`
2. 运行时在每条消息处理完毕后（微任务队列清空后）检查 `__yeowGcQueue`
3. 若有待回收 id，通过 `lifecycle` 通道发送 `gc-collect` 消息
4. 实现收到后释放对应资源

**约定：**

- 资源标识符前缀标识资源种类（`gui_` → 自定义 GUI，`boss_` → BossBar）
- `ids` 数组可为空（表示无待回收资源，可跳过）
- 实现**可以**将 `gc-collect` 延迟到下一次 `$send` 调用而非每条消息后立即处理
