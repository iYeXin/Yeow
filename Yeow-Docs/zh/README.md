# Yeow v0

**用 JavaScript / TypeScript 编写 Minecraft Paper 插件。QuickJS 引擎。**

Yeow 是一个面向 Minecraft Paper 服务器的插件开发框架：用现代前端工程化（TypeScript、npm、esbuild）编写插件逻辑，运行时为每个插件启动**独立的 QuickJS 线程**，通过消息桥与游戏主线程交互。插件代码不阻塞服务器主线程，一个插件崩溃不影响其他插件。

```bash
npm create yeow@latest -- -y     # 创建项目
cd my-plugin
npm install
npm run dev                      # 一键启动 Paper + 秒级热重载
npm run build                    # 产出标准 Paper JAR + 平台无关 .yeow.zip
```

---

## 为什么使用 Yeow

### 现代工程化体验

- **TypeScript 优先** — 全类型推断：命令参数、事件 payload、API 返回值均有完整类型，编辑器自动补全 + 编译期检查
- **npm 生态** — 插件逻辑与资源可封装为 npm 包复用（`yeow-api`、`yeow-utils`、自定义包）；`assets/` 资源按依赖项命名空间自动打包
- **热重载** — 修改 `src/` 或 `assets/` 即自动生效，无需重启服务器；生产环境可用 `/yeow reload/unload` 管理
- **构建即产物** — `npm run build` 产出**标准 Paper JAR** + **平台无关 `.yeow.zip`**：JAR 放入 `plugins/`，zip 放入 `plugins/Yeow/` 自动加载（或 `/yeow install` 一键安装）
- **声明式权限** — `fs:server.*`、`fs:outer.*`、`http:*`、`service:registerNative`、`assets:extract` 等敏感消息节点需声明授权，未声明调用返回错误

### 线程分离

- **每个插件独立 JS 线程**（独立 QuickJS 上下文）— 插件间全局隔离，一个插件崩溃不影响其他插件
- **JS 逻辑与游戏主线程分离** — 插件代码不阻塞服务器主线程
- **三级优先级调度器**（HIGH/NORMAL/LOW）— 时间片预算 + 自动降级 + 空闲自旋，防止单个插件挤占全局 tick
- **异步优先 API** — `Promise` 默认，同步操作加 `Sync` 后缀，按需选择

### 跨平台

- 插件包是标准 ZIP（`.yeow.zip`，内含打包 JS、资源、元信息与权限声明），**不依赖 Java 环境**
- 放入 `plugins/Yeow/` 自动扫描加载，或 `/yeow load` 动态加载
- **Paper 与 [Folia](https://papermc.io/software/folia/) 双平台通用**：同一份 `.yeow.zip` / `.jar` 插件包可直接互换，API 使用方法完全一致；Folia 服务器只需安装 Folia 版 [Yeow 运行时](https://modrinth.com/plugin/yeow)（Modrinth 提供）
- **Folia 深度适配**：Yeow 为 Folia 实现了独立的区域化调度器（区域驻留、热点迁移、预算控制、非阻塞投递），插件无需任何改动即可自动享受 Folia 的多线程并行优势——详见[进阶知识 · Folia](advanced/folia.md)
- 任何实现[平台规范](specifications/README.md)的运行时都能运行同一份插件；Folia 只是 Yeow 跨平台性的一个例子，未来还可能支持 Fabric / NeoForge 等平台
- Paper 系（Paper/Purpur/Leaf 等）的 `yeow-runtime` 是官方实现示例

### 原生能力扩展

- **Native Service** — 插件可嵌入 Go / Rust / C++ 等原生程序（`assets/` 携带二进制，运行时自动按平台提取 spawn），用 `serviceRequest` 调用，适合图像处理、机器学习等重计算
- **运行时健康检测** — 心跳超时、事件/补全超时、插件挂起自动告警；队列积压时动态扩容 tick 预算

## 与其他方案对比

|              | Yeow                | 传统 Java 插件 | Skript        | 脚本引擎插件（Nashorn/Rhino） |
| ------------ | ------------------- | -------------- | ------------- | ----------------------------- |
| 开发语言     | TypeScript/JS       | Java           | Skript DSL    | JS（受限）                    |
| 类型安全     | ✅ 完整类型推断      | ✅              | ❌             | ❌                             |
| npm 依赖复用 | ✅                   | 部分           | ❌             | ❌                             |
| 热重载       | ✅ 秒级              | ❌ 需重启       | 部分          | 部分                          |
| 线程隔离     | ✅ 每插件独立线程    | 主线程         | 主线程        | 主线程                        |
| 性能         | QuickJS（接近原生） | 原生           | 解释执行      | 解释执行                      |
| 平台可移植   | ✅ 插件包平台无关    | ❌ 仅 JVM       | ❌ 仅 Paper 系 | ❌ 仅 JVM                      |

---

## 文档与工具链

| 文档                                 | 说明                                           |
| ------------------------------------ | ---------------------------------------------- |
| [快速开始](getting-started.md)       | 创建项目 → 开发 → 构建 → 部署                  |
| [构建与分发](distribution.md)        | JAR / `.yeow.zip` 两种格式与一键安装           |
| [权限与原生服务可信性](permissions.md) | 敏感权限声明、SHA-256 可信性、原生服务批准    |
| [运行时运维](operations.md)          | `/yeow` 管理命令、运行时配置（config.yml）     |
| [API 参考](api/README.md)            | 按模块分组的完整索引                           |
| [编写依赖包](package-author.md)      | 包结构、资源、构建自动处理                     |
| [封装 Service 的依赖包](package-service.md) | SDK / JS 服务 / 原生服务三种封装模式     |
| [进阶知识](advanced.md)              | 架构、线程模型、调度器                         |
| [平台规范](specifications/README.md) | 协议层（其他运行时实现者参考）                 |

**工具链**：`create-yeow` 脚手架、dev-server、构建 —— 见 [CLI 参考](cli.md)。要求：Node.js 18+ · Java 21+（可选，开发服务器必需）。

**运行时下载**：[Modrinth](https://modrinth.com/plugin/yeow) · **项目源码**：[GitHub](https://github.com/iyexin/yeow)
