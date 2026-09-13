# Yeow 二进制传输格式 — 两阶段尝试与回退纪要

> **状态：两阶段均已回退，运行时为纯 JSON。**
> - **阶段一（2026-07-24）**：扁平槽 + FNV-1a 快速通道（2KB）——已移除。
> - **阶段二（2026-09-13）**：通用树二进制（16KB 常驻缓冲区，随 `yeow-runtime 0.6.1`）——**默认停用、代码隔离保留**（`BinaryCodec.ENABLED = false`，注入为全局 `$binary`）。
>
> 本文保留两阶段的设计历程、实测数据与回退原因，供后续参考。

---

# 阶段一：扁平槽 / FNV-1a 快速通道（2KB，2026-07）

## 1. 背景

### 1.1 为什么提出二进制

Yeow 的 JS↔Java 唯一通道是 `$_send(channel, jsonString)`，所有游戏任务（`task` 通道）走：

```
JS: JSON.stringify → $_send → Java: gson.fromJson → Scheduler → gson.toJson → JS: JSON.parse
```

在 `yeow-dev` 模拟基准中，裸 JNI 往返仅 **~0.7μs**，加入 4 次 JSON 转换后升至 **~6.8μs**（×9.6）。剔除 `ctx.evaluate` 开销后，纯序列化耗时 **~5.5μs**。然而在真实 Yeow-0 架构下（`MsgQueue` + `Scheduler` 三级队列 + `gson` 树模型 + `CountDownLatch` + `$hm` 分发），用户实测单次互操作约 **30μs**，其中链路固有开销（`empty + _registerCallback + tick + poll + $hm`）约 **7.5μs**，占比 70%。剩余 30% 才是可优化的序列化部分。

尽管如此，对高频小任务（`player.getPing`、`world.getTime`、`hasPermission`）而言，单次省 7μs 累计仍可观，于是提出二进制零拷贝方案。

### 1.2 约束

* 用户代码（含 `yeow-api`）**只接触 `$send(channel, object)`**，快速通道必须透明。
* 任何失败（溢出、类型不匹配、未知 task）静默回退 `$_send` JSON，无感知。
* 不新增用户可见 API，不破坏兼容性。

---

## 2. 设计目标

1. 消除 `JSON.stringify / gson.fromJson / gson.toJson / JSON.parse` 四次转换（高频路径）。
2. 单块 `DirectByteBuffer(2048)` per 插件，JS 单线程事件循环天然无并发冲突。
3. 简单类型走 buffer，复杂类型（嵌套对象/数组、变长集合）走原 JSON。
4. 通过闭包隐藏 `$_writeBuffer / $_readBuffer / $_submitTask`，执行后 `globalThis.* = null`。

---

## 3. 简单 / 复杂类型划分

人为定义，大致规则：**参数模式在编译期可确定 → 简单；否则 → 复杂**。

* 简单：`getOnlinePlayers() → string[]` 返回不定长 → 复杂。
* 复杂：`inventory.getItem` 返回 `{type, amount}` → 多值，旧设计走 JSON；新设计中多值通过哈希表支持，但早期实现仅单值走 buffer。

最终约束（与 2KB 布局一致）：

| 类型      | 上限                         | 说明                 |
| --------- | ---------------------------- | -------------------- |
| `number`  | ≤ 8                          | double               |
| `string`  | ≤ 4 且每串 ≤ 225 UTF-16 字符 | 实际 UTF-8 ≤ 450B/串 |
| `boolean` | ≤ 5                          | 1B/个                |

超出任一上限或遇到 `object`/`array` → 回退。

---

## 4. 内存布局演进

### v1 — 三数组固定偏移

```
0      : numCount  (1B)
1      : strCount  (1B)
2      : boolCount (1B)
3-10   : 保留
11-74  : 8 × double (64B)
75-2042: 4 × (2B len + 490B UTF-8)
2043-47: 5 × boolean
2048B
```

JS 侧：`$_writeBuffer(nums, strs, bools)` 传三个数组。
问题：`yeow-api` 传 `{uuid, message, x, y, z}`（对象），需在 JS 侧 `_extractParams` 拆成三数组；参数顺序由 `Object.keys` 插入顺序决定，与 Java 侧读取顺序必须严格一致，易错。

### v2 — FNV-1a 哈希表

按业务逻辑自然顺序 + 统一 2KB 布局的提议（`9*17+8*8+452*4+1*5 = 2030`）：

```
0-152   : 哈希表 17 × (hash:8B + pos:1B)   // pos = (type<<6 | idx)
153-216 : 数值 8 × double (64B)
217-2024: 字符串 4 × 452B (2B len + 450B)
2025-29 : 布尔 5 × 1B
2030-32 : 三计数器 (num/str/bool)
2033-47 : 保留
```

* FNV-1a 64-bit，`hash = (hash ^ byte) * 0x100000001B3`，初始 `0xCBF29CE484222325`，UTF-8 编码一致性下 JS `BigInt` 与 Java `long` 结果相同，碰撞概率 ≈ 2.5×10⁻¹⁸。
* C 端 `$_writeBuffer(obj)` 直接接收 JS 对象，做 `Object.keys` 遍历 → 哈希 → 写表+数据，返回 `true/false`。
* Java 端 `BufferAccessor.lookup(key, hashTable, posArray)` 扫描 17 槽匹配哈希，`getNumber / getString / getBool(key, source)` 统一接口兼容 `JsonObject` 与 buffer。

最终简化为**单块复用内存**（JS 单线程保证串行，无需双 Buffer）。

---

## 5. 传输协议

```
标准 JSON 路径（5 步）:
  JS: payload(params) → $_send('task', JSON) → Java: gson.fromJson → Scheduler → gson.toJson → JS: JSON.parse(result)

快速路径（3 步）:
  JS: paramsObj → $_writeBuffer(obj) → $_submitTask(type, true[, cbId])
                  ↓
  Java: 从 buffer 读 (hashTable, nums, strs, bools) → BufferTaskRouter.execute() → writeResult(buf)

  同步 (call):
    JS: _st(type, true) 阻塞 → Java tick → BufferTaskRouter.execute → writeResult(buf) → future.complete("true") → JS _rb()
    失败: _wb 返回 false → 回退 JSON
    未知 type: BufferTaskRouter default throw → future.complete("false") → JS 回退 JSON

  异步 (post):
    JS: _wb + _st(type, true, cbId) → null (pending)
    Java tick → BufferTaskRouter → formatResult(buf, result, cbId) →
      单值 Number/String/Boolean → CallbackMessage(value)
      void → CallbackMessage.void
      多值 → 写 buffer → CallbackMessage.buf
    JS: $hm 收到 CallbackMessage → _rb() / 直接用 value / undefined
```

### C 侧注入

`QuickJSWrapper::initBuffer(void* addr, uint32_t size)` 由 `PluginThread` 在 `ctx.create()` 后立刻调用，将 `DirectByteBuffer` 地址通过 JNI 传入，注册 `$_writeBuffer` / `$_readBuffer` 为 `JS_NewCFunction`。`$send` 闭包捕获后 `globalThis.$_writeBuffer = null`。

### Java 侧执行器

`BufferTaskRouter.execute(type, nums, strs, bools)` 按 `type` 的 switch 固定顺序取 `strs[0]=uuid, strs[1]=message` 等；`writeResult(buf, type, result)` 按返回类型写回（单 number / 单 string / 单 bool / void）。

---

## 6. 关键实现文件

| 层   | 文件                                          | 职责                                                                                                                |
| ---- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| C++  | `native/cpp/quickjs_wrapper.h`                | 新增 `bufPtr/bufSize/lengthAtom`，声明 `yeowWriteBuffer/yeowReadBuffer/fnv1a/initBuffer`                            |
| C++  | `native/cpp/quickjs_wrapper.cpp`              | `initBuffer` 注册函数；`yeowWriteBuffer` 写哈希表+数据；`yeowReadBuffer` 读哈希表返回 `[hashes, nums, strs, bools]` |
| Java | `yeow-runtime/.../PluginThread.java`          | 分配 `DirectByteBuffer(2048)`；`nativeInitBuffer`；注册 `$_submitTask` JNI；处理 `lifecycle` 通道                   |
| Java | `yeow-runtime/.../Scheduler.java`             | 新增 `BufferPendingTask` + `bufferPool` + `submitBufferSync/Async` + `executeBufferOne`                             |
| Java | `yeow-runtime/.../task/BufferTaskRouter.java` | 首批 45+ 任务路由 + `writeResult` + `formatResult`                                                                  |
| Java | `yeow-runtime/.../task/BufferAccessor.java`   | 统一 `getNumber/getString/getBool(key, source)`                                                                     |
| JS   | `.../js/init.js`                              | 闭包隐藏 buffer 函数；`$send` 快慢判断；`_hm` 的 `__buf__` 分支；`_wrapBuffer`；`$_fnv1a`                           |
| JS   | `yeow-api/src/task.ts`                        | 导出 `val(obj,key)` 兼容 Map-like 与 JSON 对象                                                                      |
| JS   | `yeow-api/src/player.ts` 等                   | `Player.get` 等少数多值结果用 `val()` 兼容                                                                          |

---

## 7. 基准测试

### 7.1 微基准（裸序列化，`ctx.evaluate` 开销已剥离）

| Payload        | JSON (ns/op) | Buffer (ns/op) | 提升  |
| -------------- | ------------ | -------------- | ----- |
| round trip     | 7195         | 3265           | 54.6% |
| 1 str (UUID)   | 3929         | 2717           | 62.2% |
| max (4n+4s+2b) | 8610         | 3381           | 53.0% |

纯序列化工作（扣除 `empty` 基线 1783 ns）：**Buffer 1.5μs vs JSON 5.5μs，快 3.7×**。

阶段分解（Buffer 3265 ns）：
```
1748 ns (54%) — JS call overhead (ctx.evaluate)
 552 ns (17%) — $_writeBuffer (C memcpy)
 945 ns (29%) — $_readBuffer (JS Array 构造)
    3 ns (0%) — return 算术
```

### 7.2 全链路（含 MsgQueue + Scheduler + $hm）

```
empty:  2921 ns (eval + _registerCallback + tick(empty) + poll(null) + $hm(null))
JSON full: 13777 ns (JSON.stringify + gson.fromJson×2 + gson.toJson×2 + queue + $hm + parse)
Buffer full: 6865 ns
   → Buffer 快 50.6%（net 3.9μs vs 10.8μs）
```

### 7.3 生产环境估算

真实服务器 `pollJs` 含 `Semaphore(50ms)` 等待 + `Tick` 周期抖动，链路固有开销约 7.5μs + 线程/调度 15μs。叠加后：

|        | 计算   | 线程+调度 | 总计      |
| ------ | ------ | --------- | --------- |
| JSON   | 10.8μs | ~15μs     | **~30μs** |
| Buffer | 3.5μs  | ~15μs     | **~17μs** |
| 节省   | 7.3μs  | 0         | **~40%**  |

---

## 8. 发现的问题

### 8.1 布局不一致

C 写新布局（153 偏移），Java 读旧布局（11 偏移），导致参数错位。排查时安装本地 DLL + `mvn install:install-file` + 版本号更新流程繁琐。

### 8.2 解码不一致（已修复）

JS `s.charCodeAt`（UTF-16）与 C/Java UTF-8 的 FNV 输入不同；初始化时已改为 `TextEncoder.encode`，对齐为 UTF-8。

### 8.3 编译阻断

`quickjs_wrapper.h` 缺 `};` 导致整文件编译失败；`nativeInitBuffer(ByteBuffer)` 的 `private final long context` 字段需反射 `GetLongField` 而非传参。

### 8.4 执行器侵入

`BufferTaskRouter` 与 `*Tasks.java` 需合并且改 `Object source` 签名，触及 20+ 文件；`val()` 兼容层让 `yeow-api` 部分方法重写（`Player.get` 等）。

### 8.5 快速通道的“透明性”代价

JS 需维护 `_canBuffer / _extractParams / _isSingle / _wrapBuffer / _bufGet`，约 80 行常驻代码。Java 需维护 `BufferTaskRouter`（360 行）与 `BufferAccessor`。调试时需同时看 JSON 与 buffer 两条路径。

---

## 9. 放弃决策

Yeow 的二进制数据交换格式理论上是优雅的：它将所有不一致性全部隔离至通信层，甚至 `yeow-api` 也无法感知（所有逻辑决策与自动封装位于 `init.js` 中，`yeow-api` 只需按照规定模式从 `init.js` 传回的 `Map-like` 数据结构中按键取值即可）。Java 侧的任务执行器也是如此。

它的收益也是显著的，跨语言数据交换开销几乎被压到了可忽略的程度。理论上，JSON 不再是链路瓶颈。JNI/线程切换/回调与 Promise 化开销凸显了出来。

它曾很接近稳定可用版本，但是我们仍然放弃了它，原因在于：1. 二进制方案带来的全链路收益并不足够显著  2. 维护它对于项目快速迭代是一个相当的负担  3. 我们不认为这种格式是完美的，理论上的关注点分离仍然存在或多或少的抽象泄露  4. 提交时的对象遍历与返回值时的 FNV-1a 解析在 JS 侧的开销仍然难以忽视，这大部分源于设计问题。FNV-1a 看似优雅，但它已经导致了太多问题，我们将在未来的优化中彻底放弃它（哪怕二进制中直传 key 字符串）。

我们将在接下来的快速迭代中使用 JSON 作为唯一数据交换格式，但我们完全保留并规划未来的数据交换格式优化路径：`$send` 的签名从来不接触底层数据格式，而 `$send` 的内部实现是随运行时的 `init.js` 分发的。

### 9.1 回退内容

* 删除 `$_writeBuffer / $_readBuffer / $_submitTask` 注入与调用
* 删除 `BufferTaskRouter` / `BufferAccessor` / `Scheduler.bufferPool`
* `init.js` `$send` 恢复为 `$_send` + `JSON.stringify/parse`
* `yeow-api` 去除 `val()`，`Player.get` 等恢复 `JSON` 对象取字段

### 9.2 保留成果

* `$_send` 通道的 5 处冗余序列化已在此前清理（`executeOne` 重解析、`effectivePriority` 重解析、`$hm` 双解析等），单次往返已从 8-9 次降至 4 次（理论最优）。
* `yeow-dev` 的 `Bench.java` 与 `bench_init.js` 保留，供后续若需重启优化时复用。
* `quickjs-wrapper` 的 `nativeInitBuffer` 分支保留于 `feature/buffer`，未合入主线。

---

## 10. 时间线

| 日期             | 事件                                                                                                                               |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| 2026-07-13       | `quickjs-wrapper` 3.5.2/3.5.1 发布（结构化错误、libwinpthread 修复）                                                               |
| 2026-07-15       | Yeow 互操作开销分析：旧 20μs vs 新 100μs，定位可借鉴点                                                                             |
| 2026-07-16       | 提出“理论 4 次最优”（2 序列化 + 2 反序列化），识别 5 处冗余                                                                        |
| 2026-07-17       | 完成零改动架构优化：删 `queue.sendJava`、去重 `fromJson`、删 `$hm` 双解析；`yeow-dev` 性能模块立项                                 |
| 2026-07-18 16:00 | 部署 `quickjs-wrapper` v3.5.2 基础版本，完成 `yeow-dev` 四场景基准（裸/JOSN/调度器有无 JSON）                                      |
| 2026-07-18       | 提出 2KB 快速通道：`$_writeBuffer/$_readBuffer/$_submitTask`，三类型约束（≤8 N/≤4 S/≤5 B）                                         |
| 2026-07-18 17:17 | 修复 `Player.get` 不在白名单导致的 void 回退 bug；贴 `BUFFER` 调试日志                                                             |
| 2026-07-18       | 引入并发事件（`concurrent-events`，实验性）、回调系统统一、`$dev` 全局变量、TypeScript 类型完善、`create-yeow` 交互优化            |
| 2026-07-20 左右  | 设计演进：开放嵌套扁平化 → 限制单层键值对 → 确定 `number[]→string[]→boolean[]` 顺序 → 最终敲定 FNV-1a 哈希表（9*17+8*8+452*4+1*5） |
| 2026-07-21       | 实现 FNV-1a 哈希表：C 侧 `yeowWriteBuffer(obj)` 接收对象 + 写入映射表；Java 侧 `BufferAccessor` 统一访问                           |
| 2026-07-22       | 完成 `BufferTaskRouter`、`*Tasks.java` 重构、`init.js` 单值/多值 `Map` 适配、`yeow-api` `val()` 兼容层                             |
| 2026-07-23       | 基准复测：微基准 Buffer 快 3.7×，全链路快 50-68%；阶段分解确认 `$_writeBuffer 552ns + $_readBuffer 945ns`                          |
| 2026-07-24       | **决议放弃**                                                                                                                       |

---

# 阶段二：通用树二进制（16KB 常驻缓冲区，2026-09，`yeow-runtime 0.6.1`）

> 与阶段一的关键差异：阶段一是**定长槽 + 哈希键 + 懒取值**（两侧都不逐键构造对象，靠 schema 路由）；阶段二是**通用树 + 逐节点物化**（C/JS/Java 都构造完整对象树），不做 schema、不做任务白名单，`$send(channel, obj)` 完全透明。这决定了两者的收益与代价完全不同。

## 1. 目标与约束

- 通用：任意可 JSON 化的对象/数组/原始值；无 schema、无任务白名单。
- 透明：`$send(channel, payload)` 与 `yeow-api` 语义不变；任何失败静默回退 JSON。
- 键名**直传原始 UTF-8**（明确放弃 FNV-1a——阶段一的教训）。
- 每插件一块常驻缓冲区，JS 单线程私有，无并发。

## 2. 格式（version 2）

小端；无对齐、无哈希。

```
header (12B): u32 magic(0x59454F42) | u16 version(=2) | u8 mode | u8 reserved | u32 bodyLen
mode 0 (binary): u16 channelLen | channel(utf8) | value
mode 1 (json)  : varint len | utf8          （已定义，未接线）
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

- 对象/数组以起止标记界定、无计数；写入为单向递归，解析为 tag 驱动状态机；长度为 LEB128 varint。
- 键名直传 UTF-8，不做哈希（阶段一的 FNV 被否）。
- `T_BYTES` 承载 `ArrayBuffer`/`byte[]`（Java 侧解码为 base64 字符串）。

## 3. 传输接线

- **上行**：`$send(channel, payload)` → `__yeowWrite(channel, payload)` 写缓冲区 → `$_send(null, null)`（Java 按 `args[1] == null` 判定二进制）→ `decodeChannel` + `decodeBinary`；结果经 `encodeResult` 回投，返回状态码 0（null）/ 1（结果在缓冲区，JS `__yeowRead`）/ JSON 字符串（回退）。
- **回退**：`__yeowWrite` 返回 false（>16KB、function、symbol 等）或 payload 为 null/undefined → `$_send(channel, JSON.stringify(payload))`。
- **下行**：事件/命令/服务/任务回投的生产者只把**原始 Java 对象**放入 `MsgQueue`，由 JS 线程在分发前 `encodeBinary`（失败回退 `gson.toJson` → `$hm` JSON）。
- **调度器去 JSON 化**：`submitGameSync` 改 `CompletableFuture<Object>`；`PaperScheduler`/`FoliaScheduler` 的 `finish`/`fail`/`purge*` 不再 `gson.toJson`，序列化收归通信层。
- 桥：`binary.c` 注入 `__yeowWrite`/`__yeowRead`；`QuickJSContext.buffer()` 暴露 16KB `DirectByteBuffer`（`BUFFER_SIZE = 16 * 1024`）。

## 4. 关键缺陷与修复：T_OBJ_END 与键长冲突

**缺陷**：对象结束标记 `T_OBJ_END = 9` 与「键长 varint 低 7 位 = 9」冲突。9 字节键（如 `blockType`）以及 137/265 字节键都会命中。解码端 `peek(b) != T_OBJ_END` 把**键长字节**误判为对象结束，随即错位 → `decodeBinary` 抛异常返回 null → Java `new JsonObject()` → `obj.get("t")` 为 null → 日志 `$_send err: Cannot invoke "JsonElement.getAsString()" because "JsonObject.get(String)" is null`。

**修复**：对象条目前置显式 `T_KEY = 12` 标记，解码端要求 `T_KEY` 而非裸键长；键长不再与结束标记同位置冲突。C（`binary.c`）与 Java（`BinaryCodec`）同步修改；回归覆盖 C `SmokeTest` 与 Java `BinaryCodecTest`（9/137/265 字节键）。

## 5. 性能实测（2026-09-13，Windows / JDK21 / Paper 1.21.4）

### 5.1 端到端（`debug.payload` 同步批量，ms/op）

| 载荷 | 二进制 | JSON | json/bin |
|---|---|---|---|
| nested(small) | 0.027 | 0.021 | ×0.79（JSON 快） |
| world.setBlock | 0.016 | 0.013 | ×0.84（JSON 快） |
| array x200 | 0.019 | 0.065 | ×3.34（二进制快） |
| string 1KB | 0.007 | 0.017 | ×2.42 |
| string 20KB（>16KB） | 0.231 | 0.214 | ×0.93 |
| array x5000（>16KB） | 1.58 | 1.55 | ×0.98 |

> >16KB 两行的「二进制」列实为回退 JSON（`__yeowWrite` 失败），对照无意义。

### 5.2 阶段归因（StageBench，ms/op，已扣 `evaluate` 基线）

| 载荷 | Cenc | Jdec | Jenc | Cdec | C 占比 |
|---|---|---|---|---|---|
| nested(small) | 0.012 | 0.004 | 0.004 | 0.006 | 69% |
| flat8（8 键单层） | 0.008 | 0.001 | 0.001 | 0.006 | 88% |
| array x200 | 0.010 | 0.002 | 0.001 | 0.010 | 90% |
| string 1KB | 0.006 | — | — | 0.005 | 88% |
| obj200 keys | 0.020 | 0.015 | 0.003 | 0.020 | 69% |

- 同载荷纯 JS 侧对照 QuickJS 自带 JSON：我们的 codec 比 `JSON.stringify` + `JSON.parse` 快 **1.3–2.6×**（array x200：0.020 vs 0.052）。
- C 侧微调（`JS_DefinePropertyValueStr/Uint32` vs `JS_SetPropertyStr/Uint32`、`JS_NewAtomLen`）实测 ≈0 → 逐节点物化已到 QuickJS 公开 API 地板。

### 5.3 Java 侧（`JavaBench`，ms/op 与分配）

| 载荷 | 我们解码 | 我们编码 | gson parse | gson toJson |
|---|---|---|---|---|
| nested | 0.001 | 0.001 | 0.002 | 0.004 |
| array200 int | 0.002 | 0.002 | 0.005 | 0.014 |
| obj200 keys | 0.011 | 0.004 | 0.014 | 0.025 |

- 堆 buffer vs direct buffer 无差异；我们的 codec 比 gson 快 1.2–7×、分配少 2–10×（array200 编码 24B vs gson 48KB）。
- 已做优化：整数编码改 `T_I32` + `getAsNumber`（array200 Java 编码 0.008→0.001，分配 ~48KB→24B）；解码 `byte[]→String` 用 ThreadLocal scratch（解码分配 −16~49%）；缓存布尔 `JsonPrimitive`。

## 6. 为什么仍然不够（回退原因）

1. **小对象（最常见）二进制反而慢 16–21%**：固定「常驻缓冲区 + `$_send(null,null)` + `__yeowRead`」协同开销，加上每对象固定开销（`JS_GetOwnPropertyNames` / `JS_NewObject`），盖过了编码收益；JSON 的内建 `stringify/parse` 对少量键足够快。
2. **63–90% 成本在 C 侧逐节点 QuickJS API 调用**，公开 API 无法再优化（实测为 0）。
3. **16KB 上限**：chunk/NBT/大数组这类节点数最多、二进制收益最大的载荷永远回退 JSON。
4. 要拿到阶段一级别的收益，必须回到「扁平槽 + 懒取值 + schema」——即阶段一被否掉的路线。

## 7. 决议：默认停用（隔离保留）

- 运行时恢复**纯 JSON**：`$send` → `$_send(channel, JSON.stringify(payload))`；下行 `gson.toJson`。
- 二进制代码**不删除**，通过单一开关 `BinaryCodec.ENABLED = false`（注入为全局 `$binary`）隔离：`init.js` 的 `$send`/`$hm`、`PluginThread`/`WorkerThread` 的 `$_send` / `encodeResult` / 消息循环均按该开关分流；C 侧 `__yeowWrite`/`__yeowRead` 仍注册但不会被调用。
- 重新启用只需把 `BinaryCodec.ENABLED` 置 `true`（C/Java/JS 自动一致）。
- 实现说明见 `yeow-doc-website/docs/{cn,en}/advanced/binary-transport.md`（顶部标注默认停用）。

## 8. 时间线（第二阶段）

| 日期 | 事件 |
|---|---|
| 2026-09-13 | 在 `quickjs-wrapper`（C + Zig）重写基础上实现通用树二进制（`native/src/binary.{c,h}` + `yeow/transport/BinaryCodec.java`），集成进 `yeow-runtime 0.6.1` |
| 2026-09-13 | 修复 `T_OBJ_END` 与 9 字节键长冲突（引入 `T_KEY`），C/Java 同步 + 回归用例 |
| 2026-09-13 | 基准与阶段归因：确认 C 侧逐节点为瓶颈、小对象劣于 JSON、>16KB 回退 |
| 2026-09-13 | Java 侧优化：`T_I32`、解码 scratch、布尔缓存 |
| 2026-09-13 | **决议默认停用（`BinaryCodec.ENABLED = false`），代码隔离保留，运行时纯 JSON** |

