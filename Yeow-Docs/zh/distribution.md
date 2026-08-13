# 构建与分发

`npm run build` 一次构建产出**两种分发格式**，建议同时上传。

## 构建产物

```bash
npm run build
```

| 产物                             | 内容                                                                                           | 用途                                       |
| -------------------------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------ |
| `dist/<name>-<version>.jar`      | 模板 Bootstrap 类 + `.yeow/main.js` + `assets/` + `yeow.json` + `plugin.yml`（`depend: Yeow`） | 兼容分发：与原生 Java 插件部署方式完全一致 |
| `dist/<name>-<version>.yeow.zip` | `.yeow/main.js` + `assets/` + `yeow.json`（**无模板类**）                                      | 推荐分发：纯插件包，平台无关               |

---

## 方式一：JAR（兼容）

- 放入服务器 `plugins/` 目录即可运行，与其他 Java 插件行为一致（Paper 系会在启动时先加载前置 Yeow 运行时）
- 可在 **Modrinth / CurseForge / Hangar** 等主流平台直接分发
- 适合：
  - 使用其他 Java 插件管理器的用户
  - 需要与原生 Java 插件完全一致部署体验的场景

## 方式二：.yeow.zip（推荐）

- **平台无关**：纯 ZIP（JS 代码 + 资源 + 元信息），不依赖 Java / Paper 系——任何实现[平台规范](specifications/README.md)的运行时都能运行同一份插件。**现已支持 Paper 与 [Folia](https://papermc.io/software/folia/) 双平台**（Folia 服务器使用 Folia 版 Yeow 运行时即可，插件包与 Paper 完全通用，详见[进阶知识 · Folia](advanced/folia.md)），未来跨平台兼容性更优
- 部署方式：
  - 放入 `plugins/Yeow/`，服务器启动时**自动扫描加载**
  - 或管理员执行 `/yeow install <url>` 一键安装
  - 或 `/yeow load <url | path>` 临时/动态加载
- 适合：
  - 面向 Yeow 生态的分发（用户已装 Yeow 运行时）
  - 多平台目标（未来非 Paper 系运行时）

## 建议：同时上传两种格式

在 Modrinth 等平台一个项目可上传多个文件（不同版本/格式）。推荐：

1. 上传 **`.yeow.zip`** 作为首选文件（标注推荐）
2. 上传 **`.jar`** 作为兼容文件
3. 标记 `Folia Support`
4. 在项目描述中注明前置要求：**需要安装 Yeow 插件作为前置**（https://modrinth.com/plugin/yeow）
5. 建议同时上传 `yeow.config.json` 或 README 中说明插件声明的[权限](getting-started.md#权限声明)，便于用户评估

用户根据自身服务器情况二选一：装了 Yeow 运行时 → 用 `.yeow.zip`；纯兼容场景 → 用 `.jar`。

---

## 一键安装：`/yeow install <url>`

服务器管理员可在游戏内/控制台直接安装你的插件：

```
/yeow install <your-plugin-url>
```

- **前置**：服务器必须已安装 [Yeow 插件](https://modrinth.com/plugin/yeow)（运行时），否则没有 `/yeow` 命令
- `<url>` 必须**直接指向 `.yeow.zip` 文件**（不是网页页面）。你可以托管在自己的站点（如 GitHub Release）或直接使用 Modrinth 的直链：
  - 网页端：文件下载按钮 → 右键复制链接（通常为 `https://cdn.modrinth.com/data/<project>/versions/<version>/<file>.yeow.zip`）
- 行为：下载 → 以标准格式 `<name>-<version>.yeow.zip` 保存到 `plugins/Yeow/` → 立即加载（重启后自动扫描保持安装）
- 配套命令：
  - `/yeow update <url>` — 替换旧版本（旧包自动移入 `plugins/Yeow/.backup/`）
  - `/yeow uninstall <plugin>` — 卸载并备份包（数据目录手动清理）
  - `/yeow load <url>` — 临时加载（不持久化）

### 服务器管理员速查

```
1. 安装 Yeow 运行时（一次性）：把 yeow-runtime jar 放入 plugins/ 并重启
2. /yeow install https://cdn.modrinth.com/.../my-plugin-1.0.0.yeow.zip   ← 一键安装
3. /yeow update <同款直链>   ← 升级
4. /yeow uninstall my-plugin ← 移除
```

---

## 分发检查清单

- [ ] `npm run build` 生成 `.jar` 与 `.yeow.zip`，两者均上传
- [ ] 项目描述注明前置：Yeow 插件（https://modrinth.com/plugin/yeow）
- [ ] 说明插件声明的[权限节点](getting-started.md#权限声明)（`fs:server.*` / `fs:outer.*`、`http:*`、`service:registerNative`、`assets:extract` 等）
- [ ] 提供 `/yeow install <直链>` 一键安装示例
- [ ] 说明数据目录位置（`plugins/<name>/`）与备份机制（`plugins/Yeow/.backup/`）
