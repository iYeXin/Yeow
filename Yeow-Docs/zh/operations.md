# 运行时运维

> 服主 / 服务器管理员视角：`/yeow` 管理命令、运行时配置（`config.yml`）、部署形态速查。插件开发者通常只需要[快速开始](getting-started.md)与[权限声明](permissions.md)。

## 插件管理命令

运行时提供 `/yeow` 命令，支持 Tab 补全：

> **权限**：`yeow.admin`（管理命令：load / install / update / unload / uninstall / reload）与 `yeow.profile`（性能命令）均由运行时注册，**默认授予 OP**（`default: op`，可在权限插件中单独调整）。

| 命令                                     | 说明                                                                                                                                                                                 |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `/yeow load <path\|url>`                 | **临时**加载插件包（`.yeow.zip` 或 JAR）。`<path>` 为本地路径；`<url>` 为直接指向 `.yeow.zip` 的下载地址（下载到缓存，不落盘到 `plugins/Yeow/`，重启后不保留）                       |
| `/yeow install <url>`                    | 下载并**安装**：重命名为标准格式 `<name>-<version>.yeow.zip` 保存到 `plugins/Yeow/`（下次启动自动扫描加载），并立即加载                                                              |
| `/yeow update <url>`                     | 下载并**强制替换**旧版本：扫描 `plugins/Yeow/` 内所有 `.yeow.zip`，按 `yeow.json` 中的 `name` 匹配旧版本 → 旧文件移入 `plugins/Yeow/.backup/` → 写入新版本；若插件正在运行则自动重载 |
| `/yeow unload <plugin\|all>`             | 卸载插件（与热重载相同的卸载逻辑，5s 强制终止）                                                                                                                                      |
| `/yeow uninstall <plugin>`               | 卸载并把 `plugins/Yeow/` 下对应 `.yeow.zip` 移入 `plugins/Yeow/.backup/`（数据目录 `plugins/<plugin>/` 需手动清理）                                                                  |
| `/yeow reload <plugin\|all> [path\|url]` | 重新加载。`<plugin>` 可选 `path` 或 `url` 从新来源加载（URL 为临时，不持久化）；`all` 按原路径全部重载                                                                               |
| `/yeow approve <code>`                   | 用控制台提示中的**一次性批准码**批准插件（声明原生服务的插件被拒后，批准会**自动加载**它；code 用后作废，关闭时写回 `approve.json`）                                                                |
| `/yeow profile`                          | 性能快照（需 `profile.enabled: true` 开启全量分析）                                                                                                                                  |
| `/yeow track <plugin> <seconds>`         | 单插件深度追踪（需 `profile.enabled: true`）                                                                                                                                         |

```bash
/yeow load plugins/Yeow/my-plugin-1.0.0.yeow.zip              # 服务器运行时动态加载
/yeow load https://example.com/my-plugin.yeow.zip             # 直接下载加载（临时，重启不保留）
/yeow install https://example.com/my-plugin.yeow.zip          # 下载并安装到 plugins/Yeow/（标准格式）
/yeow update https://example.com/my-plugin.yeow.zip           # 替换旧版本（旧包备份到 plugins/Yeow/.backup/）
/yeow reload my-plugin https://example.com/my-plugin.yeow.zip # 从新来源重载（临时）
```

> **`.yeow.zip` 优先规则**：Yeow 的管理命令（load / install / update / reload）以 `.yeow.zip` 为主要支持对象（JAR 仅 `load`/`reload` 支持本地路径）。**如果一个插件同时部署了模板 JAR（`plugins/<name>.jar`）和 `.yeow.zip`（`plugins/Yeow/`），两者会注册同一个插件名，产生冲突警告（重复加载被拒绝）**——需要手动解决：保留其一、移除另一个。

重复加载同名插件时输出警告并拒绝（无论通过自动扫描、命令还是模板 JAR 注册）。

## 运行时配置

首次启动后在 `plugins/Yeow/runtime/config.yml` 生成。Paper 平台参数在顶层，Folia 平台参数在 `folia:` 节（语义不同，互不混淆）：

```yaml
tick-budget-ms: 20               # 每 tick 任务时间预算（ms）
priority-ratios: [0.5, 0.3, 0.2] # 三级优先级比例
auto-demote: true                # 自动降级
demote-threshold: 200            # 降级阈值（次/秒）
idle-spin-us: 100                # 空闲自旋（us），0 关闭
task-sync-timeout-ms: 10000      # 同步 task 调用超时（ms），受服务器负载影响大，默认 10s

native-service-require-approval: true  # 声明原生服务的插件需要批准（默认 true；false = 默认批准）。
                                       # 运行时直接修改即生效（config.yml 为信任源）。

profile:
  enabled: false                 # 全量性能分析（逐任务采集），默认关闭
  warnings-enabled: true         # 预警引擎（默认开启，与全量分析独立）
  warn-cooldown-seconds: 1800    # 同类警告冷却时间（30min）
  latency-warn-threshold-ms: 200 # 心跳超时阈值（ms）
  event-slow-threshold-ms: 2000  # 事件响应警告阈值（ms；超时仍为 5000）
  tab-slow-threshold-ms: 500     # 补全响应警告阈值（ms；超时仍为 1000）
  callback-timeout-event-ms: 5000 # 事件回调等待上限（ms，运行时生效）
  callback-timeout-tabcomplete-ms: 1000 # 命令补全等待上限（ms，运行时生效）
  suspend-warn-seconds: 30       # 插件挂起检测阈值（s）
  backlog-threshold: 35          # 扩容信号：40 tick 中积压次数阈值
  backlog-window-ticks: 40
  scheduler-saturation-pct: 80   # 调度饱和告警百分比

  scaler:
    enabled: true                # 动态扩容
    expansion-factor: 1.3        # 每次扩容倍数
    max-multiplier: 3.0          # 最大扩容上限

folia:
  tick-budget-ms: 20             # 每 50ms 窗口内调度器活跃的物理时间上限（ms）
  max-inflight: 100              # 同时投递未完成任务上限
  scheduler-idle-wait-us: 2000   # 调度循环空闲阻塞等待上限（us）
  migration-threshold: 2         # 热点迁移阈值（连续非本区域任务数）
```

> **运行时配置目录写保护**：`plugins/Yeow/runtime/` 目录（含 `config.yml` / `approve.json`）受 fs 写保护——插件无法通过 fs API 修改其中的文件。

## 告警与性能分析

运行时健康检测（心跳超时、事件/补全超时、插件挂起、队列积压、调度饱和）与性能分析命令的完整说明见[运行时警告指南](runtime-warning.md)。

## 部署形态速查

| 形态 | 放置位置 | 说明 |
| --- | --- | --- |
| 标准 JAR | `plugins/` | 与原生 Java 插件部署一致（需同时安装 Yeow 运行时） |
| `.yeow.zip` | `plugins/Yeow/` | 启动自动扫描加载；**平台无关**（Paper / Folia 通用） |
| `/yeow install <url>` | `plugins/Yeow/` | 一键下载安装，重启保留 |

分发与检查清单见[构建与分发](distribution.md)。
