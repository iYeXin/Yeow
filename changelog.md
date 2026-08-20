# 更新日志

> 从 2026-08-08 开始记录。一天内的多次更新合并为一节。

---

## 2026-08-20

### 版本 0.5.0（破坏性重构后首次大版本）

- **yeow-api 0.4.4 → 0.5.0** / **create-yeow 0.4.4 → 0.5.0**：BossBar / Scoreboard OOP 重构 + 消除「必须传 uuid」摩擦（见上方 API 重构小节）——破坏性，不再向后兼容
- **yeow-runtime 0.2.0 → 0.5.0**（Maven core/paper/folia）：运行时版本随 `plugin.yml` 提升——`yeow-runtime-0.5.0.jar` / `yeow-runtime-folia-0.5.0.jar`
- **yeow-template 0.1.0 → 0.5.0**（依赖 runtime 0.5.0）：`yeow-template-0.5.0.jar`
- 模板：`create-yeow` 内置两个 jar 同步至 0.5.0；模板依赖 `yeow-api ^0.4.0` → `^0.5.0`（0.x caret 锁次版本，必同步）；build.js / dev-server.js 引用更新
- 关联包适配：`yeow-server` / `yeow-command` / `yeow-fflate` peer `yeow-api` 上调至 `^0.5.0`（自身版本不升）
- 文档：README / overview / getting-started / folia / env / specs 中 jar 名与运行时版本示例同步；AGENTS.md / CONTRIBUTING.md 构建与复制路径更新
- 验证：`tsc --noEmit`（yeow-api）；`mvn install`（core+paper+folia）与 yeow-template 编译通过；jar 已同步模板

### API 重构：BossBar / Scoreboard 改 OOP + 消除「必须传 uuid」摩擦

- **BossBar → 对象式**：`BossBar.create(title, options?)` 返回 `BossBar` 对象（内部承载句柄），方法直接调用（`setTitle`/`setProgress`/`setColor`/`setStyle`/`setVisible` + 属性糖 `bar.progress = 0.5`、`addPlayer(player)`、`removeAllPlayers`、`addFlag`/`removeFlag`、`destroy`，均含 Sync 变体）；事件比对用 `bar.toString()`。**移除 12 个函数式导出**（`createBossBar`/`setBossBar*`/`addBossBar*` 等）
- **Scoreboard → 对象式**：`Scoreboard.main()` / `Scoreboard.create(id)`，`Objective` / `Team` 句柄（`board.createObjective`/`getObjectives`/`createTeam`/`getTeam`/`getTeams`/`attach`；`obj.setDisplay`/`setScore`/`getScore`/`resetScore`/`delete`；`team.setPrefix`/`setSuffix`/`setColor`/`setFriendlyFire`/`setOption`/`add`/`remove`/`getEntries`/`delete`）。**移除 20 个函数式导出**（`createScoreboard`…`setPlayerBoard`）；board 参数不再反复穿透（对象自带上下文）；`setPlayerBoard(uuid, board)` → `board.attach(player)`；`createTeam` 创建后回查返回 Team 句柄
- **消除「必须传 uuid」摩擦**：新增统一目标类型 `PlayerTarget` / `LivingTarget` / `EntityTarget`（对象或字符串；`resolveUuid` 解析）——玩家/实体目标参数一律接受对象；同时**上移为实例方法并移除顶层 uuid 函数**：
  - 药水：`LivingEntity.addPotionEffect` / `removePotionEffect` / `clearPotionEffects` / `getActivePotionEffects`（+Sync）——**Player 经继承自动获得**；新增 `LivingEntity.get(uuid)`
  - 进度：`Player.grantAdvancement` / `revokeAdvancement` / `getAdvancementProgress` / `awardCriteria` / `revokeCriteria`（+Sync）
  - 音效：`Player.stopSound` / `stopAllSounds`（+Sync，与 `playSound` 对称）
  - `Inventory.open` / `closePlayer`、`BossBar.addPlayer` / `removePlayer`、`Scoreboard.attach` 接受 `Player | string`
- **协议不变**：任务节点 / 线缆字段 / Java 侧零改动——纯 yeow-api 封装层重构
- 破坏性：移除顶层函数式导出（BossBar 12 / Scoreboard 20 / potion 4 / advancement 5 / sound 2）——不兼容上一版本
- 文档同步：api/bossbar、api/scoreboard、api/potion、api/advancement、api/player（进度/音效节）、api/entity、api/inventory、advanced/folia
- 验证：`tsc --noEmit`（yeow-api）通过

### 值域附录结构调整：方块状态直接维护 + 新增参考实现部分

- **方块状态 `state` 格式归入第二节**（键小写、值保留类型数字/布尔/字符串、入宽出严），**键名整体移至版本变迁节**（很多键只适用特殊方块/物品，不承诺稳定）——但明确**常用键名（如 `axis`、`facing`、`waterlogged`、`level`）及对应值规范必须稳定**，键名参考清单见第四节
- **`playerInteract.action` / `playerResourcePackStatus.status` 纳入直接维护**（R5 大写枚举）：此前无规范条目
- **新增「三、参考实现（非规范、不强制）」部分**：`entityDamage.cause`（DamageCause）/ `playerTeleport.cause`（TeleportCause）/ `entityRegainHealth.reason`（RegainReason）——**不强制实现、不保证值稳定**，仅列常见参考值；原第二节中 DamageCause 移出直接维护清单
- 值域总表、sitemap ×2、specifications/README 摘要同步（DamageCause 移出"直接维护"表述）
- 纯文档变更，无代码/产物改动

### 方块状态 state 保留类型（数字/布尔）

- **`world.getBlock` 的 `state` 值保留类型**（破坏性：此前全部为字符串）：`true`/`false` → 布尔、纯数字字面量 → 数字、其余 → 字符串——两侧（Paper `WorldTasks` + Folia `FoliaTasks` getBlock）经 `stateValue` 推断类型输出（`waterlogged: false`、`level: 8`）
- **`setBlock` / `sendBlockChange` 入参保留类型**：`state` 值传数字/布尔/字符串均可，按字面量原样写入 `type[键=值,...]`（`getAsString()` 天然序列化布尔/数字字面量）
- **API 类型**：`yeow-api` `BlockState` 值类型 `string` → `string | number | boolean`（`Block.of` / `world.getBlock` / `matches` / `withState` 贯通）
- 文档：values.md（方块状态条目：保留类型、入宽出严）、specifications/task/world.md、specifications/task/player.md、api/block.md
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

### 方块状态：新增常用键值规范表

- 在版本变迁节「方块状态（`state` 键名）」补**常用键值规范表**（正规、保证稳定）：布尔键（`waterlogged`/`lit`/`powered`/`open`/`snowy`/`hanging`/`enabled`/`attached`/`natural`）、`axis`（x/y/z）、`facing`（六向/四向）、`half`（top/bottom）、`face`（floor/wall/ceiling）、`type`（随方块）、整数键（`level`/`age`/`rotation` 及上限说明）、连接键（`north`/`south`/`east`/`west`/`up`/`down` 布尔/墙三态）
- 其余特殊方块专有键（`vault_state`/`trial_spawner_state` 等）仍按版本变迁、以 Wiki 为准
- 纯文档变更，无代码/产物改动

### 新增 Material.getMaxDurability

- **`Material.getMaxDurability(type)` / `getMaxDurabilitySync`**：查询材料最大耐久（工具/盔甲等的耐久上限；非耐用品返回 `0`；未知类型返回错误）；任务 `material.getMaxDurability`，Paper `MaterialTasks` + Folia `FoliaTasks` 两侧实现
- 文档：api/material.md、specifications/task/world.md；统计联动：server 模块 12→13、合计 224→225

### 文档整理：API 索引 / 统计核对 / 去日期 / 值域引用 / http-server 删例

- **API 索引重写**（api/README.md）并重订 ⭐ 常用标记：移除 `Service` / `Worker` / `Assets` / `Log` / `Text`，补入 `Block` / `Material` / `Inventory` / `PDC`；`Service` 标注「较进阶，多数插件用不到」
- **任务数量统计核对**：`world` 模块 48→49（遗漏 `world.isChunkGenerated`）；总量随 `getMaxDurability` 更新为 225；**事件统计核对无问题**（player 21 / entity 6+2 / block 6 / inventory 3 / server 3，含 permissionCheck 共 42）
- **删除 API 文档 7 处日期标注**（如「Tab 列表（2026-08-13）」、「基础操作（2026-08-13）」等）
- **API 文档 11 处补充值域引用**：material/potion/particle/item/entity/world/player/scoreboard/bossbar/inventory/event 在取值域位置引用 [值域附录](specifications/values.md) 对应章节
- **移除 api/http-server.md**「典型场景：从 `assets` 读取资源包等二进制文件并暴露下载 URL。」及下方示例（完整闭环小节同步去除「见上例」引用）
- 验证：`tsc --noEmit`（yeow-api）通过；`mvn package`（含测试）通过

## 2026-08-19

### fetch arrayBuffer + init.js 拆分为 polyfill.js（TextEncoder/TextDecoder）

- **新增 `response.arrayBuffer()`**（标准 `ArrayBuffer`）；`bytes` / `base64()` / `text()` / `json()` 不变
- **init.js 拆分为 `polyfill.js` + `init.js`**（Java 端顺序拼接、先执行 polyfill）：`polyfill.js` 承载纯 JS 全局——`TextEncoder` / `TextDecoder`（utf-8）与 `fetch`
- **TextEncoder/TextDecoder utf-8**：≤100 字节且 ≤50 字符载荷纯 JS 直转（零往返）；超阈值（字节 >100 或字符 >50）经 `util` 通道 `encode.utf8` / `decode.utf8`——非法序列替换 `U+FFFD`；其他编码抛 `RangeError`
- **fetch 复用 TextDecoder**：`text()` / `json()` 经 `TextDecoder` 解码（不再无条件走 util）
- 规范：`specifications/runtime/index.md` 新增 `TextEncoder` / `TextDecoder` 要求（实现要求存在、utf-8；内部实现不作要求）；fetch Response 增 `bytes()`（保留）+ `arrayBuffer()`（新增）
- 文档：api/http.md（fetch 节）、新增**环境能力**文档（`environment.md`：全局能力 / 线程与异步 / 与浏览器、Node 差异 / 性能建议，侧边栏 + sitemap ×2 登记）
- 验证：init.js/polyfill.js 冒烟测试（encode/decode 阈值、bytes/arrayBuffer）通过；`mvn package` 通过

### util：stringToBytes/bytesToString 命名约定 + TextEncoder/TextDecoder

- **命名约定改为“默认异步，同步加 `Sync` 后缀”**（破坏性）：`stringToBytes(text)` / `bytesToString(bytes)` 现在为**异步**（原 `*Async` 合并为其默认）；`stringToBytesSync` / `bytesToStringSync` 为同步（原无后缀版本）。`core.ts` 导出同步更新
- 调用方：`fs.ts` 同步解码/编码改用 `*Sync`；`yeow-fflate` 后端同步 UTF-8 直接改用运行时全局 `TextEncoder`/`TextDecoder`
- **`TextEncoder` / `TextDecoder` 全局类型加入 yeow-api `global.d.ts`**（含 `YeowResponse.bytes()` / `arrayBuffer()` 补全）
- 文档：api/util.md（节名 + 关系说明：**同步编解码优先 TextEncoder/TextDecoder，性能最好**；大规模非阻塞用异步 `stringToBytes`/`bytesToString`）
- 验证：`tsc --noEmit`（yeow-api + yeow-fflate）通过

### 游戏规则值域：出参驼峰 + 入参真正宽松（修复文档/实现不一致）

- **`world.getGameRules` 出参严格转驼峰**（R3 值域规范）：Bukkit `getGameRules()` 原生返回 `UPPER_SNAKE`（`DO_DAYLIGHT_CYCLE`/`KEEP_INVENTORY`）——此前执行器直接透传，与规范「出参 R3 驼峰、大小写不敏感」矛盾；现两侧（Paper `WorldTasks` + Folia `FoliaTasks`）经 `UPPER_SNAKE → camelCase` 转换输出（`doDaylightCycle`/`keepInventory`）
- **入参真正宽松（修复驼峰解析失败）**：此前 `getGameRule`/`setGameRule` 用 `rule.toUpperCase()` 匹配 Bukkit 字段（`KEEP_INVENTORY`）——驼峰 `keepInventory` 会变成 `KEEPINVENTORY`（缺下划线）→ 查不到返回 null，**规范声明的驼峰入参实际解析不了**。现改为「去下划线 + 小写」归一化映射，`keepInventory` / `KEEP_INVENTORY` / `Do_Daylight_Cycle` 均正确解析（两侧一致）
- 文档修正：api/world.md（原先错误标注「规则名使用大写下划线格式如 DO_DAYLIGHT_CYCLE」→ 改为 R3 驼峰、入宽出严）
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

## 2026-08-18

### 新增 world.isChunkGenerated

- **`World#isChunkGenerated(x, z)`**：查询区块是否已生成（未加载/未生成返回 `false`）；任务 `world.isChunkGenerated`，两侧（Paper `WorldTasks` + Folia `FoliaTasks`，含 Folia 区块坐标路由）均有实现；API 层含 `isChunkGeneratedSync`
- 文档：specifications/task/world.md、api/world.md
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

### 移除 Player#setBorder（玩家侧客户端边界）

- **移除 `Player.prototype.setBorder` / `setBorderSync`** 及任务 `player.setBorder`（Paper `PlayerTasks` + Folia `FoliaTasks`）——客户端个性化边界不再提供（破坏性：此前可用）
- 保留**服务端**世界边界 `world.setBorder*`（`setBorderCenter`/`Size`/`Damage`/`Warning`/`Moving`，对全体玩家生效）
- 文档同步：api/player.md、specifications/task/player.md、api/world.md、specifications/task/world.md（交叉引用更新为"已移除"）
- 验证：`tsc --noEmit`（yeow-api）通过；`mvn package` 通过

### assets 通道：移除权限拦截 + dest 强制限定插件目录

- **assets 通道不再做权限拦截**：移除 `assets:extract` / `assets:extractDir` 权限节点（`DEFAULT_DENIED_NODES` 一并移除，Worker 委托同样跳过 assets 权限检查）——只读打包资源、或解压到本插件数据目录，无需声明权限
- **解压目标强制限定在插件数据目录 `plugins/<插件名>/` 内**：新增 `assetsTarget` 解析（`resolvePath` 越界检查 + 运行时目录保护），所有 `extract` / `extractDir` 目标统一走它，越界返回错误
- **`extract` 必须指定 `dest`**（缺省返回 `extract requires dest`）；`extractDir` 的 `dest` 仍可选（默认 `assets/<path>`）——`dest` 均基于插件数据目录计算
- API 层（`assets.ts`）：`extract(path, dest)` / `extractSync(path, dest)` 的 `dest` 改为必填
- 文档同步：api/assets.md、specifications/message/assets.md、permissions.md、specifications/README.md（权限模型）、runtime/index.md（敏感节点表）、package-author.md、distribution.md、README.md
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

## 2026-08-17

### 版本升级（0.4.2 / 0.1.3）

- **yeow-api 0.4.1 → 0.4.2** / **create-yeow 0.4.1 → 0.4.2** / **yeow-server 0.1.2 → 0.1.3**
- 内容：新增 `Player#sendBlockChange`（Block | string，与 `world.setBlock` 同语义）+ 整体通读一致性修复（Folia 事件桥字段补齐、`inventory.removeItem` 返回统一、`fs.delete` 递归删除、assets extract 相对路径、http body null、`newGameMode` 小写等）+ http.request/fetch 响应体 Uint8Array 化与按需解码、body 二进制化（见下方小节）
- 模板依赖：`yeow-api ^0.4.0`（caret 自动覆盖 0.4.2）；yeow-server peer `^0.4.0` 不变
- 运行时：模板内置 `yeow-runtime-0.2.0.jar` 已同步（sendBlockChange、Folia 桥字段、fs.delete 递归、assets extract 相对路径、http body null、newGameMode 小写、http timeout/{err}、请求/响应体二进制、fetch base64）

### 版本升级（0.4.3）

- **yeow-api 0.4.2 → 0.4.3** / **create-yeow 0.4.2 → 0.4.3**（yeow-server 自 0.1.2 起无内容变更，版本保持 0.1.3）
- 内容：http.request/fetch 响应体 Uint8Array 化与按需解码、body 二进制化、`responseEncoding`、`requestSync` 移除、`$_send` 闭包化、`debug:payload` 载荷回显（见下方小节）——0.4.2 已发布版本不含上述内容（其 http.ts 仍为旧实现）
- 模板依赖：`yeow-api ^0.4.0`（caret 自动覆盖 0.4.3）；yeow-server peer `^0.4.0` 不变
- 运行时：模板内置 `yeow-runtime-0.2.0.jar` 已同步（http timeout/{err}、请求/响应体二进制、fetch base64、`$_send` 闭包、debug:payload）

### 版本升级（0.4.4）

- **yeow-api 0.4.3 → 0.4.4** / **create-yeow 0.4.3 → 0.4.4**（yeow-server 自 0.1.2 起无内容变更）
- 内容：`Player extends LivingEntity extends Entity`（实体继承体系，`setVelocity` 等可用）+ 运行时修复（`getItemInMainHand` 的 attributeModifiers NPE、并发事件字段回写失效）+ dev-server 移除代理能力（见下方小节）——0.4.3 已发布版本不含上述内容（其 player.ts 仍为独立类、dev-server 仍含 --proxy）
- 模板依赖：`yeow-api ^0.4.0`（caret 自动覆盖 0.4.4）；yeow-server peer `^0.4.0` 不变
- 运行时：模板内置 `yeow-runtime-0.2.0.jar` 已同步（attributeModifiers NPE 修复、并发事件字段回写、dev-server 去代理）

### http.request 响应体 Uint8Array 化 + fetch 按需解码

- **`request` 响应体默认 `Uint8Array`**（原始字节）：`responseEncoding: 'utf8'` → 字符串、`'base64'` → base64 字符串；也可在收到后自行解码（`bytesToString` / `TextDecoder`）；新增 **`timeout` 选项**（毫秒，连接与读取；缺省运行时默认 5s/10s）——破坏性：旧 `body` 为字符串
- **`body` 请求体与 `fs.writeFile` 同语义**（`request` 与全局 `fetch` 均支持）：`string | Uint8Array`——`Uint8Array` 直接二进制（base64 承载）；字符串按 `encoding` 解释（缺省 UTF-8 文本；`'base64'` 视为 base64 二进制，解码后按字节写出）。响应体形态单独由 **`responseEncoding`** 控制（缺省 `Uint8Array`；`'utf8'` / `'base64'` 字符串）——`encoding` 只管请求体，无双作用。协议 `request` / `requestAsync` 的 `encoding`（请求体）与 `responseType`（响应）字段本就分离，无需改动
- **删除 `requestSync`**（破坏性）：API 层移除，api 文档不再提及；`http:request` 消息节点本体保留（协议同步语义不变，供适配器/内部使用）
- **全局 `fetch`**：响应底层始终以 **base64 缓存**原始字节（`responseType: 'base64'`），`text()` / `json()` 触发 UTF-8 解码（util 通道 `decode.utf8`，非法序列替换 U+FFFD），新增 `base64()` / `bytes()`；`fetch(url, { timeout })` 支持超时
- 协议：`http:request` / `requestAsync` 支持可选 `timeout` 字段；失败统一返回 `{ "err": ... }`（此前为 `{ "error": ... }`——顺带修复 yeow-api 旧异步请求错误被静默当成成功的潜在 bug）
- 文档同步：api/http.md（重写）、specifications/message/http.md（encoding/timeout/错误键）、runtime/index.md（fetch init 与 Response 结构）、advanced/events.md 与 EventSlowDetector 提示语、sitemap ×2（http 摘要 + 消息通道表补 util 页）、文档站侧边栏消息通道区补 Util
- 验证：`tsc --noEmit`（yeow-api）通过；init.js fetch 冒烟测试通过

### $_send 闭包化：外部仅可用 $send

- **`$_send` 从全局对象移除**：init.js 以闭包持有底层桥后立即 `delete globalThis.$_send`（属性不可删除的异常宿主回退为覆盖抛错桩）——插件代码（含依赖包）直接调用 `$_send` 得到 `ReferenceError`，只能使用封装后的 `$send`（自动 JSON 转换）
- 与规范一致：specifications/README 本就规定 `$send` 为插件唯一可依赖入口、`$_send` 属内部实现——本次为运行时实现落地该边界（Java 注入方式不变：仍于 `evaluate(init.js)` 前注入全局属性，由 init.js 接管移除）
- 文档同步：advanced/channels.md（注入表/封装层/示例）、specifications/runtime/index.md（$send 节补充实现边界）、specifications/message/worker.md（worker 通道经 `$send`）
- 验证：init.js 冒烟测试（含 `$_send` 移除断言）通过；`mvn package` 通过

### 新增 debug:payload（载荷回显）

- **`debug` 通道新增 `payload` 节点**：JS 可发送任意 JSON 载荷，插件线程解析后回复原载荷——用于基准测试 / 往返延迟测量；支持同步返回与 `cb` 异步回投两种形态；Worker 线程同样支持（本地处理）
- 文档：specifications/message/debug.md
- 验证：`mvn package`（含测试）通过

### dev-server：移除代理能力

- **删除 `--proxy=<url>` 参数**：dev-server 的 Paper 下载不再支持代理——`download()` 去掉 agent 参数、`ensurePaper` 不再构造 `HttpsProxyAgent`；模板 devDependencies 移除 `https-proxy-agent` 依赖
- 文档同步：cli.md（参数表）、sitemap ×2（CLI 摘要）

### 修复 getItemInMainHand NPE（attributeModifiers 为 null）

- **`serializeItem` 属性修饰序列化判空修复**：`ItemMeta.getAttributeModifiers()` 在物品无属性修饰时可能返回 `null`，此前 `!im.getAttributeModifiers().isEmpty()` 直接调用触发 NPE（`getItemInMainHand` 等）——改为捕获返回值并判空，两侧（Paper `InventoryTasks` + Folia `FoliaTasks`）一并修复
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

### 修复并发事件模式字段回写失效（deathMessage 等）

- **根因**：Paper `dispatchConcurrent`（并发事件，**默认开启**）此前只聚合回写 `cancelled`/`motd`/`icon`/`maxPlayers`/`numPlayers`，**从不调用 `applyMods`**——`deathMessage`、`joinMessage`、聊天 `message`/`format` 等通用字段回写（`e.deathMessage = {...}` 等）全部静默失效（JS 侧正常收集与 release，Java 侧丢弃，非异步问题）
- **修复**：`dispatchConcurrent` 在聚合循环中对每个插件结果调用 `applyMods`（仅排除上述聚合专用字段），与串行路径 `dispatchSerial` 语义一致；`cancelled` 仍按"任一取消即取消"、serverPing 字段仍按聚合语义处理
- Folia 无此问题（其分发本就直接 `applyMods`）
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

### Player 继承 LivingEntity / Entity

- **`Player extends LivingEntity extends Entity`**：Player 纳入实体继承体系——`Entity` 通用能力（`velocity`（`setVelocity`）、`fireTicks`、`getBoundingBox`、`getPassengers`、`teleport` 等）与 `LivingEntity` 能力（`health`、`maxHealth`、`damage`、`setTarget` 等）在 Player 上直接可用（此前 Player 为独立类，无 `setVelocity` 等）
- 构造不再重复声明 `uuid`（由 Entity 基类持有）；Player 自有的 `health`/`world`/`location`/`teleport` 等覆盖基类同名成员（玩家侧任务优先），未覆盖的实体/活体成员走实体任务
- 文档：api/player.md（类型关系说明）
- 验证：`tsc --noEmit`（yeow-api）通过

## 2026-08-16

### yeow-fflate 0.3.1：ZipReader（list / stat / 单文件读取）

- **新增 `ZipReader`**：构造时只解析 ZIP 中央目录（含 Zip64），`list()` / `stat(name)` / `has(name)` 提供条目元数据（name、compression、size、originalSize、crc、mtime、attrs），`read(name, options?)` / `readSync` 只解压目标文件；`readRaw` / `readRawSync` 直接返回条目的原始压缩字节（raw deflate / 存储字节，不解压）
- **编码语义与 fs 一致**：单文件读取默认 `Uint8Array`；`'utf8'` / `'base64'` 返回字符串；不存在条目返回 null
- 实现：`src/async.ts` 复用 core 的中央目录解析工具，Base64 输出用纯 JS 编码器（无 btoa/Buffer 依赖）；`verify.mjs` 新增 10 项检查，总计 **47 项 ALL PASS**
- 版本：**yeow-fflate 0.3.0 → 0.3.1**（peer `yeow-api ^0.4.0` 不变）

### 版本升级（0.4.1 / 0.1.2）

- **yeow-api 0.4.0 → 0.4.1** / **create-yeow 0.4.0 → 0.4.1** / **yeow-server 0.1.1 → 0.1.2**
- 内容：yeow-api（事件坐标 Location 化/物品统一/`post` 导出/批量错误对齐/`respond` body+encoding/`isOnlineAsync` 等）+ create-yeow（模板同步）+ yeow-server（mount/mountAssets index.html、响应体与 fs 同语义）
- 模板依赖：`yeow-api ^0.4.0`（caret 自动覆盖 0.4.1）；yeow-server peer `^0.4.0` 不变
- 运行时：模板内置 `yeow-runtime-0.2.0.jar` 已同步（含 fs.list 条目名、respond encoding）

### 新增 player.sendBlockChange（客户端假方块）

- **`Player#sendBlockChange(loc, block)`**：向玩家发送假方块变化（仅客户端视觉，不改变真实世界）；`block` 为 [Block](api/block.md) 对象或字符串，与 `world.setBlock` 同语义（`blockType` 宽松匹配、`state` 键值对构建规范方块数据）；`world` 省略时默认玩家所在世界
- 任务集（Paper `PlayerTasks` + Folia `FoliaTasks`）：`player.sendBlockChange`；API 层含 `sendBlockChangeSync`
- 文档同步：specifications/task/player.md、api/player.md

### 一致性修复（整体通读审查）

- **Folia 事件桥补齐字段**（与 Paper 对齐）：`entityDeath` 补 `entityName`、`inventoryOpen` 补 `title`、`inventoryClick` 补 `hotbarKey`/`clickedItem`/`cursorItem`、`playerDeath.deathMessage` 改 Message 对象（`{key,args,text}`）；`eventData` 字段提取异常不再逸出监听器（告警 + 丢弃本次分发，与 Paper 一致）
- **`inventory.removeItem` 返回统一为未移除数量 `int`**（0 = 全部移除；Folia 此前返回 `true`，规范同步修正）
- **事件 `playerGameModeChange.newGameMode` 统一小写**（`creative` 等，与值域附录 R2 及 `player.getGamemode` 一致；破坏性：此前为大写枚举名）
- **`fs.delete` 实现递归删除**（对齐规范：此前非空目录抛错）
- **`assets.extract` / `extractDir` 返回相对服务器根目录路径**（对齐规范与 api/assets.md：此前返回绝对路径）
- **http `request` / `requestAsync` 显式 `body: null` 不再报错**（按规范默认 null 处理）
- 规范修正：message/index 通道列表补 `worker`；runtime/index 通道总览与每通道 cb 语义表补 `worker` + `util`、定时器回调实参（`r: true`）、`_getCurrentCbStack` 返回调用链节点对象、`__yeowEventHandlers` 归属 yeow-api `event.ts`；event/index 订阅引用指向 task/event-system 规范
- 文档修正：api/README Block 描述移除已删除的 isLiquid 语义、补 Util 页；sitemap ×2 补 Util 行与 util 通道；yeow-fflate 版本行（0.3.0 → 0.3.1）
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

### http-server respond：body + encoding（移除 bodyBase64）

- **`respond` 响应体与 `fs.writeFile` 同语义**：`body` 支持 `Uint8Array`（直接二进制）或字符串——字符串默认 UTF-8 文本，`encoding: 'base64'` 时视为 base64 编码的二进制数据（解码后按字节原样写出）；**`bodyBase64` 字段移除**
- 协议层（`PluginThread` respond）：`{ body, encoding }`——`'utf8'`（默认）/ `'base64'`；`respond` 返回协议不再有 `bodyBase64`
- API 层（yeow-api `http.ts`）：`RespondOptions.body: string | Uint8Array` + `encoding?: 'utf8' | 'base64'`——`Uint8Array` 自动转 base64 承载，插件无需手动中转
- yeow-server：`mount`/`mountAssets` 响应改 `{ body, encoding: 'base64' }`；二进制示例改 `assetsRead(path)`（Uint8Array）
- 文档同步：specifications/message/http.md（respond 协议）、api/http-server.md（响应选项/二进制节）、yeow-server README、sitemap ×2
- 验证：`tsc --noEmit`（yeow-api + yeow-server）通过

### yeow-server：mount / mountAssets 目录请求尝试 index.html

- **两个静态挂载（`mount` / `mountAssets`，共享同一实现）新增目录索引**：目录请求自动尝试 `index.html`——`/` → `index.html`、`/a/` → `a/index.html`；普通路径 `/a` 先按文件读，失败（不存在/是目录）再试 `a/index.html`；**候选全部失败时 `next()` 继续后续层**
- 挂载根（如 `mount('web/')` 请求 `/`）此前直接跳过，现同样尝试 `index.html`；Content-Type 按**实际命中的文件**推断（`index.html` → `text/html`）
- 文档同步：yeow-server README、api/http-server.md（mount/mountAssets 两节）
- 验证：`tsc --noEmit` 通过

### fs.list 返回条目名（不再泄漏绝对路径）

- `fs.list` / `fs.listSync` 返回**目录条目名**（不含路径前缀，与 Node `fs.readdir` 一致）——此前返回绝对路径（`Path::toString`），泄漏服务器文件系统布局且平台相关（Windows 盘符等）
- 规范同步：specifications/message/fs.md（返回示例与说明）、api/fs.md（补充语义说明）
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步；`mvn test` 通过

## 2026-08-15

### 值域附录结构调整：平台枚举直接维护 + 版本变迁域规则化

- **附录重构为三节**：格式规则（R1-R5 + 方向规则 + 值域总表）→ 直接维护清单 → 版本变迁域（规则+链接）；进度/配方键并入 R1；PDC 键规则由 pdc 任务规范承载
- **平台专有枚举全部直接维护为规范清单**（经 paper-api 1.21.4 javap 逐一核实）：ChatColor 16 色（+6 格式码）、DisplaySlot 19（含 16 个 `SIDEBAR_TEAM_*`）、TeamOption 3 / OptionStatus 4（`FOR_OTHER_TEAMS`/`FOR_OWN_TEAM`）、BarColor 7 / BarStyle 5 / BarFlag 3、ClickType 14、ItemFlag 10、DamageCause 33、InventoryType 31、环境 4、世界类型 4（1.21 无 CUSTOMIZED）
- **修正 scoreboard 规范错误值**：`HIDE_FOR_*` / `PUSH_*` 为旧版/错误命名，1.21 实际枚举为 `ALWAYS` / `NEVER` / `FOR_OTHER_TEAMS` / `FOR_OWN_TEAM`（collisionRule 推挤语义即 `FOR_*`）
- **版本变迁域规则化**（不维护清单、不保证稳定）：方块/物品/实体/生物群系/音效/粒子/附魔/药水/属性/伤害类型/游戏规则/翻译键——R1-R4 规则 + 权威链接 + 运行时动态枚举
- 删除决策过程/迁移记录等非规范内容（迁移历史已入 changelog）

### 值域格式统一（R1-R5 框架）：药水/粒子/属性键化 + 附录补全

- **统一格式规则框架**（值域附录 `specifications/values.md` 重写）：所有取值字段按优先级归入五类规范格式——**R1 注册键**（有原版注册表对应：方块/物品/实体/生物群系/音效/粒子/附魔/药水/属性/伤害类型）、**R2 小写规范串**（游戏模式/难度）、**R3 原版命名**（游戏规则驼峰）、**R4 翻译键**（`Message.key`，以原版 lang 文件为准）、**R5 平台专有枚举**（无原版一一对应：DamageCause/ClickType/InventoryType/计分板/BossBar/ItemFlag/环境/世界类型等，保留大写并显式标注）；**入宽出严**：入参兼容旧格式（大小写不敏感），出参一律规范格式
- **药水效果键化**（Paper + Folia）：`entity.addPotionEffect`/`removePotionEffect`/`meta.potionEffects` 入参接受 minecraft 注册键（`minecraft:speed`）+ 旧枚举名兼容；`entity.getActivePotionEffects` 与 `meta.potionEffects` **出参改为注册键**（原小写枚举名 `speed`）
- **粒子键化**：`world.spawnParticle.particle` 入参接受 minecraft 注册键（`minecraft:flame`）+ 旧枚举名兼容（`Particle implements Keyed`，经 `Registry.PARTICLE_TYPE` 解析）
- **属性入参键化**：`meta.attributeModifiers[].attribute` 入参接受 minecraft 注册键（`minecraft:attack_damage`）+ 旧枚举名兼容——**出入格式不对称消除**（出参本已是键）
- **deathType 补全为注册键**：`playerDeath.deathType` 由裸键（`lava`）改为完整注册键（`minecraft:lava`）；**Folia 事件桥补上缺失的 deathType 字段**（此前与 Paper 不一致）
- **附录补全**：新增 `Message.key`（翻译键，R4，以原版为准 + 资源包扩展说明）、伤害类型（完整键 + 链接）、药水/粒子/属性迁移记录；小集合（游戏模式/难度/环境/世界类型/游戏规则/BossBar/计分板/ItemFlag/伤害原因/点击动作）全部列举，大集合（方块/物品/实体/生物群系/音效/粒子/附魔/药水/属性/伤害类型）规则 + 权威链接
- 文档同步：task 规范（entity 药水参数/inventory-gui meta/world 粒子）、事件规范（player-events deathType）、api 文档（potion/item/particle/entity 示例）
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步；验证：`mvn test`（93 项）通过

### 协议/API 一致性修订（P1-P4）：实体类型键化、枚举小写、物品/坐标统一

**P1 协议层（Paper + Folia 两侧同步）**
- **EntityType 改用 minecraft 注册键**：`entity.getType` 返回 `minecraft:zombie`（原 Bukkit 枚举名 `ZOMBIE`）；`world.spawnEntity` 的 `type` 入参接受 minecraft 键（兼容旧式枚举名回退）——材料/方块/生物群系（minecraft 键）与实体类型的命名空间约定统一；事件载荷 `entityType` / `projectileType`（entityDamage/entityDeath/entitySpawn/entityExplode/projectileLaunch/projectileHit）同步键化
- **枚举大小写统一（小写）**：`player.getGamemode` 返回小写 `creative`（原大写 `CREATIVE`）——与 `world.getDifficulty` 一致；入参大小写不敏感不变
- **移除 `material.isLiquid`**（近似语义，Bukkit 1.13 起无 isLiquid，旧实现为水/熔岩枚举判断）：协议节点（Paper+Folia）+ `Material.isLiquid/isLiquidSync` + `Block.isLiquid/isLiquidSync` 全部移除——液体判断改用 `world.getBlock` 的 `state` 或方块类型比对

**P2 API 层**
- `core.ts` 导出 `post`（此前只导出 `call/callBatch/postBatch`，与 call 不对称）
- 批量错误对齐：`callBatch` 顶层 `{err}` 改为与 `call/post` 一致的带 type/task/Java 堆栈上下文 Error；**单任务失败仍为数组内 `{err}` 元素**（批量保留部分结果，契约显式化，文档已注明）
- Sync 补齐：`world.setBorderDamageSync` / `setBorderWarningSync` / `setBorderMovingSync`

**P3 state 对象化（确认现状）**
- `world.getBlock` 的 `state` 在 Paper 与 Folia 两侧**均已对象化**（BlockData 字符串解析为键值对象，与 `setBlock` 入参对称）——审计确认，无需改动；文档与 API 类型（`BlockState`）已一致

**P4 事件/物品统一（yeow-api）**
- **事件坐标统一 `Location`**：entitySpawn/entityExplode/blockBreak/blockPlace/blockFade/blockGrow/blockSpread/blockExplode 的平铺 `x/y/z/world` → `location: Location`（与 playerMove/teleport 的 from/to 同形）；playerInteract.block、projectileHit.hitBlock → `{ location, type }`——原始 `x/y/z` 字段仍保留在事件对象上（运行时兼容旧代码读取）
- **物品表示统一 ItemStack**：`playerDropItem` / `playerPickupItem` / `playerItemConsume` 的 `itemType+amount` → `item: ItemStack`；`inventoryClick.clickedItem/cursorItem` 类型改为 ItemStack；`world.dropItem` 参数改为 `item: ItemStack | string`（字符串兼容旧式，运行时 `item` 对象优先、`itemType` 回退）
- **命名收敛**：`player.getOnline()` → `isOnlineAsync()`（与 isOpAsync/isFlyingAsync/isSneakingAsync 一致；`online` 同步 getter 不变）

**其他**
- 文本管线保持各平台独立（Paper TextUtil / Folia FoliaTextUtil，依赖 Paper Adventure）——不做下沉
- `entity.setTarget` 保留（意图式语义可接受）；Folia 并发模型（region/GLOBAL 路由）保持现状
- 文档/规范同步：task 规范（entity/world/player）、事件规范（entity-events）、api 文档（entity/world/player/event/material/block）、sitemap
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步；验证：`mvn test`（93 项）+ `tsc --noEmit` 通过

### dev 模式栈追踪修复：未处理 rejection 也能还原完整异步链（P1-P6）

- **链段展开提前到打标时**（此前只在报错路径展开）：`mark` 每次把 hop 调用点挂到 rejection reason 后立即重建 `stack` 字符串——**未处理 rejection / job 抛错经 native wrapper 逃逸时（快照的是错误当前的 stack），异常消息已携带完整链**，dev-server 不再只显示一个裸帧
- **`_finalStack` 门控修复**：此前 `__yeowStackNode` 未设置时跳过展开——纯 rejection（非同步抛错）的链节点从未进入输出；现改为节点或链任一存在即重建（`__yeowRawStack` 只快照一次，重建幂等不嵌套累积）
- **全 hop 打标**：无 rejection handler 的透传 `.then()` 也记录调用点（此前只记录带 handler 的 hop 与 onFulfilled 抛错）——`p.then(A).then(B).catch(C)` 三个链接点全记录（连续相同合并 ×N）
- **链段存储有界**：先合并再截取——每错误对象最多保存 **50 个合并片段**（保留栈帧起始 40 段固定 + 结尾 **10 段滑动窗口**持续更新，窗口最旧段丢弃计入省略数，存储不随 hop 数无界增长）；输出构建时再合并并 **6+6 截取**（首 6 + 省略标记 + 尾 6，省略计数含存储层丢弃数）
- **onRejected 抛错打标**：`.catch` 处理器自身抛出的新错误同样挂链（与 onFulfilled 对称；此前裸栈）
- **多消费者修复**：移除 `promise[_chainMark]` 单次守卫——同一 promise 上多个 `.then()` 全部记录调用点
- **透传语义修正**（关键）：无 handler 的 rejection 透传改为**重抛** reason（等价规范内建 Thrower）——修复此前"return reason 把 rejection 吞成 fulfillment、下游 catch 永不触发"的语义 bug（曾导致 `.then(A).then(B).catch(C)` 中 C 不执行、p2 以错误对象为值 resolve）
- **P6 生命周期钩子注册点**：yeow-api `onInit/onLoad/onUnload` 在注册时捕获用户调用栈挂到回调（`__yeowNode`，仅 `$dev` 捕获）；init.js 分发钩子时优先使用它作为栈上下文（旧版 yeow-api 副本无该属性时退回分发点上下文）——钩子内 `.then()` 链与钩子抛错都能定位到注册处
- 边界不变（文档声明）：`await` 续体与原生 `Promise.prototype.finally` 为引擎内部实现，不产生 hop 节点；非 Error 的 rejection reason 不加工栈
- 验证：Node 逻辑级 harness 25 项断言（dev 22：存储上限 50 / 起始保留 / 窗口滑动 / 合并 ×N / 输出 6+6 与省略计数 / P1-P5 / P6 / $cb 回归；prod 3：零拦截）ALL PASS；`mvn test`（93 项）通过
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步；yeow-api 未单独升版

### init.js 修复：interval 实参 / GC 冲刷 / fetch 悬挂 / 双份日志

- **`setInterval` 回调收到多余实参**：Java 定时器投递 `r=true`，interval 回调被以 `true` 作为第一个参数调用（与标准语义不符；setTimeout 因外层包装不受影响）——注册时改用 `() => fn()` 包装丢弃载荷实参
- **`$send` 非 JSON 返回防御**：`$_send` 返回非 JSON（如错误文本）时此前 `JSON.parse` 抛 SyntaxError 掩盖真实结果——解析失败改为原样返回
- **GC 队列冲刷加固**（InstanceId 句柄回收上报）：
  - `$hm` 改用 `finally` 冲刷——消息分发异常（用户回调抛错）不再跳过 `_flushGC`（此前延迟到下一次消息、插件随后卸载/重载即永久丢失）
  - `_flushGC` 改为**发送成功后才清空**（原地 `length = 0`，`__yeowGcQueue` 引用不变）——桥调用失败时保留 id 待下次重试（此前 `splice` 先取后发，失败即丢失）
- **`reportError` 双份日志**：debug 通道上报后 Java 侧已打印 `JS Error`，原代码又经 `console.error` 走 log 通道重复打印——`console.error` 改为仅 debug 通道失败时兜底
- **fetch 悬挂修复**：`$_send` 抛错（如插件卸载中 io 线程池已关闭）或同步返回 `{err}` 时，Java 不会投递回调——注销回调并 reject，防 `_cbs` 泄漏与 Promise 永久悬挂
- **`$hm` 对非法 JSON 返回 `null`**（原返回字符串 `'null'`，语义混乱；Java 侧忽略返回值，无行为影响）
- 删除未使用的 `_captureStack` 死代码
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

### dir 通道并入 env（pluginDir 字段）+ log 通道等级支持

- **删除 `dir` 通道**（PluginThread / WorkerThread 的分支、`getDataDirPublic`、`inject()` 中被 lambda 捕获的局部变量）——其功能并入 **`env` 通道新增 `pluginDir` 字段**：返回插件数据目录路径（`plugins/<pluginName>`，与原 dir 通道返回一致；Worker 中为主插件目录，语义不变）；`yeow-api` 的 `EnvInfo` 接口同步新增 `pluginDir: string`
- 文档同步：specifications/message/index.md（通道列表 + env 章节）、specifications/runtime/index.md（cb 语义表 + 通道表 + env 章节 + 权限列表）、specifications/README.md、advanced/channels.md、api/env.md
- **log 通道支持等级**：Java 侧（PluginThread + WorkerThread）按 `level` 字段输出——`INFO` → `log.info`、`WARN` → `log.warning`、`ERROR` → `log.severe`（此前一律 `log.info`，`console.warn/error` 全部以 INFO 级别落日志；与 log.md 规范对齐）
- 产物：模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步

### 事件处理器抛错不再悬挂事件桥（event.complete finally 释放）

- **此前**：事件 handler 同步抛错时 `event.complete` 被跳过（`$send` 在 handler 调用之后）→ 事件桥等待 complete 直到 5s 超时，每次抛错都卡住该事件并产生 `event.timeout` 噪音
- **修复**（yeow-api `event.ts`）：非 manualRelease 模式改用 `try/finally` 释放事件（mods 合并逻辑不变）；manualRelease 模式处理器抛错时立即 `release()`（complete 幂等，重复调用无副作用）；`$send` 加保护避免桥故障掩盖原始错误——错误照常经 `$hm` 重抛上报，事件即时释放
- yeow-api 未单独升版（随现有版本一并发布）；运行时修复随模板内置 jar 同步

### 移除 dedupe 插件：yeow-api 多副本可安全共存

- **删除构建期 dedupe 插件**（`makeDedupePlugin`）：不再把 `import 'yeow-api'` 统一重写到主项目实例——安装完全交给包管理器按语义化版本规则进行，peer 范围不重叠（如主项目 `^0.3.0`，依赖包 `^0.4.0`）时依赖包自带独立 yeow-api 副本，bundle 内多副本共存
- **yeow-api 全局注册表改为共享**（读已有、绝不覆盖）：
  - 生命周期钩子（`onInit` / `onLoad` / `onUnload`）注册到共享全局数组（`__yeowInitCbs` / `__yeowLoadCbs` / `__yeowUnloadCbs`）——多副本的回调进入同一注册表，运行时一次 INIT/LOAD/DISABLE 分发**全部执行**，修复了旧 dedupe 依赖的覆盖式赋值在无 dedupe 时会导致 `onLoad` 丢失的问题
  - 句柄 GC 队列（`__yeowGcQueue`）改为复用运行时创建的全局数组——多副本句柄回收进入同一条 `gc-collect` 上报通道；顺带修复：此前 `instance-id` 新建数组覆盖全局，与 init.js `_flushGC` 冲刷的数组不一致，`gc-collect` 实际从未上报（BossBar/Inventory 句柄回收失效）
  - 事件处理器注册表（`__yeowEventHandlers`）本就是共享模式，无需改动
- **安全前提**：yeow-api 对底层协议是纯封装（消息协议 1.0.0 之后不会出现破坏性变更），多副本只是重复封装同一协议，无全局状态分裂
- 模板：`.yeow/yeow-assets.mjs` 删除 `makeDedupePlugin`（含 yeow-command/yeow-server 的 onResolve 一并移除），`.yeow/build.js` 移除两处引用
- 文档：package-author.md「构建时的自动处理」改为「yeow-api 多副本共存（无去重）」说明，peerDependencies 表格与版本策略同步更新；sitemap.md 构建自动处理描述更新
- 关联独立包：`yeow-command` / `yeow-server` 无全局状态，多副本天然安全（不影响）

### fs API：二进制优先（Node 风格编码）+ 移除 Base64 专用 API

- **readFile 默认返回 `Uint8Array`**：`fs.readFile(path)` / `fs.readFileSync(path)` 返回原始字节（底层 `readBase64` 承载，JS 侧 `Uint8Array.fromBase64` 解码）；显式 `'utf8'` / `'base64'`（或 `{ encoding }`）才返回字符串——utf8 走文本节点，base64 直接返回协议 Base64 串。**兼容性破坏**：原先默认返回字符串的 `readFile` 调用需加 `'utf8'`（类似 Node `fs.readFile`）
- **writeFile / appendFile 统一数据形态**：`data: string | Uint8Array`——字符串默认按 UTF-8 写入；`{ encoding: 'base64' }`（或 `'base64'`）时把字符串视为 Base64 编码的二进制数据解码后写入；`Uint8Array` 始终写原始字节（encoding 选项忽略）
- **新增 fs 协议节点 `appendBase64`**：二进制追加（Base64 解码 + `CREATE,APPEND`，含运行时目录写保护与 server/outer 权限模型）——API 层 `appendFile(bytes)` / `appendFile(str, 'base64')` 走此节点
- **移除 Base64 专用 API（破坏性）**：`readFileBase64` / `readFileBase64Sync` / `writeFileBase64` / `writeFileBase64Sync` 从 yeow-api 公共导出移除——二进制直接用 `readFile` / `writeFile`（Uint8Array）；显式 Base64 字符串用 `readFile(path, 'base64')` / `writeFile(path, str, 'base64')`
- **读流编码固定于创建时**：`createReadStream(path, { encoding?: 'utf8' | 'base64' })`——缺省返回 `ReadStream<Uint8Array>`，指定编码返回 `ReadStream<string>`；**运行时不支持修改**（无 setEncoding）。UTF-8 模式跨块保留不完整的多字节序列（chunk 边界字符不截断），EOF 时冲刷（非法序列替换为 U+FFFD）；base64 模式逐块返回 Base64 字符串
- **写流与 writeFile 同语义**：`createWriteStream(path, { flags?, encoding? })`——字符串 chunk 默认 UTF-8、`base64` 先解码后写字节；`Uint8Array` chunk 原始字节；`WriteStream.encoding` 只读（创建时固定）
- 权限：`appendBase64` 纳入 fs 节点权限模型（`fs:plugin.appendBase64` 免声明；`fs:server.appendBase64` / `fs:outer.appendBase64` 需声明，整组/通道通配照常覆盖）；`FsPermissionTest` 补充用例
- 实现：`yeow-api/src/fs.ts` 重写（移除 4 个 Base64 API；新增 `FsEncoding` / `FsData` / `ReadFileOptions` / `WriteFileOptions` 类型；UTF-8 流式解码器）；core `PluginThread.handleFs` 新增 `appendBase64`；模板内置 `yeow-runtime-0.2.0.jar` 已重新构建并同步
- 文档：api/fs.md（重写编码语义与流示例）、specifications/message/fs.md（`appendBase64` + 运行时目录保护清单）、api/http-server.md 示例更新
- 关联包：`yeow-server` 静态挂载改用 `fs.readFile(filePath, 'base64')`（原 `fs.readFileBase64` 已移除）

### assets.read 合并：默认 Uint8Array + 显式编码（与 fs.readFile 同语义）

- **移除 `assets.readBase64` / `assets.readBase64Sync`（破坏性）**：assets 只保留 `read` / `readSync`（`extract` / `extractDir` 系列不变）
- **`assets.read` 默认返回 `Uint8Array`**：`'utf8'` 返回文本、`'base64'` 返回 Base64 字符串——选项形式与 `fs.readFile` 一致（`'utf8' | 'base64'` 或 `{ encoding }`）
- 协议层不变：`assets:read`（UTF-8 文本）与 `assets:readBase64`（二进制 Base64 承载）节点继续保留；yeow-api 顶层别名只保留 `assetsRead` / `assetsReadSync`
- 关联：`yeow-server` 的 `mountAssets` 与 api/http-server.md、api/assets.md 示例改用 `assetsRead(path, 'base64')` / `assets.read(path, 'utf8')`

### 版本升级（0.4.0）

- **yeow-api 0.3.10 → 0.4.0** / **create-yeow 0.3.10 → 0.4.0** / **yeow-command 0.1.0 → 0.1.1** / **yeow-server 0.1.0 → 0.1.1** / **yeow-fflate 0.2.1 → 0.3.0**（独立包 peer 依赖范围同步 `yeow-api ^0.4.0`）
- 内容：fs 二进制优先 + Node 风格编码、移除 fs Base64 专用 API、assets.read 合并、`appendBase64` 协议节点、读/写流编码创建时固定
- 破坏性变更：`fs.readFile` 默认返回 `Uint8Array`；`readFileBase64` / `writeFileBase64` 移除；`assets.readBase64` 移除（改用 `assets.read(path, 'base64')`）
- 模板依赖：`yeow-api ^0.4.0`
- 运行时：模板内置 `yeow-runtime-0.2.0.jar` 已同步（含 `appendBase64`）

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
- **Gzip 原始 deflate（raw）选项**：`Gzip.compress/decompress`（含 Sync）与 `createCompressor/createDecompressor` 新增 `{ raw?: boolean }`——true 时操作**原始 deflate 流**（无 GZIP 头/尾/CRC，对应 `Deflater`/`Inflater` nowrap；raw 无完整性校验，截断静默结束）；`level` 数字简写兼容（`Gzip.compress(data, 6)` 仍可用）；`UtilCodecTest` 新增 5 用例（raw 往返/各级别/无 GZIP 头且与 gzip 互不兼容/输出上限/流式 raw 与一次性一致）
- 实现：core `yeow/util/{GzipCompressor,GzipDecompressor,FileStreams}.java`（可单测）+ `PluginThread` 流操作；`UtilCodecTest` 新增 4 用例（分块往返/单块与一次性一致/空输入/穿插空块）
- 文档：api/util.md（重写）、api/fs.md（流式节）、specifications/message/{util,fs}.md、operations.md；站点侧边栏加入 Util 页

### yeow-utils 拆分为 yeow-command + yeow-server（独立 npm 包）

- **`yeow-utils` 拆分并迁移至 `packages/`**：`yeow-command`（重载式命令构建器：Command/CommandSchema/Completer）与 `yeow-server`（HTTP 服务器：createServer/中间件/静态挂载）——各自独立 package.json / tsconfig / README.md，**从本仓库 Git 追踪移除**（`/packages/` 已入 .gitignore，与 `yeow-fflate` 同处）
- **主仓库策略**：`packages/` 下为**独立 npm 包**（yeow-fflate / yeow-command / yeow-server），各自独立仓库管理，**不并入本仓库**——本仓库只追踪运行时 / yeow-api / create-yeow / 文档等核心组件；组件清单（README/CONTRIBUTING/LICENSE）不再列举这些独立包
- **模板不预装**：模板项目仅依赖 `yeow-api`——需要命令构建器 / HTTP 服务器时自行 `npm install yeow-command` / `yeow-server`（构建脚本的依赖统一解析已支持这两个包）
- **导入变更（破坏性）**：`import { Command, CommandSchema } from 'yeow-command'`、`import { createServer } from 'yeow-server'`——`yeow-utils` 不再发布
- 文档：api/http-server.md 与 api/command.md 顶部引用 [yeow-server](https://www.npmjs.com/package/yeow-server) / [yeow-command](https://www.npmjs.com/package/yeow-command) npm 链接；示例导入同步

### 版本升级（0.3.10 / 0.1.30）

- **yeow-api 0.3.9 → 0.3.10** / **create-yeow 0.3.9 → 0.3.10** / **yeow-command 0.1.0** / **yeow-server 0.1.0**（原 `yeow-utils` 0.1.30 由拆分替代，不再发布）
- 内容：流式文件读写（start/end 偏移、flags、fs.stat）+ 分块 gzip（Gzip 命名空间 + **raw 原始 deflate 选项**）、util 上限配置化、http 回调修复、`encode.utf8` 输入校验修复、http 权限文档（listen/respond）、旧 gzip 导出移除（破坏性：用 `Gzip.*`）
- 运行时内容更新：模板内置 `yeow-runtime-0.2.0.jar`（含 util 通道、调度器/事件桥/Profile 修复、流句柄、util 校验修复、fs.stat、raw deflate）
- 模板依赖：仅 `yeow-api ^0.3.0`（caret 自动覆盖；`yeow-command`/`yeow-server` 为独立 npm 包，按需自行安装）

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
