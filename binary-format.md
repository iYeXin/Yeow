# Yeow 二进制传输格式 — 设计、实现与回退纪要

> **状态：已废弃。** 2026-07-24 决议：移除二进制快速通道，恢复纯 JSON 传输。
> 本文档保留完整设计历程与测试数据，供后续参考。

---

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
