# 更新日志

> 从 2026-08-08 开始记录。一天内的多次更新合并为一节。

---

## 2026-08-15

### 流式 API：文件流 + 分块 gzip；util 上限可配置；http 回调修复

- **util 上限配置化**：`config.yml` 新增 `util` 段（`max-input-bytes` / `max-output-bytes`，默认均 **256 MiB**，按原始字节计）——替代原先硬编码的 64 MiB base64 / 256 MiB 输出
- **流式文件读写**（fs 通道，`plugin/server/outer` 三段均可用）：`createReadStream` / `createWriteStream`——有状态句柄（`openRead/openWrite → read/write ×n → end/close`），运行时缓冲 256 KiB 降低跨线程往返开销；**背压 = 显式响应**（每操作 await 结果后才发起下一块）；`ReadStream` 支持 `for await`
- **Gzip 命名空间**（util.ts 重构）：`Gzip.compress/compressSync/decompress/decompressSync`（迁移原顶层方法）+ `Gzip.createCompressor()` / `Gzip.createDecompressor()`（**分块输入压缩/解压**，管道式 `write×n → finish`；非 syncFlush，拼接输出与一次性压缩字节级一致）；**旧顶层导出已移除**（破坏性变更：`gzipCompress` 等 → `Gzip.*`）
- **流句柄生命周期**：per-plugin 注册表，卸载/热重载自动关闭（gzip 压缩/解压 + 文件读写）
- **http server 回调丢失修复**：`getRequestBody().readAllBytes()` 对无 Content-Length 的 keep-alive 请求（如普通 GET）永久阻塞（未定义长度流等 EOF）→ 回调永不投递且 io 线程被占死；改为仅当 `Content-Length > 0` 或 chunked 时读 body，处理异常加日志
- **util 通道输入校验修复**：`encode.utf8` 的 `data` 是明文文本（非 base64 承载）——输入上限校验曾对所有操作统一按 base64 解码，导致 `stringToBytes` 对含非 base64 字符的文本报错（`Illegal base64 character`）；改为文本按 UTF-8 字节数校验、二进制操作按 base64 解码校验
- **HTTP 服务器权限文档**：`http:listen` / `http:respond` 均默认拒绝——漏声明 `http:respond` 时服务器可启动、回调可达但响应被拒 → 请求挂起超时（假阳性排查结论）；api/http-server.md 与 permissions.md 补充节点表与症状说明
- **流选项扩展**：`createReadStream(path, { start?, end? })`——字节偏移区间（start 含、end 含，如日志尾部/分片读取）；`createWriteStream(path, { flags? })`——`w` 覆盖（默认）/ `a` 追加 / `wx` 排他创建（已存在报错）；`FileStreamsTest` 新增 5 用例（全文件/偏移区间/越界/覆盖+追加/排他创建）
- **fs.stat**：`fs.stat(path)` / `fs.statSync(path)`（三段 + 顶层导出）——`{ isFile, isDirectory, size, mtimeMs, ctimeMs }`（`Files.readAttributes` 一次取全；路径不存在报错）
- 实现：core `yeow/util/{GzipCompressor,GzipDecompressor,FileStreams}.java`（可单测）+ `PluginThread` 流操作；`UtilCodecTest` 新增 4 用例（分块往返/单块与一次性一致/空输入/穿插空块）
- 文档：api/util.md（重写）、api/fs.md（流式节）、specifications/message/{util,fs}.md、operations.md；站点侧边栏加入 Util 页

### 版本升级（0.3.8 / 0.1.28）

- **yeow-api 0.3.7 → 0.3.8** / **create-yeow 0.3.7 → 0.3.8** / **yeow-utils 0.1.27 → 0.1.28**
- 内容：流式文件读写（含 start/end 偏移、flags 选项）+ 分块 gzip（Gzip 命名空间）、util 上限配置化、http 回调修复、`encode.utf8` 输入校验修复（文本非 base64）、http 权限文档（listen/respond）、旧 gzip 导出移除（破坏性：用 `Gzip.*`）
- 运行时内容更新：模板内置 `yeow-runtime-0.2.0.jar`（含 util 通道、调度器/事件桥/Profile 修复、流句柄、util 校验修复）
- 模板依赖范围 `^0.3.0` / `^0.1.21` caret 自动覆盖，无需改动

---

## 2026-08-14

### 三项修复：dev-server 中文乱码 / 事件 player 零往返构造 / 日志前缀对齐拆分前

- **dev-server 中文乱码**：`a63e07a` 给主路径（stdio inherit）强加 `-Dstdout.encoding=UTF-8`——JVM 向 GBK 控制台（中文 Windows）写入 UTF-8 字节导致乱码。修复：主路径移除该参数（不设时 JVM 自动匹配控制台编码，`-Dfile.encoding=UTF-8` 保留、仅影响文件 I/O）；headless 路径（管道 + readline 按 UTF-8 解码）保留 UTF-8 强制不变
- **事件 player 零往返**：`adaptEvent` 不再 `Player.getSync(uuid)`（同步调度往返）——直接 `new Player(uuid)` 构造；`name` 首次访问时惰性同步获取并缓存（仅一次往返；不读 `name` 的 handler 完全零开销）
- **日志前缀对齐拆分前**：console.log / JS 警告经 `host.logger()` 输出——拆分后该 logger 为插件 logger，多出 `[Yeow]` 前缀。改回根 logger（`Bukkit.getLogger()`，paper + folia 一致）：输出恢复 `[12:42:00 INFO]: [yeow-tools] xxx`
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 更新；**yeow-api 0.3.2 → 0.3.3** / **create-yeow 0.3.2 → 0.3.3**（模板依赖 `^0.3.0` caret 自动覆盖）

### PlayerDeath 幽灵触发：**根因已定位并修复**（原标记 TODO[ghost] 解除）

> **现场证据**（过滤日志）：`Dropped invalid playerDeath dispatch: {"_cancellable":true}`——载荷只有 `_cancellable`，即 `eventData()` 内 `(PlayerDeathEvent)ev` **强转失败**（switch 只执行了开头的 `_cancellable` 填充），异常被 `catch(Exception ignored)` 静默吞掉后残废载荷照常投递给 JS。

- **根因（两层）**：
  1. **平台侧（分发串扰）**：`dispatch("playerDeath", ev)` 收到的 `ev` 是**纯 `EntityDeathEvent`（怪物死亡）**——该 Folia 系 build 的事件分发把纯 EntityDeathEvent 投递给注册为 PlayerDeathEvent 的监听器（FoliaEventBridge 中早有此现象的注释记载）；因 PlayerDeathEvent 是 EVENTS 表中唯一"注册类有父类也在表中"的事件，故**仅 PlayerDeath 受影响**
  2. **运行时侧（吞异常）**：Paper `eventData()` 的 `catch(Exception ignored)` 吞掉强转失败后，残废载荷 `{"_cancellable":true}` 照常进入投递 → JS handler 收到无 `player` 的数据（"玩家并未死亡却触发"、`e.player` 不存在）。**cbId 随机化当然无效——载荷本身就是残废的，与回调跨代无关**
- **修复**（Paper + Folia 两侧一致）：
  1. **死亡事件合并监听器**（Paper 侧新增，对齐 Folia）：`playerDeath`/`entityDeath` 合并注册为一个 `EntityDeathEvent` 监听器，按 `instanceof PlayerDeathEvent` 分流；两个 et 都记入 reg 防重复注册（双投递）
  2. **dispatch 类型守卫**：`et` 与事件实际类型必须匹配，不匹配即丢弃并告警（兜底）
  3. **eventData 不再静默吞异常**：提取失败改为告警 + 返回 null（dispatch 丢弃），杜绝残废载荷再次投递
- 有效性过滤保留为纵深防御

### 任务内触发事件 → 5s 死锁（Paper 调度器，社区报告）

> 报告场景：插件在命令执行器里 `await player.teleport(...)`（Yeow 游戏任务）→ 传送同步触发 PlayerTeleportEvent → 事件桥投递 JS → **事件超时约 5s，主线程阻塞、服务器卡顿**。handler 与执行器均无同步调用，非"经典事件重入"。

- **机制（确认）**：事件自旋期间 JS 回复的 `event.complete`（cb 为空 → `submitGameSync`）进入**优先级池**；池只有调度线程 `yeow-sched` 能泵，而 yeow-sched 正阻塞在 `waitMain`（`fut.get`）等待主线程执行中的传送任务；主线程自旋期间 `drainDuringWait` 只排空 `mainQueue`，不碰池 → **循环等待**直至事件 5s 超时。事件在"Yeow 游戏任务执行过程中"被触发即命中（serverPing 由 Paper 自身触发、playerDeath 由原版逻辑触发，故正常）
- **修复（Paper）**：`drainDuringWait` 在排空 `mainQueue` 之外**同时代行泵职责**——按优先级顺序（high→normal→low）排空三个优先级池并就地执行（`executeNow`，与 `executeOne` 相同完成语义：指标 + future/callback 完成）；`poll` 原子性保证与调度线程无竞态，FIFO 顺序不变。覆盖全部自旋调用点（事件串行/并发、权限检查、tabComplete），且同步修复"handler 内同步 call（如 `Player.getSync`）同样会入池等待"的同类死锁
- Folia 侧不受影响（`SpinPump` 自旋期间直接 `drainForPlugins` 泵池，无独立泵线程阻塞点），无需改动

### Paper 调度器恢复拆分前线程模型（setBlock 吞吐 2x 回归）

> 报告：循环 setBlock 吞吐量约为拆分前的一半。拆分提交 `c44f1f4` 把 Paper 从"主线程 tick 内就地执行"改成"yeow-sched 泵线程 + mainQueue + fut.get 往返"，每任务多两次线程切换 + future park/unpark + 两次队列 hop，且主线程 idleSpin 只看 mainQueue、看不见池——热循环提交→执行延迟从"同一 tick 内"变成"跨线程两跳"（拆分前 init.js/yeow-api 字节级无差异，回归纯在 Java 调度侧）。

- **恢复**（Paper 平台特异，线程模型本就平台差异化——Folia 保持 region 线程模型不变）：
  - 移除 yeow-sched 泵线程：`mainTickPump` 每 tick 直接消费三级池（tier 比例分配 → 贪婪 → 空闲自旋盯三池 + mainQueue），与拆分前 `tick()` 一致
  - `mainQueue`/`waitMain` 仅保留为兜底路径（`executeOne` 非主线程分支，正常情况下不触发）
  - 顺带消除"任务内触发事件"死锁的整个类别：主线程是唯一消费方，事件自旋（`drainDuringWait`）与每 tick 都直接排空池——`event.complete` 不再依赖任何中间泵
  - 指标（TickMetric/预算缩放/LOW 积压告警）移入 `mainTickPump`

### 新增 util 通道（gzip + UTF-8 ↔ 字节转换，0.3.4）

- **协议**（`$_send('util', ...)`，纯计算无权限检查，无流式接口）：
  - `gzip.compress`（`level` 0-9）/ `gzip.decompress`——字节经 **base64 承载**（QuickJS 引擎原生 `Uint8Array.toBase64/fromBase64`，ES2023+）
  - `encode.utf8`（字符串 → 字节）/ `decode.utf8`（字节 → 字符串）——**encode/decode 语义 = buffer ↔ 字符串**，base64 只是承载形式
- **API**（`yeow-api/src/util.ts`，**不暴露 base64**）：`gzipCompress(Sync)` / `gzipDecompress(Sync)`（输入 `Uint8Array | string`，输出 `Uint8Array`）、`stringToBytes(Async)` / `bytesToString(Async)`
- **安全**：输入上限 64 MiB（base64 字符数）；解压输出上限 256 MiB（防压缩炸弹）
- 实现：core `yeow/util/UtilCodec.java`（纯静态可单测）+ `PluginThread.handleUtil`（同步/异步双模式，照 fs 通道骨架）；`UtilCodecTest` 7 用例（往返/级别/空/坏魔数/输出上限/UTF-8 边界）
- 文档：api/util.md、specifications/message/util.md；版本 **yeow-api 0.3.3 → 0.3.4** / **create-yeow 0.3.3 → 0.3.4**（模板依赖 `^0.3.0` caret 覆盖）

### Profiler：虚拟插件（Worker）默认不告警心跳超时

- `heartbeat.timeout` 检测跳过虚拟插件（Worker）——Worker 通常承载计算密集型任务，长时间占用 JS 线程不响应心跳属预期行为
- 实现：`WindowMetrics` 新增 `virtualPlugins` 集合（WindowCollector 在记录 ping 时按 `PluginEntity.isVirtual()` 标记）；`HeartbeatTimeoutDetector` 跳过；`DetectorTest` 增补用例（虚拟插件无 pong/慢响应均不告警，真实插件仍告警）
- 文档：runtime-warning.md heartbeat.timeout 节补充说明

### CommandBuilder 重载匹配校验 enum 值（yeow-utils 0.1.25）

- `match` 此前只数 token 数、不校验 enum 值——`action+name`（2 token）重载并列时先注册者胜：`/proj info test` 会被先注册的 `paste <name>` 吃掉
- 修复：enum 节点校验 token 是否在声明值内，不匹配即该重载不匹配（补全过滤同样受益）

### path 模块兼容 Windows 反斜杠路径（yeow-api 0.3.5）

- `basename`/`dirname` 原为 POSIX 实现（仅 `/`）——Windows 上 fs 通道返回 `\` 路径时 `basename` 返回整串（如 `proj list` 显示完整路径）
- 修复：按 `/` 与 `\` 双分隔符切分（`extname` 基于 basename 自动受益）；`join` 保持 `/` 连接（Windows API 同样接受正斜杠）

---

## 2026-08-13

### 回调系统跨代串扰修复（PlayerDeath 幽灵触发的根因）

- **根因**：回调 ID 每上下文从 `cb_1` 顺序递增——热重载/重载创建新上下文后 ID 与旧代完全重叠；旧代消息（旧定时器投递、reload 时 in-flight 的事件分发、迟到的异步结果）经共享队列落入新上下文后，`_cbs[旧ID]` 命中"同序号、不同用途"的新回调 → **事件 handler 被错误数据调用**（典型症状：playerDeath handler 收到 entityDeath（怪物死亡）数据或定时器 `true` → "玩家并未死亡却触发"、`e.player` 不存在；该问题自首版即存在）
- **修复**：`_cbSeq` 改用**每上下文随机基址**——新旧代回调 ID 不撞号，旧代消息在新上下文查无此回调被静默丢弃；一次修复覆盖全部通道（事件/定时器/命令/服务/Worker/异步任务）
- 规范：specifications/runtime/index.md 回调系统节补充"跨代唯一性"契约说明

### yeow-utils Command API 支持 Permission 对象（0.1.24）

- `Command.create(name, { permission })` 的 `permission` 从 `string` 扩展为 `string | Permission | PermissionOptions`（`{ node, default? }` 对象 / `registerPermission` 返回值）——与 yeow-api `registerCommand` 语义一致，声明权限节点 + 默认值（如 `{ node: 'myplugin.home', default: 'all' }`）
- 透传至 `registerCommand`（内部经 `permissionPayload` 归一化），执行时检查逻辑不变（`permissionCheck` 优先，回退 Bukkit）

### 版本升级（0.3.2 / 0.1.23）

- **yeow-api 0.3.1 → 0.3.2** / **yeow-utils 0.1.22 → 0.1.23** / **create-yeow 0.3.1 → 0.3.2**
- create-yeow 内容变更：模板内置 `yeow-runtime-0.2.0.jar` 更新（含 timer 通道三项修复）；yeow-api/yeow-utils 为发布节奏一致升版（源码无变更）
- 模板依赖范围 `^0.3.0` / `^0.1.21` caret 自动覆盖，无需改动

### Timer 通道修复（三项）

- **clear 协议**：`clearTimeout`/`clearInterval` 现在经 timer 通道发送 `{type:'clear', cb}` 通知 Java **取消定时任务**（此前只做本地回调注销——`setInterval` 的 `scheduleAtFixedRate` 会永久空转，长生命周期插件反复 create/clear 定时器会累积僵尸周期任务；`timerFutures` 列表也随之无界增长）。一次性 timeout 触发后自动释放登记
- **延迟下限**：Java 侧防御 `timeout ≥ 0`、`interval ≥ 1`（`scheduleAtFixedRate` 的 period 必须 >0——`setInterval(fn, 0)` 此前抛 `IllegalArgumentException` 被静默吞掉，JS 回调却已注册 → 悬挂）
- **代际隔离**：reload 时递增 generation，timer 任务投递前校验代际——热重载窗口内在途的旧 timer 消息不再进入新生代队列（旧 cbId 与新上下文 `cb_N` 撞号时曾会幽灵调用新插件回调）
- 规范同步：specifications/message/timer.md 补 `clear` 操作与延迟下限；runtime/index.md 定时器实现说明更新

### 事件回写字段扩展（常用稳定字段）+ Folia 对齐 Paper

- **可回写字段扩充**（此前仅 `cancelled` / `deathMessage` / serverPing 四字段）：新增 `joinMessage`（加入消息）、`quitMessage`、`message` / `format`（聊天）、`message`（命令改写）、`to`（playerMove/playerTeleport 目标位置，`{x,y,z,yaw?,pitch?,world?}`）、`respawnLocation`、`newFoodLevel`、`damage`、`amount`（回血）、`target`（实体目标 UUID/null）、`clickedItem` / `cursorItem`（点击/光标物品 `{type, amount?}`）——偏门/无 setter 字段（from/cause/deathType/经验/等级/潜行/飞行等）保持只读
- **Folia 对齐 Paper**：Folia 事件桥从"仅应用 cancelled"升级为与 Paper `applyMods` 一致的完整回写实现（含 serverPing 的 motd/maxPlayers/numPlayers/icon + PNG 图标加载）
- 回写值格式：位置类 `{x,y,z,yaw?,pitch?,world?}`（world 缺省用事件当前世界）、物品类 `{type, amount?}`、`deathMessage` 支持 Message 对象/字符串
- 文档：api/event.md 回写字段表、specifications 事件规范逐事件标注"可回写"、event-system.md mods 契约、advanced/events.md 同步

### 事件回写机制扩展（三种方式）与死亡消息回写

- **事件回写支持"修改事件参数"**：自动模式下 `e.xxx = ...` 直接赋值事件字段即收集为回写 mods（此前只有 `cancelled` 有收集机制，其余字段必须 return）——与返回值合并，直接赋值优先；`cancelled` 语义不变（仅可取消事件暴露）
- **`playerDeath.deathMessage` 允许回写**（此前只读）：`e.deathMessage = { text: '...' }` 或 `{ key, args }`（可翻译组件）/ 字符串均可——Paper `applyMods` 与 Folia 事件桥同步支持（`TextUtil.parseMessage` 复用）
- 回写约束不变：返回 **Promise 视为无修改立即释放**（不等待其完成），`await` 之后的修改一律无效；异步决定结果请用手动模式 `complete(mods)`——api/event.md、advanced/events.md 已重写回写章节（三种方式 + 支持回写字段清单）
- **命令补全器与事件对齐**：自动模式返回 Promise **不再等待**（此前 await 结果后回传）——视为无补全立即释放（空补全）；异步补全请使用手动模式 `complete(res)`（api/command.md 已更新）

### Folia 调度器加固（调度评估后的 P0/P1 修复）

- **排队超时幽灵执行封堵（G1）**：同步任务在池中排队超过 JS 侧 `task-sync-timeout`（10s）后调用方已放弃——取件路径（`pollAny`/`pollForSpin`）即时剔除 + 周期扫描兜底（`sweepStaleQueued`，与在途扫描同拍），补 `{err: "task queue timed out"}` 绝不执行任务体（异步任务无调用方超时语义，不受影响）
- **LOW 饿死保护（G2）**：严格 H→N→L 取件下，HIGH/NORMAL 洪泛会无限饿死 LOW（Paper 有 0.5/0.3/0.2 比例保护，Folia 无）——新增轻量轮转：LOW 池非空且超 1s 未取过 LOW 时强制取一个，不引入每插件配额
- **卸载清理补齐（G3）**：`purgePluginTasks` 现在同时取消该插件**在途投递**（cancel + 补 err + 回收 in-flight）——插件卸载/热重载后其世界副作用不再落地（此前只清池，已投递任务 ≤5s 内仍会执行）

### 版本升级（0.3.0 发布）

- **yeow-api 0.2.117 → 0.3.0** / **create-yeow 0.2.127 → 0.3.0** / **yeow-utils 0.1.20 → 0.1.21**（peer 依赖同步 `^0.3.0`）
- 破坏性变更：GUI 术语整体弃用并入 Inventory（`GUI.create` → `Inventory.create`、`guiId` → `inventoryId`）；PDC 裸 key 默认命名空间由 `yeow` 改为**插件名**（历史数据需 `yeow:key` 显式迁移）
- 模板依赖范围同步：`yeow-api ^0.3.0`、`yeow-utils ^0.1.21`

### API 覆盖扩充（Entity / WorldBorder / Tab / 批量任务 / Inventory 内容物）

- **Entity 基础补齐**：`getVelocity`/`setVelocity`（速度向量）、`getFireTicks`/`setFireTicks`（着火）、`getTicksLived`/`setTicksLived`、`isOnGround`、`damage(amount, damager?)`；yeow-api 的 `Entity`/`LivingEntity` 类补齐对应属性与方法
- **`entity.setTarget`**（AI 目标）：实体目标（`targetUuid` → `Mob.setTarget`）或位置目标（`world`+`x`+`y`+`z` → `Pathfinder.moveTo`，可带 `speed`）——**不保证必然生效**（取决于实体类型/寻路能力，可移植性取舍，不引入 attribute 任务族）
- **玩家**：`setItemInMainHand`/`setItemInOffHand`（完整 ItemStack 含 meta）、`sendTabHeader`（Tab 栏 header/footer，MiniMessage）、`setPlayerListName`、`setBorder`（客户端世界边界，null 重置）
- **世界**：`getSeed`/`getEnvironment`/`getWorldType`/`getGameRules` + **WorldBorder 全套**（`getBorder`/`setBorderCenter`/`setBorderSize`/`setBorderDamage`/`setBorderWarning`/`setBorderMoving`）
- **Inventory**：`getContents`/`setContents`（全槽位快照读写）
- **批量任务提交**（core 内部，无调度器改动）：task 通道支持 `tasks` 数组——`callBatch`（同步结果数组）/ `postBatch`（异步 Promise 结果数组），逐个独立执行无原子性，单个失败对应项为 `{err}`；协议见 specifications/message/task.md
- 任务总量：Paper 224 / Folia 224

### Folia：任务 / 事件 / 权限与 Paper 全对齐

- **任务覆盖全对齐**：Folia 200 个任务 case 覆盖 Paper 全部 198 个——补全 inventory 家族、GUI / BossBar / Scoreboard / Recipe / Advancement 五大块、区块快照（`chunk.getSnapshot`）、世界音效/粒子等；修复 `chunk.` 前缀区块坐标路由（此前会被当作世界坐标 >>4 错路由）
- **事件覆盖全对齐**：41 个 Bukkit 事件 + `permissionCheck` 生态钩子（补全 playerMove、blockPlace、entityDamage、serverPing 等 33 个）；javap 确认此 Folia build 的事件继承链差异并适配（PlayerTeleportEvent extends PlayerMoveEvent、ProjectileLaunchEvent extends EntitySpawnEvent、BlockSpreadEvent extends BlockFormEvent extends BlockGrowEvent、EntityExplodeEvent 与 BlockExplodeEvent 为独立类）
- **权限系统对齐 Paper**：新增 `FoliaPermissionRegistry`（权限节点幂等注册 + default 记录）；`permissionCheck` 生态钩子接线（命令执行检查 + `player.hasPermission`），事件携带 `permission: {node, default}` 对象；修复 Paper 同款 `_eventId` 缺失隐藏 bug（此前 permissionCheck handler 的返回永远超时 5s 不生效）

### Folia：实机验证修复（Yeow-Test/folia 双区域基准）

- **实体解析修复**：Folia 的 `Bukkit.getEntity(uuid)` 不含在线玩家——实体/玩家目标解析回退 `getPlayer(UUID)`（此前玩家在线却报 "entity not found"）
- **全局状态写入自动路由**：`world.setTime / setStorm / setThundering / setDifficulty / setSpawnLocation / setGameRule` 在 Folia 上只能在全局 region 线程修改（AsyncCatcher 拦截）——运行时自动将这些任务路由到全局线程，插件无感知
- **Scoreboard 限制**：Folia 不支持创建计分板对象（`registerNewObjective`/`registerNewTeam` 全部重载抛 `UnsupportedOperationException`）——`createObjective`/`createTeam` 返回明确错误 `{err: "Folia does not support creating new objectives/teams"}`（已存在对象则更新 displayName）；仅支持读取与修改已存在对象（`setScore`、`setTeamPrefix` 等可用）

### Folia：调度器 v1 打磨与看门狗（已提交）

- **v1 打磨**（`195c51e`）：事件取件回归纯 L（只取本插件任务，天然分流）；`runCycleOn` 的 world 目标免全局线程跳转；GLOBAL 任务在全局线程就地执行（消除双跳）；预算尽重启改每 tick 定时任务（等待 ≈ 窗口剩余 ~30ms，毛刺从 ~50-80ms 降至 ~31-57ms）
- **看门狗 + 投递超时**（`8c717ff`）：cycle 启动投递被 region 调度器静默丢弃时强制重启（1s 活性阈值 + 防重启风暴）；在途投递超时由每任务定时器合并为周期任务粗粒度扫描（5s 兜底补 err 并回收 in-flight，防区域停摆时调度器永久停摆）

### PDC / ItemStack API 扩展

- **PDC**：  - `pdcGet`/`pdcSet` 自动 JSON 序列化/反序列化——任意对象/数字/布尔直接存取，无需手写 `JSON.stringify`（`getRaw`/`setRaw` 保留底层字符串读写）
  - 新增 `pdcGetAll`：一次取回本插件命名空间全部键值
  - **裸 key 默认命名空间由 `yeow` 改为插件名**——不同插件的裸 key（如 `score`）互不冲突；历史数据需用 `yeow:key` 显式访问迁移
  - `Player` / `Block`（需 location）新增实例方法：`setPdc` / `getPdc` / `hasPdc` / `removePdc` / `keysPdc` / `getAllPdc`
- **ItemStack**：
  - meta 扩展：`damage`（耐久损伤）、`color`（`#RRGGBB` 或 rgb——皮革盔甲染色/自定义药水颜色）、`potionEffects`（自定义药水效果）、`skullOwner`（玩家名 / UUID / base64 纹理头颅）、`attributeModifiers`（属性修饰符）；不支持的字段静默忽略（跨版本兼容）
  - 新增工具：`ItemStack.create` / `clone` / `equals`
  - `inventory.setItem` 现在接受**完整 ItemStack（含 meta）**（旧参数 `itemType`/`amount` 弃用；传 null 清空槽位）；`inventory.getItem` / `player.getItemInMainHand` 读回含 meta
  - 
### Inventory 统一重构

- **统一 Inventory 容器抽象**：玩家物品栏（`player.inventory`）、**容器方块**（`block.getInventory()`——Chest / Furnace / Hopper / Barrel / Dispenser / Dropper / BrewingStand 等，新增能力）、自定义 Inventory（原 GUI，`Inventory.create`）三种持有者共用同一套方法（`getItem` / `setItem` / `setItems` / `addItem` / `removeItem` / `clear` / `fill` / `getSize` / `getType`）
- **弃用 "GUI" 术语**：任务族统一为 `inventory.*`（原 `gui.*` 并入，三寻址：`uuid` / `world+x+y+z` / `id`）；事件字段 `guiId` → **`inventoryId`**；`GUI` 类 → `Inventory` 类（`GUI.create` → `Inventory.create`，方法名不变）；API 文档 `gui.md` 并入 `inventory.md`
- `addItem` 返回**未放入数量**（玩家溢出掉落，返回 0）；`inventory.getType` 返回 `PLAYER` / `CUSTOM` / 方块实体类型名

### 文档

- **新增"事件重入死锁"专节**（advanced/events.md）：事件处理中同步操作（含属性读写）触发新事件 → 嵌套自旋死锁至 5s 超时，**Paper 与 Folia 均存在**；警告除非逻辑非常简单否则事件内用异步 API；getting-started 同步引用
- 已知差异整理（folia.md）：Scoreboard 创建限制、全局状态路由、TPS 返回 null；文档站侧边栏补 "Folia 支持" 条目
- 域名迁移：`docs.yexin.wiki` → `yexin.wiki`（含运行时告警框的 HELP_URL）
- API / 规范文档全面同步（PDC / ItemStack / Inventory / 事件字段 / 任务规范）

### 运行时修复：8 项审计确认 Bug（`477052b`）

- **挂死插件强杀路径修复**：QuickJS 上下文**不再跨线程 destroy**（wrapper 的 `checkSameThread` 守卫使跨线程 destroy 恒为空操作、上下文从未释放；去掉守卫则 use-after-free）——改由 JS 线程自身销毁；热重载强杀后**重建全新实体**（新线程/新队列/新上下文），旧实体遗弃，杜绝"旧线程从共享队列偷消息"双线程并发；插件/Worker 的 JS 线程改 daemon（挂死不阻塞关服）
- **BudgetScaler 动态扩容真正生效**：调度器此前始终读固定 `tick-budget-ms`，扩容（×1.3~×3）只计算未应用——`drainRound`/`mainTickPump` 改读扩容后预算
- **同步任务超时"幽灵执行"修复**：超时后任务仍留在主线程队列稍后执行（调用方已报错、副作用仍发生）——Paper 超时按引用移除未执行任务；Folia 投递闭包返回取消动作（捕获 `ScheduledTask`），超时回收先 `cancel()` 再补 err
- **block 级 PDC 写入不持久化修复**（Paper + Folia）：`TileState` 快照写 PDC 后不调 `update()` 不写回世界——补 `update()`
- **assets.extractDir zip-slip 防护**：entry 相对路径含 `../` 不得逃逸目标目录；extract 路径补运行时目录写保护（`plugins/Yeow/runtime`）
- **fs 读操作副作用修复**：`readFile`/`exists`/`list` 等不再静默创建父目录
- **HTTP 未响应请求泄漏修复**：JS 侧 30s 不 `respond` 的连接自动 503 关闭（周期清扫）
- **init.js 回调注册泄漏修复**：一次性回调改为调用前注销（handler 同步抛错不再泄漏注册项）
- dev 模式 `assets.read` 路径逃逸防护；EventBridge/checkPermission 投递失败时 pend 注册表清理

### quickjs-wrapper 3.9.0：`QuickJSContext.interrupt()`（原生中断）

- **跨线程安全强制中止 JS 执行**：经 `JS_SetInterruptHandler` 在**执行线程自身**周期性检查中断标志，返回非 0 即中止当前 `evaluate`/`call`（一次性语义，自清标志）——让挂死死循环的插件线程"杀得掉"且上下文由其自身销毁（配合上方强杀路径修复）；四平台原生库经 CI 构建发布（主仓库 `v3.9.0`）

### 任务执行器审计修复：30 项（`bb4ddcd`）

- **Folia 与 Paper 行为对齐**（此前按家族复制实现时的契约漂移）：
  - 缺失实体语义：`entity.get`/`player.get`/`player.isOnline` 返回 `null`/`false`（此前 Folia dispatch 层一律报错）
  - 执行体实体解析回退玩家表（Folia `getEntity` 不含在线玩家）；`pdcHolder` 玩家回退——**Folia 玩家 PDC 全家族恢复**；`world.getBlock` 补 `x/y/z/world` 字段；`server.getMaterials` 改对象数组（此前与 getItems 相同）；`entity.teleport` 保留 yaw/pitch；`getCustomName` 空串；药水 `ambient` 默认 true + `icon` 参数；`getActivePotionEffects` 返回小写全字段结构；手持物品读回完整 meta；`world.spawnItem` 接受 item 对象；`server.getTps` 显式返回 null 字段（此前 Gson 丢弃成 `{}`）
  - inventory `close`/`destroy` 查看者关闭改按玩家 region 调度（此前 GLOBAL 线程跨 region 违规）；BossBar/自定义 Inventory 接入 InstanceRegistry（JS GC 自动回收）；`advancement.*` 强制 GLOBAL 路由（全局注册表 AsyncCatcher 约束）
- **Paper 侧**：`hasPermission` 兼容字符串与 `{node}` 对象（此前字符串形式报错）；`sendTitle` 走 MiniMessage 组件管道；`stopSound` 注册表解析 + 未知音效报错（此前静默 `stopAllSounds` 误停全部）；`setGamemode`/难度大小写不敏感；`removeItem` 返回未移除数量（此前恒 true）；setGameRule 按 JSON 类型显式转换；setBlock 未知材质明确报错；ItemStack 序列化往返补齐（color/potionEffects/skullOwner/attributeModifiers/hideTooltip/itemFlags 读回）
- **yeow-api**：事件 `player` 字段适配补全（inventoryClick/advancementDone/toggleSneak/toggleFlight/resourcePackStatus 此前漏包成 Player 对象）；异步 completer 真正等待 Promise 结果（此前立即返回空补全）；`call()` 错误携带 type/task/Java 堆栈；`setBorder` 补 centerX/centerZ；`removeItem` 类型改 `number`
- 批量任务错误对象形状统一（`{err, type, task}`）

### 文档结构优化

- **拆分**：`getting-started.md` 拆出 [权限与原生服务可信性](permissions.md)（安全主题）与 [运行时运维](operations.md)（`/yeow` 命令 + config.yml）；`package-author.md` 拆出 [封装 Service 的依赖包](package-service.md)（三种类型）
- **瘦身去重**：`specifications/README.md` 的任务执行器/事件/命令细节改为链接（与 task/index.md、event/index.md 重复）；`cli.md` 性能分析节并入 runtime-warning.md
- 入口更新：README/overview 文档地图、站点侧边栏、sitemap 同步三个新页面

## 2026-08-12

### Folia：调度器脚手架 + 运行时拆分收尾（`396e536`）

- **Folia 平台运行时雏形**：非阻塞调度器 + 区域驻留（调度循环借宿 region 线程）、让出/抢占迁移（热点跟随）、空闲 park 阻塞等待、物理时间预算（50ms 窗口）、in-flight 上限（默认 100）+ 5s 投递超时兜底、严格 H→N→L 取件 + 提交时自动降级
- **三函数契约**：调度器对任务类型零认知——`ownedHere` / `getScheduler` / `execute` 家族共享实现，目标 key 解析收敛于 `TargetKey`（新增任务类型只需实现对应分支）
- 事件桥懒注册 + 多 handler eventId 精确匹配；SpinPump 统一事件/补全自旋样板
- 关键修复：事件多 handler 越界与 latch 错位、finishCycle 丢失唤醒、迁移机制持续负载下失效、in-flight 泄漏（region 无 retired 回调）、过期驻留权不清理、runDelayed 毫秒/tick 单位错误（1.5s 停顿）、B 路径抢占缺失、config.yml 首次落盘失效
- 配置分层：Folia 专用参数移入 `folia:` section；文档新增 advanced/folia.md（环境约束/调度模型/迁移机制）

## 2026-08-11

### 运行时架构：core / paper 双模块拆分（`c44f1f4`）

- `yeow-runtime` 拆分为 `jvm/core`（平台无关引擎：QuickJS、消息桥、插件生命周期、权限、Service、Profile）+ `jvm/paper`（Paper 平台实现）——`PlatformHost` 平台桥接口成为唯一耦合面，为 Folia 等新平台铺路

### 协议层：instance id 不透明句柄（`82d8cb0`）

- JS 句柄（GUI/BossBar/Inventory）id 改为**不透明句柄**（每上下文随机种子，无业务前缀）——修复跨插件 id 冲突；版本 yeow-api 0.2.117 / create-yeow 0.2.127

### 调度修复（`8b0fb44` 等）

- 命令补全与 `permissionCheck` 自旋改用 `scheduler.drainAll()`——补全结果任务不再被 tick 预算饿死
- `permissionCheck` 超时对齐事件配置（默认 5s）

## 2026-08-10

### 调度器：事件自旋无预算排空（`c3cbcdb` / `ab3071a`）

- 事件派发等待期间排空调度器队列**不再受 tick 预算限制**——高负载下事件 `event.complete` 不再被饿死（此前会触发 5s 超时）
- `drainAll` 重构为单循环一轮一任务（HIGH→NORMAL→LOW）——新鲜高优先级任务不被低优先级积压饿死

## 2026-08-09

### 权限系统

- **`permissionCheck` 事件**（`4d3c5b3`）：`player.hasPermission` 任务与 Yeow 命令执行检查会触发该事件，handler 返回 `{allowed}` 决定结果——**覆盖 Bukkit 权限系统**，无处理时回退；仅限 Yeow 生态（不拦截其他 Java 插件）；多 handler 以最后返回者为准
- **`registerPermission({node, default: 'all'|'op'|'none'})` API**（`c2bd859`）；命令注册接受权限对象，`permissionCheck` 事件携带 `permission: {node, default}`；节点仍注册进 Bukkit（permissions.yml / LuckPerms 可管理）
- 命令权限默认值由 `none` 改为 **`op`**（`62e76ae`）

### CommandSender 类型重构（`b8b68e5`）

- **破坏性变更**：命令执行器的 `sender` 现在是**真正的 `Player` 对象**（`isPlayer: true` 时）或字符串 `'CONSOLE'`——`p.sender === 'CONSOLE'` 判断后即可直接用 `p.sender` 的 Player 方法（`sendMessage` 等）

### Java 插件集成 API（`8cc848d`）

- 其他 Java 插件可调用 Yeow 插件注册的服务：`requestService`（请求-响应）、`subscribeService`（订阅事件）——`depend: [Yeow]` + `Bukkit.getPluginManager().getPlugin("Yeow")` 即可；文档新增 `specifications/java-api.md`

### dev-server AI / headless 模式（`34b3980` 等）

- `--eula`（自动接受 EULA）/ `--timeout` / `--wait` / `--outfile` / `--keep`（PID 输出、加载检测、自动退出）——供 AI 代理自动化验证；`--eula` 自动接受修复、UTF-8 输出编码、退出流程加固

### 文档与模板

- `advanced.md` 拆分为 `advanced/`（7 篇：架构/调度器/事件/生命周期/通道/服务/运维）
- about.md
- 模板命令注册示例：`sender` 先判 CONSOLE 再收窄为 Player

## 2026-08-08

### 文本与 Message 对象

- **Message 对象（可翻译组件）**（`ae9878f` 等）：`sendMessage` / `sendActionBar` / `broadcast` 支持 `{key, args}` 或 `{text}` 载荷——key 本地化 + text 纯文本兜底同时传递；`playerDeath.deathMessage` 直接为 Message 对象（跨版本读取修复：Paper `deathMessage()` Component 优先，回退 `getDeathMessage()`）；`playerAdvancementDone` 新增 `title` / `description` Message 字段
- **文本转义规则**（`7352ac0` 等）：`\n` 等字面反斜杠序列在文本管线中保留（不再被误转成换行）——只有真实控制字符 / MiniMessage 标签才生效；`\\n` 保持字面量；文档新增 Text & MiniMessage 章节

### Worker API（虚拟插件）（`1ea380c` 等）

- `createWorker` 创建独立线程 + 独立 QuickJS 上下文的执行单元：`load` / `unload` / `reload`、双向 `postMessage`；共享主插件数据目录与权限；**不能销毁只能卸载**（句柄保留可重新 load）；禁嵌套 Worker
- 接入全链路：调度器（独立队列统计/purge）、事件/命令/Service 以注册名登记、Profile 标记虚拟插件、`/yeow` 管理命令不覆盖
- 构建：`yeow.config.json` 的 `dev.worker` 声明 Worker 打包（先打包 Worker 再打包主插件）；dev 热重载 + source-map 错误按 origin 定位

### HTTP / yeow-utils

- **http 响应支持 `bodyBase64`**（`a449efc`）：二进制响应（资源包等）无需 base64 中转
- **createServer**（yeow-utils）：对象返回值自动 JSON 序列化（`738b1fa`）；洋葱模型中间件 `use`/`next` + 静态文件 `mount(dir, prefix)`（路径穿越防护 + MIME 推断）（`4336069`）；`mountAssets` 直接从 zip 服务打包资源（`dd1599c`）；类型化二进制响应（`8b380b9`）
- **破坏性修复**：异步 fs/assets/http 回调现在投递**对象**而非 JSON 字符串（`8ad0958`）——与同步调用 `JSON.parse` 语义一致

### 其他 API

- `Player.performCommand`：以玩家身份执行命令（`ab39d73`）
- `sendResourcePack` 的 `prompt` 接受 Message 对象（`eace762`）
- **`env` 通道 + `getEnv()`**（`796b636` / `85b000a`）：cpus / memory / arch / minecraftVersion / Yeow 版本 / epoch 微秒时间戳（移除原 `now` 通道）

### 文档站与 AI 工作流

- **站点地图**（`sitemap.md`，AI / Vibe-Coding 友好：全部页面标题 + 摘要 + URL）（`7e638cd`）
- **docs.zip**：构建时产出全量 Markdown 压缩包（`/v1/docs.zip`），模板内置 sitemap.md + AGENTS.md 供 AI 代理查阅（`b02c807`）
- **AI 代理启动指南**（`/ai-agent`）+ 强烈推荐 TypeScript（AI 辅助编码场景）（`69264cf` / `a3307ea`）
- ItemStack 文档章节（纯数据快照语义）+ 侧边栏分组（`f9e4d48`）
