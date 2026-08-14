# Timer 通道

定时器机制的底层实现。`setTimeout` / `setInterval` 通过此通道工作。

## 调用格式

```json
{ "type": "<type>", "cb": "<callbackId>", "delay": <ms> }
```

---

## 操作

### `timeout`

延迟执行（`setTimeout` 底层）。

- `delay`：毫秒（**下限 0**）
- 到期后通过 `cb` 向 JS 投递回调消息（一次性）

### `interval`

重复执行（`setInterval` 底层）。

- `delay`：毫秒（**下限 1**——`scheduleAtFixedRate` 的 period 必须 >0）
- 每隔 `delay` 毫秒通过 `cb` 向 JS 投递回调消息
- 直到 JS 调用 `clearInterval` 停止（见下）

### `clear`

取消定时任务（`clearTimeout` / `clearInterval` 底层）。

```json
{ "type": "clear", "cb": "<callbackId>" }
```

- `cb`：被取消定时器注册的回调 ID（与 `timeout` / `interval` 请求中的一致）
- 运行时必须取消对应的 Java 定时任务并释放登记——**仅本地注销回调不足以停止 `interval` 的周期投递**（会形成永久空转的僵尸任务，直到插件卸载）
