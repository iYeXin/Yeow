# Event System 任务

事件订阅、取消和完成通知。

---

## 概述

Yeow 事件系统使用 `event.subscribe` 注册事件监听，`event.complete` 回复处理结果，实现插件与游戏事件之间的同步/异步衔接。

## `event.subscribe`

订阅一个游戏事件。

- **请求**：`{ "pluginName": "<name>", "eventType": "<eventType>", "callbackId": "<cbId>" }`
- **返回**：`true`

| 字段         | 必填 | 说明                                                                                 |
| ------------ | ---- | ------------------------------------------------------------------------------------ |
| `pluginName` | 是   | 所属插件名                                                                           |
| `eventType`  | 是   | 事件名（camelCase，如 `playerJoin`），参见[事件名称表](../event/index.md#事件名称表) |
| `callbackId` | 是   | 事件处理器回调 ID（必须以 `persistent: true` 注册）                                  |

**触发时机：** 当游戏事件发生时，运行时向 `callbackId` 投递事件数据（`cb` 通道，`r` 为事件数据对象）。事件数据中包含 `_cancellable` 布尔字段标记是否可取消，以及 `_eventId`（**每次分发的唯一 id**，见下）；顶层 `eventId` 字段同值。

## `event.unsubscribe`

取消订阅事件。

- **请求**：`{ "pluginName": "<name>", "eventType": "<eventType>" }` \| `{ "pluginName": "<name>", "callbackId": "<cbId>" }`
- **返回**：`true`

实现应同时移除对应的事件监听器，避免事件仍被发送到不回应的 JS 回调。

## `event.complete`

事件处理器完成信号——插件向运行时告知事件处理已结束，可以继续调度后续步骤。

- **请求**：`{ "eventId": "<eventId>", "mods": { "cancelled": <bool> } }`
- **返回**：`true`

| 字段             | 必填 | 说明                                                                                     |
| ---------------- | ---- | ---------------------------------------------------------------------------------------- |
| `eventId`        | 是   | 运行时投递事件数据时携带的 `_eventId`（顶层 `eventId`），**原样回传**——每次分发的唯一 id |
| `mods`           | 否   | 事件修改对象。省略或为 `null` 时按 `{}` 处理                                             |
| `mods.cancelled` | 是*  | 若为 `true`，运行时必须取消该事件（`mods` 存在时）                                       |

---

## 事件处理流程

```
1. 游戏事件触发
2. 运行时查找订阅该事件的插件及其 callbackId
3. 通过 cb 通道投递事件数据 → {t:"cb", p:callbackId, eventId, r:{...事件数据, _cancellable:bool, _eventId}}
4. JS 端事件处理器执行
5. JS 端发送 event.complete（params 含 eventId）→ 运行时收到
6. 运行时按 eventId 匹配本次分发并应用 mods（如 cancelled）
```

### 多插件并发

当多个插件订阅同一事件时，实现可以选择：

- **串行**：逐个发送事件给每个插件，等待一个插件完成（`event.complete`）再发送给下一个
- **并发**：同时发送给所有订阅插件，等待最慢的插件完成

两种模式下，各插件对 `cancelled` 的修改合并策略为：任一插件设置取消则取消。