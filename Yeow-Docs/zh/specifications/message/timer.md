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

- `delay`：毫秒
- 到期后通过 `cb` 向 JS 投递回调消息

### `interval`

重复执行（`setInterval` 底层）。

- `delay`：毫秒
- 每隔 `delay` 毫秒通过 `cb` 向 JS 投递回调消息
- 直到 JS 调用 `clearInterval`（底层调用 `_unregisterCallback`）停止
