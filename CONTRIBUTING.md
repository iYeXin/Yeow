# 贡献指南

感谢参与 Yeow 开发。本指南说明仓库结构、各组件构建方式、**本地安装流程**（Maven/Gradle）与文档维护约定。

---

## 环境要求

| 工具 | 版本 | 用途 |
|------|------|------|
| Node.js | 18+ | `yeow-api` / `create-yeow` / 文档站点 |
| JDK | 21+ | `yeow-runtime/jvm` / `yeow-template` / `yeow-tools`；打包 `quickjs-wrapper` 的 JAR |
| Maven | 3.9+ | 运行时与模板构建 |
| Zig | 0.16.0 | `quickjs-wrapper` 原生构建与交叉编译 |
| Git | 任意 | 版本控制 |

**克隆时必须带 submodule**（`quickjs-wrapper/native/quickjs` 是 QuickJS 本体的 submodule）：

```bash
git clone --recursive https://github.com/iYeXin/Yeow.git
```

已克隆后补拉 submodule：`git submodule update --init --recursive`。

---

## 仓库结构

| 目录 | 组件 | 构建方式 |
|------|------|---------|
| `yeow-runtime/jvm` | Maven 父工程（多模块） | 根目录 `mvn install -DskipTests`（详见下文） |
| `yeow-runtime/jvm/core` | 平台无关引擎（artifact `yeow-runtime-core`，零 Bukkit 依赖） | 随父工程构建；单独构建 `mvn -pl core install` |
| `yeow-runtime/jvm/paper` | Paper/Bukkit 平台实现（artifact `yeow-runtime`，产出运行 jar） | 随父工程构建；单独构建 `mvn -pl paper install`（需先装 core） |
| `yeow-runtime/jvm/folia` | Folia 平台实现（artifact `yeow-runtime-folia`，实验性） | 随父工程构建；单独构建 `mvn -pl folia install`（需先装 core） |
| `yeow-template` | 空 JAR 骨架 | `mvn package`（依赖 yeow-runtime，需先安装到本地 Maven 仓库） |
| `yeow-api` | TS 库 | 无构建步骤（源码直接随插件 bundle）；类型检查 `tsc --noEmit` |
| `create-yeow` | CLI 脚手架 | 无构建步骤；模板改动直接生效 |
| `quickjs-wrapper` | QuickJS 专用 JNI 桥（原生 C + Java `wiki.yexin.quickjs`） | Zig 0.16 + Node（`node build.mjs jar`，详见下文） |
| `yeow-tools` | 开发基准/诊断工具 | `mvn package`（独立，不依赖运行时） |
| `yeow-dev` | 构建期虚拟模块（空 npm 包） | 无构建；发布 `npm publish`（构建时被 esbuild 拦截，不实际加载） |
| `yeow-doc-website` | 文档源 + 文档站点 | 文档目录 `docs/cn/`（仓库内直接提交）；`npm run build` 产出 `/v1/` 站点 |
| `packages` | 独立发布的关联 npm 包（`yeow-command` / `yeow-server` / `yeow-fflate` / `yeow-docs`） | 各自 TypeScript 类型检查 `tsc --noEmit`；发布 `npm publish` |

---

## 本地安装流程（Maven）

### 1. yeow-quickjs（原生桥，关键依赖）

`yeow-runtime/jvm` 依赖 `wiki.yexin:yeow-quickjs`（本地 Maven 仓库）。该桥位于本仓库 `quickjs-wrapper/`，用 **Zig 0.16** 编译——原生 C 源码 + 内置 JNI 头，构建原生库不需要 JDK：

```bash
cd quickjs-wrapper
node build.mjs jar     # 生成 polyfill JS 头 + 产出 zig-out/yeow-quickjs.jar（Java 类 + 全部平台原生库）
mvn install:install-file \
  -Dfile=zig-out/yeow-quickjs.jar \
  -DgroupId=wiki.yexin -DartifactId=yeow-quickjs -Dversion=0.6.1 -Dpackaging=jar
```

- 构建由 `build.mjs` 主持：先生成 `native/polyfill/js/*.js` 对应的 C 头（`scripts/gen-polyfill.mjs`），再调用 `zig build`。
- 只构建本机原生库：`node build.mjs`
- 只构建各平台原生库：`node build.mjs all` → `zig-out/native/<platform>/`
- 交叉编译：`node build.mjs -Dtarget=aarch64-linux-gnu` 等（单工具链产出 linux/macos/windows × x86_64/arm64）
- 冒烟测试与 API 说明：见 `quickjs-wrapper/README.md`

> 修改原生 C（`quickjs-wrapper/native/src/*`、`native/polyfill/**`）或 Java API 后，重新执行 `node build.mjs jar` 并重装该依赖，再重建 yeow-runtime。

### 2. yeow-runtime（core + paper + folia 多模块）

```bash
cd yeow-runtime/jvm
mvn clean install -DskipTests    # 一次构建 core + paper + folia 并安装到本地 Maven 仓库
```

构建产物 `paper/target/yeow-runtime-0.6.1.jar` 与 `folia/target/yeow-runtime-folia-0.6.1.jar`（均 shaded，含 core + 引擎 + 配置解析）需要**复制到脚手架模板**，供 `create-yeow` 生成的插件项目使用：

```bash
cp paper/target/yeow-runtime-0.6.1.jar folia/target/yeow-runtime-folia-0.6.1.jar ../create-yeow/templates/default/.yeow/assets/
```

### 3. yeow-template

```bash
cd yeow-template
mvn clean package -DskipTests
```

编译时解析 `yeow-runtime/jvm` 来自本地 Maven 仓库（第 2 步已安装）。产物同样复制到模板：

```bash
cp target/yeow-template-0.6.1.jar ../create-yeow/templates/default/.yeow/assets/
```

> **为什么必须同步复制？** `create-yeow` 生成的项目在 `npm run dev` / `npm run build` 时从 `.yeow/assets/` 读取这三个 jar。不同步会导致旧签名（如 `registerPlugin` 返回类型变化）引发 `NoSuchMethodError`。

---

## yeow-quickjs 版本

`quickjs-wrapper/` 现在是 **Yeow 专用实现**（不再是外部镜像），接口、包名与构件坐标见 `quickjs-wrapper/README.md`。升版步骤：

1. 修改 C / Java 代码，`quickjs-wrapper/CHANGELOG.md` 顶部新增版本条目（`## X.Y.Z *(YYYY-MM-DD)*`）
2. `cd quickjs-wrapper && node build.mjs jar`
3. 安装到本地 Maven 仓库：

```bash
mvn install:install-file \
  -Dfile=zig-out/yeow-quickjs.jar \
  -DgroupId=wiki.yexin -DartifactId=yeow-quickjs \
  -Dversion=<X.Y.Z> -Dpackaging=jar
```

4. 更新 `yeow-runtime/jvm/pom.xml` 的 `<quickjs.version>`（子模块经父 POM 的 dependencyManagement 继承），重建运行时（第 2 步）

> `native/quickjs`（QuickJS 引擎）仍是 git submodule，版本锁定在其自身仓库；本目录只维护桥接层。

---

## 文档维护

### docs/cn（中文文档源，位于 yeow-doc-website 内）

- 按现有结构编辑：`getting-started.md` / `api/*` / `advanced.md` / `specifications/*` 等
- 相对链接保持 `.md` 后缀（站点构建时自动重写）
- 新增文档时同步更新 `README.md`（文档地图）与站点侧边栏（见下）
- **「上次修改」由 VitePress 内置 `VPLastUpdated` 渲染（读取已入库文件的 git 提交日期），无需手动写日期**

### yeow-doc-website（站点）

- `docs/` 为仓库内文档目录（多语言根）：中文在 `docs/cn/`、英文占位在 `docs/en/`（直接提交，无构建复制/无 junction）——直接编辑对应语言目录下的文件
- 站点构建配置在 `vp-roots/<cn|en>/.vitepress/config.mts`（每语言一个独立 root），导航/侧边栏在此配置；多语言切换组件在 `vp-roots/<cn|en>/.vitepress/theme`（vt-locales-btn 原生风格，跨域跳转携带路径）
- 修改后重新 `npm run dev`（中文）/ `npm run dev:en`（英文），或 `npm run build` 同时构建两语言（产物分别在 `vp-roots/cn/.vitepress/dist` 与 `vp-roots/en/.vitepress/dist`）
- 预览：`cd yeow-doc-website && npm run dev`（站点运行在 `/v1/` 路径）
- 构建 + 发布：`npm run publish`（按 `.env` 的 `CN_PATH`/`EN_PATH` 逐语言上传）

---

## 代码约定

### Profile 插桩（检测点）

运行时性能检测通过统一的 `ProfileSink` 接口（`yeow.profile.instrumentation`）。新增检测点时：

1. 在 `ProfileSink` 增加方法 + 对应 Metric record（不可变）
2. 在检测点处**判空短路**调用（`if (s != null) s.onXxx(...)`），不构造样本对象
3. 聚合/告警逻辑一律放在 `collector` / `warnings` 包，运行时组件不直接依赖 Profiler

### 预警检测器

新增告警 = 在 `yeow.profile.warnings.detectors` 新增一个 `WarningDetector` 实现（纯函数 + 少量自包含状态），在 `Profiler.create` 中注册。注意：

- **HIGH/NORMAL 是实时队列（不应积压），LOW 是批量队列（允许积压）**——告警与分析口径遵循此语义
- 事件 2s 警告（`event-slow-threshold-ms`）与 5s 超时（`callback-timeout-event-ms`）是**两个独立概念**：前者提醒、后者是运行时行为

### 测试

测试套件分为两层，统一入口 `node tests/run.mjs <tier>`（详见 [tests/README.md](tests/README.md)）：

- **simple**（不启动服务器）：`node tests/run.mjs simple`——QuickJS 桥测试组件 + `mvn -q test`（core/paper/folia）。改动 `quickjs-wrapper` 或运行时逻辑后应先跑此层。
- **full**（实机 Paper）：`node tests/run.mjs full`——启动 Paper，加载 `tests/e2e/plugins/*` 测试插件并校验断言。需要 JDK 21；Paper jar 自动定位或下载（或 `--paper=<jar>`）。

profile 相关改动需同步更新对应单测；`tests/e2e/plugins/` 新增场景只需添加插件目录（`main.js` + `yeow.json`）。

**迭代同步要求（强制）**：改动实现必须**在同一次改动中**更新对应测试组件，不得留待后续；组件与测试层的对应关系见 `AGENTS.md` 的「迭代同步要求」表格。只改实现不改测试视为未完成。

---

## 提交规范

- 遵循 conventional commits：`feat:` / `fix:` / `docs:` / `refactor:` / `chore:`
- 提交前运行 `node tests/run.mjs simple`；涉及协议/运行时行为再运行 `full`。改动实现必须同步更新对应测试（见「测试」与 `AGENTS.md`）
- 二进制产物（`target/`、`node_modules/`、`dist/`）不提交，模板资产 jar 除外（见 `.gitignore`）
