# Log 通道

`console.log` / `console.warn` / `console.error` / `console.info` 的底层实现。

## 调用格式

```json
{ "level": "<level>", "message": "<text>" }
```

`level` 可选值：`"INFO"`、`"WARN"`、`"ERROR"`。

实现应将消息按对应日志等级输出到服务端。`message` 字段已包含最终消息文本——`[插件名]` 前缀由 init.js 中的 `console.*` 封装层负责添加，实现只需直接输出，不应添加前缀。
