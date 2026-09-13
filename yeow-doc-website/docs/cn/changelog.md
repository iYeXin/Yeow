# 更新日志

> 从 2026-08-08 开始记录。**简短版本**：每日条目一览。详细版本见仓库根目录 `changelog.md`。

---

## 2026-09-13

- **yeow-runtime 0.6.1**：强制终止鲁棒性——`QuickJSContext` 线程亲和守卫（跨线程调用除 `interrupt()` 外抛错，避免 use-after-free/崩溃）；原生中断不可捕获（`catch`/`finally` 吞不掉），无法终止时遗弃+隔离引擎（`postMessage`/`ping` 丢弃、其 `$send` 立即触发不可捕获中止）并重建实体（**遗弃为临时**：卡住调用返回/检查点触发即自毁回收，仅永不返回者泄漏）；作为**平台通用行为**写入运行时环境标准（新增「卸载与强制终止」节：两条强制要求——**顺序与受控终止**、**优先优雅卸载**——另附**示例流程**，插件与 Worker 同适用），`runtime-warning` / `advanced/lifecycle` 链接引用

## 2026-09-12

- quickjs-wrapper 重写为 Yeow 专用桥：原生层 C + **Zig 0.16** 单工具链构建（六平台交叉编译），Java 包名 `wiki.yexin.quickjs`，仅保留 evaluate / 全局函数上下行 / job 泵 / 中断；Maven 组 `io.yeow` → `wiki.yexin`，新构件 `wiki.yexin:yeow-quickjs`
- 新增**原生 polyfill**（`quickjs-wrapper/native/polyfill/`，C，随上下文创建注入，不改 QuickJS 源码）：`performance.now()`（零点为**上下文创建**时刻）/ `performance.timeOrigin`（单调高精度时间）与原生 UTF-8 版 `TextEncoder` / `TextDecoder`；yeow-api `global.d.ts` 声明 `performance`
- JS↔Java 链路清理（仅 JSON 路径）：`$hm` 绑定句柄、微任务泵合并为单次 JNI `drainJobs()`、异步回调 `cbMessageRaw` 直接拼装结果 JSON、批量同步结果直接拼接数组、字符串 ASCII/BMP 快路径
- **原生服务强制声明**：`yeow.config.json` 必须声明 `native`（构建时校验：有 `service:registerNative` 权限但清单为空 / 文件缺失 → 构建失败；加载时为空 → 拒绝加载）；`registerNativeService` 校验 serviceId、二进制路径、SHA-256，缺一即拒绝；**移除目录模式**（仅单文件）；`native-service-allow-untrusted` 保留（声明 ≠ 可信）
- **统一权限门控 + Worker 权限/销毁**：主插件与 Worker 共用 `PermissionGate`（所有消息节点受控、`task:*` 默认拥有、deny 优先、allow 不可提权）；`createWorker({ permissions: { allow?, deny? } })`；新增 `worker.destroy()`
- 版本 0.5.3 → 0.6.0（runtime core/paper/folia、yeow-template、create-yeow、quickjs）；模板内置 jar 同步为 `yeow-runtime-0.6.0.jar` / `yeow-runtime-folia-0.6.0.jar` / `yeow-template-0.6.0.jar`；yeow-api 0.5.0 → 0.6.0
- **原生服务协议 v2（破坏性）**：TCP 由 JSON line 改为帧协议（header JSON + raw/分块 body），支持原始二进制与流式；`serviceRequest` 返回 fetch 风格 `Response`（`json()`/`bytes()` 等）；事件语义不变；顺带修复出站帧并发交错、shutdown 缺帧、无大小上限
- **service API 改 OOP（破坏性）**：注册返回 `PluginService`/`NativeService` 句柄，新增 `getService`/`hasService`（含非原子性警告）与 `unregister`；`Service` 基类含 `request`/`subscribe`，`PluginService` 含 `token`/`publish`，`NativeService` 含 `ready`/`onTerminate`；运行时 `service` 通道新增 `info`/`unregister`，`request`/`response` 支持 `headers`、请求支持超时；移除函数式 `serviceRequest`/`serviceSubscribe`/`servicePublish`
- **文档**：service 文档重构为三种场景（插件自身提供 / 依赖包纯调用 / 依赖包内嵌服务），自包含设计移至最复杂场景
- **移除实验性集成能力**：删除「插件适配器规范」与「Java 插件集成」（`requestService`/`subscribeService`）文档、相关代码（`requestJava`/`subscribeJava`、`registerPluginEntity(PluginEntity)` 重载）与站点导航；未来可能并入主线
- **插件包缓存阈值**：新增 `assets.cache-max-bytes`（默认 30 MiB），超过不再缓存到内存（回退 ZipFile 直读）；`<=0` 不限制
- **`/yeow load` 增强**：路径找不到时回退 `plugins/Yeow/<path>` 与 `plugins/Yeow/<name>-<version>.yeow.zip`（忽略大小写、精确优先、多版本取最新）
- CI 重写：Zig 构建 + 冒烟测试，`v*` 标签自动发布 `yeow-quickjs.jar`

## 2026-09-06

- yeow-runtime 0.5.2 → 0.5.3：资源内存缓存（加载时预解析，默认启用）+ `__plugin.version/author` 修复 + 移除原生动态审批改配置开关（默认允许并警告）+ 配置缺失字段自动合并写回；yeow-template / create-yeow 同步至 0.5.3

## 2026-08-26

- 文档：`setMotd` 会写入 `server.properties` 持久化——动态 MOTD 请用 `serverPing` 事件回写（不落盘）
- yeow-runtime 0.5.1 → 0.5.2：挂起自恢复（30s告警/120s自动重载，默认启用，冷却300s/最大3次）+ BudgetScaler 编码修复；yeow-template 同步至 0.5.2

## 2026-08-21

- yeow-runtime 0.5.0 → 0.5.1：修复 Adventure 4.20+ TranslatableComponent.args() NoSuchMethodError（Paper 26.2 兼容）
- yeow-template 0.5.0 → 0.5.1：依赖 runtime 0.5.1
- 模板 jar 全量同步至 0.5.1

## 2026-08-20

- 版本 0.5.0：yeow-api / create-yeow / yeow-runtime / yeow-template 全量升版（BossBar/Scoreboard OOP + 消除 uuid 摩擦的破坏性发布；模板依赖 yeow-api ^0.5.0）
- API 重构：BossBar / Scoreboard 改 OOP；消除「必须传 uuid」摩擦（potion/advancement/sound 上移为实例方法，目标参数接受对象）
- 值域附录结构调整：方块状态直接维护 + 新增参考实现部分
- 方块状态 state 保留类型（数字/布尔）
- 方块状态：新增常用键值规范表
- 新增 `Material.getMaxDurability`（及同步版）
- 文档整理：API 索引重写与 ⭐ 标记重订 / 任务·事件统计核对 / 去日期标注 / 值域引用 / http-server 删例
- 开发模式热重载允许重新加载权限（修改 `permissions` 后热重载即生效）

## 2026-08-19

- fetch `arrayBuffer` + init.js 拆分为 polyfill.js（TextEncoder / TextDecoder）
- util 命名约定：默认异步、Sync 后缀 + 全局 TextEncoder/TextDecoder
- 游戏规则值域：出参驼峰 + 入参真正宽松

## 2026-08-18

- 新增 `world.isChunkGenerated`
- 移除 `Player#setBorder`（客户端边界；保留 `world.setBorder*`）
- assets 通道：移除权限拦截 + dest 强制限定插件目录

## 2026-08-17

- 版本升级 0.4.2 / 0.4.3 / 0.4.4（yeow-api / create-yeow）
- http.request 响应体 Uint8Array 化 + fetch 按需解码（responseEncoding、body 二进制、移除 requestSync）
- `$_send` 闭包化：外部仅可用 `$send`
- 新增 debug:payload（载荷回显）
- dev-server：移除代理能力
- 修复 getItemInMainHand NPE（attributeModifiers 为 null）
- 修复并发事件模式字段回写失效（deathMessage 等）
- Player 继承 LivingEntity / Entity（setVelocity 等可用）

## 2026-08-16

- yeow-fflate 0.3.1：ZipReader
- 版本升级（0.4.1 / 0.1.2）
- 新增 `player.sendBlockChange`（客户端假方块）
- 一致性修复（整体通读审查）
- http-server respond：body + encoding（移除 bodyBase64）
- yeow-server：mount / mountAssets 目录请求尝试 index.html
- fs.list 返回条目名（不再泄漏绝对路径）

## 2026-08-15

- 值域附录结构调整：平台枚举直接维护 + 版本变迁域规则化
- 值域格式统一（R1-R5 框架）：药水/粒子/属性键化 + 附录补全
- 协议/API 一致性修订（P1-P4）：实体类型键化、枚举小写、物品/坐标统一
- dev 模式栈追踪修复：未处理 rejection 也能还原完整异步链（P1-P6）
- init.js 修复：interval 实参 / GC 冲刷 / fetch 悬挂 / 双份日志
- dir 通道并入 env（pluginDir）+ log 通道等级支持
- 事件处理器抛错不再悬挂事件桥（event.complete finally 释放）
- 移除 dedupe 插件：yeow-api 多副本可安全共存
- fs API：二进制优先（Node 风格编码）+ 移除 Base64 专用 API
- assets.read 合并：默认 Uint8Array + 显式编码
- 版本升级（0.4.0）
- 流式 API：文件流 + 分块 gzip；util 上限可配置；http 回调修复
- yeow-utils 拆分为 yeow-command + yeow-server（独立 npm 包）
- 版本升级（0.3.10 / 0.1.30）

## 2026-08-14

- 三项修复：dev-server 中文乱码 / 事件 player 零往返构造 / 日志前缀对齐
- PlayerDeath 幽灵触发：根因已定位并修复
- 任务内触发事件 → 5s 死锁（Paper 调度器）修复
- Paper 调度器恢复拆分前线程模型（setBlock 吞吐回归）
- 新增 util 通道（gzip + UTF-8 ↔ 字节转换）
- Profiler：虚拟插件（Worker）默认不告警心跳超时
- CommandBuilder 重载匹配校验 enum 值
- path 模块兼容 Windows 反斜杠路径

## 2026-08-13

- 回调系统跨代串扰修复（PlayerDeath 幽灵触发根因）
- yeow-utils Command API 支持 Permission 对象
- 版本升级（0.3.2 / 0.1.23）
- Timer 通道修复（三项）
- 事件回写字段扩展（常用稳定字段）+ Folia 对齐 Paper
- 事件回写机制扩展（三种方式）与死亡消息回写
- Folia 调度器加固（幽灵执行封堵 / LOW 饿死保护 / 卸载清理）
- 版本升级（0.3.0 发布）
- API 覆盖扩充（Entity / WorldBorder / Tab / 批量任务 / Inventory 内容物）
- Folia：任务 / 事件 / 权限与 Paper 全对齐
- Folia：实机验证修复
- Folia：调度器 v1 打磨与看门狗
- PDC / ItemStack API 扩展
- Inventory 统一重构
- 文档（事件重入死锁专节等）
- 运行时修复：8 项审计确认 Bug
- quickjs-wrapper 3.9.0：`QuickJSContext.interrupt()`
- 任务执行器审计修复：30 项
- 文档结构优化

## 2026-08-12

- Folia：调度器脚手架 + 运行时拆分收尾

## 2026-08-11

- 运行时架构：core / paper 双模块拆分
- 协议层：instance id 不透明句柄
- 调度修复

## 2026-08-10

- 调度器：事件自旋无预算排空

## 2026-08-09

- 权限系统（permissionCheck / registerPermission）
- CommandSender 类型重构
- Java 插件集成 API
- dev-server AI / headless 模式
- 文档与模板

## 2026-08-08

- 文本与 Message 对象
- Worker API（虚拟插件）
- HTTP / yeow-utils
- 其他 API
- 文档站与 AI 工作流
