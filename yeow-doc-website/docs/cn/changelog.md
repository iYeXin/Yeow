# 更新日志

> 从 2026-08-08 开始记录。**简短版本**：每日条目一览。详细版本见仓库根目录 `changelog.md`。

---

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
