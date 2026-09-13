# 二进制传输

> **状态：已停用（默认）。** 运行时当前只走 JSON：`BinaryCodec.ENABLED = false`，`init.js` 的 `$send`/`$hm` 仅执行 JSON 分支，`$_send(null,null)` 与常驻缓冲区不会被调用。二进制编解码代码（C / JS / Java）**完整保留并隔离**，仅通过 `BinaryCodec.ENABLED`（注入为全局 `$binary`）这一个开关启用。本文档描述该实现，供重新启用时参考。
>
> 弃用理由与历史见仓库根目录 `binary-format.md`：全链路收益不足以覆盖维护成本，且逐节点物化受 QuickJS 公开 API 限制。

JS 与 Java 之间的消息通过每个上下文预分配的一块常驻缓冲区以二进制编码传输；编码失败时回退 JSON。本文描述其实现。插件开发无需了解：`$send` 与 `yeow-api` 的用法与语义不变。

## 缓冲区与线程约束

- 每个上下文分配一块 **16KB `DirectByteBuffer`**（小端），由 `QuickJSContext.buffer()` 暴露，并在创建时经 `nativeRegisterBuffer` 把地址交给 C 侧（`YeowCtx.buf` / `buf_cap`）。
- 该缓冲区**只由创建上下文的 JS 线程读写**。其他线程（主线程 / IO 线程）不接触缓冲区，只把原始 Java 对象放入队列；编码在 JS 线程分发前完成。
- `QuickJSContext` 对非 `interrupt()` 的方法有 owner 线程守卫，跨线程调用抛异常。

## 时序

上行（JS → Java）：

```
JS $send
  ├─ __yeowWrite(channel, payload)      编码写入缓冲区（失败则回退 JSON）
  ├─ $_send(null, null)                 Java（JS 线程）读缓冲区 → JsonObject
  └─ $_send 返回状态码
       0 → null
       1 → __yeowRead()                 从缓冲区解码上行结果
       string → JSON.parse              回退
```

下行（Java → JS）：

```
主线程/IO： postMessage(obj) → MsgQueue<Object>（原始对象）
JS 线程：   take() → 编码写入缓冲区 → $hm(null) → __yeowRead() → _hm(obj)
```

上行请求仅在 `payload` 非 `null`/`undefined` 且 `__yeowWrite` 成功时走二进制；否则 `$_send(channel, JSON)`。

## 二进制格式（version 2）

小端；无对齐、无哈希；键为原始 UTF-8。

```
header (12B): u32 magic(0x59454F42) | u16 version(=2) | u8 mode | u8 reserved | u32 bodyLen
mode 0 (binary): u16 channelLen | channel(utf8) | value
mode 1 (json)  : varint len | utf8
mode 2 (null)  : -

value := tag [payload]
  T_NULL=0  T_FALSE=1  T_TRUE=2
  T_I32=3 (4B)  T_I64=4 (8B)  T_F64=5 (8B)
  T_STR=6   varint len | bytes
  T_BYTES=7 varint len | raw
  T_OBJ=8   ( T_KEY key value )* T_OBJ_END=9
  T_ARR=10  value*         T_ARR_END=11
  T_KEY=12  varint len | key bytes(UTF-8)
```

- 对象/数组以起止标记界定，不写元素计数；写入为单向递归，解析为 tag 驱动的递归状态机。
- 对象条目以显式 `T_KEY` 标记起始：若直接写 varint 键长，当其低 7 位恰为 9 时（如 9 字节的 `blockType`）会与 `T_OBJ_END` 混淆。
- 字符串/字节/键的长度使用 LEB128 varint。
- 当前运行时只使用 `mode 0`；`mode 1`、`mode 2` 已在编解码器中定义但未被接线使用。

## 编解码实现

- C：`quickjs-wrapper/native/src/binary.c` / `binary.h`，随上下文创建安装 `__yeowWrite(channel, obj) -> boolean` 与 `__yeowRead() -> any`。
- Java：`yeow-runtime/jvm/core/.../yeow/transport/BinaryCodec.java`（`encodeBinary` / `decodeBinary` / `decodeChannel`）。两侧布局必须一致。

## 接线点

| 位置 | 作用 |
| --- | --- |
| `init.js` `$send` | 请求体写入缓冲区并处理状态码；回退 JSON |
| `init.js` `$hm` | 入参为字符串则 `JSON.parse`，为 `null` 则 `__yeowRead()` |
| `PluginThread` / `WorkerThread` | `$_send` 回调顶部一次性 `decodeChannel` + `decodeBinary` 得到 `JsonObject`，各通道复用；消息循环把队列对象 `encodeBinary` 进缓冲区，失败则 `gson.toJson`；`encodeResult` 写上行结果并返回状态码 |
| `MsgQueue` | `toJs` 为 `BlockingQueue<Object>` |
| `RuntimeCore` / `SyncCallbackHelper` | `submitTask` / `submitTasks` 返回原始对象；`cbMessageObject` 构造回调信封对象 |
| `PaperScheduler` / `FoliaScheduler` | `finish` / `fail` / `purge*` 以原始对象完成 future（不序列化） |
| `EventBridge` / `CommandBridge` / `Folia*` / `ServiceManager` | 事件/命令/服务回投投递原始对象 |

序列化统一在通信层完成；`TaskScheduler.submitGameSync` 的 future 类型为 `CompletableFuture<Object>`。

## JSON 回退

以下情况回退 JSON，且回退决定发生在 JNI 调用之前：

- 编码后超过 16KB；
- 含不可编码值（function / symbol 等）；
- 键或字符串超出实现上限；
- 编解码异常。

## 已知差异与限制

- **`ArrayBuffer`**：`__yeowWrite` 对 `JS_GetArrayBuffer` 可取得缓冲的值为 `T_BYTES`，Java 侧 `BinaryCodec` 将其解码为 **base64 字符串**；JSON 路径下 `Uint8Array` 会变成数字键对象、`ArrayBuffer` 变成 `{}`。两者表示不同。
- **`undefined` 属性值**：`__yeowWrite` 对对象中值为 `undefined` 的可枚举属性编码为 `T_NULL`（`null`）；`JSON.stringify` 会丢弃该键。
- **自定义 `toJSON`**（如 `Date`）：二进制路径不调用 `toJSON`。
- **16KB 上限**：大载荷（inventory/NBT、chunk、base64 二进制）回退 JSON。
- 缓冲区线程私有，跨线程使用为未定义行为。

## 调试

- 是否走二进制由 `init.js` 的 `$send` 内部决定；插件调用 `$send` 得到的是最终结果，无法据此判断路径。
- `$send(channel, payload, { json: true })` 可强制该次请求走 JSON 文本路径（直接经 `$_send(channel, JSON.stringify(payload))`，不写常驻缓冲区），结果也以 JSON 字符串返回——用于与二进制路径做同尺寸对照（如基准测试）。默认仍为二进制 + 自动回退。
- 需要确认某条消息的路径时，在 `BinaryCodec.encodeBinary` 返回 `false` 的分支、或消息循环的 `gson.toJson` 回退分支临时打点。
- `quickjs-wrapper/java/src/test/.../SmokeTest.java` 覆盖 `__yeowWrite` / `__yeowRead` 往返、溢出与不支持值回退、上行二进制结果；`BinaryCodecTest` 覆盖 Java 侧编解码。
