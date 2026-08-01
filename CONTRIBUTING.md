# 贡献指南

感谢参与 Yeow 开发。本指南说明仓库结构、各组件构建方式、**本地安装流程**（Maven/Gradle）与文档维护约定。

---

## 环境要求

| 工具 | 版本 | 用途 |
|------|------|------|
| Node.js | 18+ | `yeow-api` / `yeow-utils` / `create-yeow` / 文档站点 |
| JDK | 21+ | `yeow-runtime` / `yeow-template` / `yeow-tools` / `quickjs-wrapper` |
| Maven | 3.9+ | 运行时与模板构建 |
| Gradle | 8.x（仓库自带 wrapper） | quickjs-wrapper 构建 |
| Git | 任意 | 版本控制（本仓库与 quickjs-wrapper 均使用） |

**克隆时必须带 submodule**（`quickjs-wrapper/native/quickjs` 是 QuickJS 本体的 submodule）：

```bash
git clone --recursive https://github.com/iYeXin/Yeow.git
```

已克隆后补拉 submodule：`git submodule update --init --recursive`。

---

## 仓库结构

| 目录 | 组件 | 构建方式 |
|------|------|---------|
| `yeow-runtime` | Bukkit 插件运行时 | `mvn install -DskipTests`（详见下文） |
| `yeow-template` | 空 JAR 骨架 | `mvn package`（依赖 yeow-runtime，需先安装到本地 Maven 仓库） |
| `yeow-api` / `yeow-utils` | TS 库 | 无构建步骤（源码直接随插件 bundle）；类型检查 `tsc --noEmit` |
| `create-yeow` | CLI 脚手架 | 无构建步骤；模板改动直接生效 |
| `quickjs-wrapper` | QuickJS JVM 封装 | Gradle + CMake（详见下文） |
| `yeow-tools` | 开发基准/诊断工具 | `mvn package`（独立，不依赖运行时） |
| `yeow-dev` | 构建期虚拟模块（空 npm 包） | 无构建；发布 `npm publish`（构建时被 esbuild 拦截，不实际加载） |
| `Yeow-Docs` | 文档源 | 无构建；直接编辑 Markdown |
| `yeow-doc-website` | 文档站点 | `npm run build`（`docs/` 是指向 `Yeow-Docs/zh` 的目录联接） |

---

## 本地安装流程（Maven）

### 1. quickjs-java-wrapper（关键依赖）

`yeow-runtime` 依赖 `com.whl.quickjs:quickjs-java-wrapper`（本地 Maven 仓库）。该组件由主仓库 [iYeXin/quickjs-wrapper](https://github.com/iYeXin/quickjs-wrapper) 维护，本仓库仅镜像。两种获取方式：

**方式 A：使用 CI 发布产物（推荐）**

1. 在 `quickjs-wrapper` 目录修改代码 → 更新 `CHANGELOG.md` → `git commit` → 打版本标签并推送（`git tag vX.Y.Z` + `git push origin main vX.Y.Z`）
2. 标签推送触发 GitHub Actions 多平台构建，完成后从 [Releases](https://github.com/iYeXin/quickjs-wrapper/releases) 下载 `quickjs-java-wrapper.jar`
3. 放入 `yeow-runtime/lib/`，执行：

```bash
cd yeow-runtime
mvn install:install-file \
  -Dfile=lib/quickjs-java-wrapper-<version>.jar \
  -DgroupId=com.whl.quickjs -DartifactId=quickjs-java-wrapper \
  -Dversion=<version> -Dpackaging=jar
```

4. 确认 `yeow-runtime/pom.xml` 中的 `<version>` 与安装版本一致

**方式 B：本地构建（仅 Java 层改动，原生库不变）**

```bash
cd quickjs-wrapper
./gradlew :wrapper-java:jar
./gradlew :wrapper-java:publishToMavenLocal   # 或手动 install:install-file
```

> 注意：C++ 层（`native/cpp/*`）改动必须走方式 A 的 CI 流程——本地原生库不会自动重建，发布标签后把产物下载到 `yeow-runtime/lib/` 再重装。

### 2. yeow-runtime

```bash
cd yeow-runtime
mvn clean install -DskipTests
```

构建产物 `target/yeow-runtime-0.1.0.jar` 需要**复制到脚手架模板**，供 `create-yeow` 生成的插件项目使用：

```bash
cp target/yeow-runtime-0.1.0.jar ../create-yeow/templates/default/.yeow/assets/
```

### 3. yeow-template

```bash
cd yeow-template
mvn clean package -DskipTests
```

编译时解析 `yeow-runtime` 来自本地 Maven 仓库（第 2 步已安装）。产物同样复制到模板：

```bash
cp target/yeow-template-0.1.0.jar ../create-yeow/templates/default/.yeow/assets/
```

> **为什么必须同步复制？** `create-yeow` 生成的项目在 `npm run dev` / `npm run build` 时从 `.yeow/assets/` 读取这两个 jar。不同步会导致旧签名（如 `registerPlugin` 返回类型变化）引发 `NoSuchMethodError`。

---

## quickjs-wrapper 版本发布流程

> **镜像说明**：本仓库中的 `quickjs-wrapper` 目录仅为**镜像副本**，主维护仓库在 [github.com/iYeXin/quickjs-wrapper](https://github.com/iYeXin/quickjs-wrapper)。版本标签、多平台 CI 构建与 Release 发布均在该仓库进行，镜像副本不运行任何 CI。

1. 在主仓库（`iYeXin/quickjs-wrapper`）修改 C++ / Java 代码
2. `CHANGELOG.md` 顶部新增版本条目（`## X.Y.Z *(YYYY-MM-DD)*`）
3. `git add` + `git commit`（提交信息使用 conventional 风格，如 `fix: ...` / `feat: ...`）
4. `git tag vX.Y.Z && git push origin main vX.Y.Z` —— 标签触发多平台 CI 构建
5. 从 Releases 下载 `quickjs-java-wrapper.jar` → 同步到本仓库 `yeow-runtime/lib/` → 执行上面方式 A 的安装
6. 重新构建并安装 yeow-runtime（第 2 步）

> 镜像同步：主仓库的代码与 Release 更新后，将 `quickjs-wrapper/` 内容同步到本仓库（不含 `.git`）；`native/quickjs` 的 submodule commit 与主仓库保持一致（`git submodule update`）。提交镜像更新时需同时提交 `.gitmodules` 与 submodule gitlink。

---

## 文档维护

### Yeow-Docs（文档源）

- 按现有结构编辑：`getting-started.md` / `api/*` / `advanced.md` / `specifications/*` 等
- 相对链接保持 `.md` 后缀（站点构建时自动重写）
- 新增文档时同步更新 `README.md`（文档地图）与站点侧边栏（见下）

### yeow-doc-website（站点）

- `docs/` 是 `Yeow-Docs/zh` 的 **junction/symlink**（`npm install` 自动创建，脚本 `scripts/setup-docs.mjs`），**不要直接编辑站点内文件**
- 修改 `Yeow-Docs` 后站点立即生效；导航/侧边栏在 `.vitepress/config.mts`
- 预览：`cd yeow-doc-website && npm run dev`（站点运行在 `/v1/` 路径）
- 构建：`npm run build`

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

- `yeow-runtime`：`mvn test`（JUnit 5）——直方图、窗口聚合、检测器阈值均有单测，改动 profile 相关代码需同步更新
- 实机验证：`npm run dev` 启动测试服务器（热重载）；生产行为用 `plugins/Yeow/*.yeow.zip` 自动扫描验证

---

## 提交规范

- 遵循 conventional commits：`feat:` / `fix:` / `docs:` / `refactor:` / `chore:`
- 提交前运行 `mvn test` 与 `tsc --noEmit`（yeow-api）
- 二进制产物（`target/`、`node_modules/`、`dist/`）不提交，模板资产 jar 除外（见 `.gitignore`）
