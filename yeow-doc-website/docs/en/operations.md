# Runtime Operations

> Server owner / server admin perspective: `/yeow` management commands, runtime configuration (`config.yml`), deployment form quick reference. Plugin developers usually only need [Quick Start](getting-started.md) and [Permission Declaration](permissions.md).

## Plugin Management Commands

Runtime provides `/yeow` command with Tab completion support:

> **Permissions**: `yeow.admin` (management commands: load / install / update / unload / uninstall / reload) and `yeow.profile` (performance commands) are both registered by runtime, **granted to OP by default** (can be individually adjusted in permission plugins).

| Command                                    | Description                                                                                                                                                                            |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/yeow load <path\|url>`                   | **Temporarily** load plugin package (`.yeow.zip` or JAR). `<path>` is local path; `<url>` is direct download link to `.yeow.zip` (downloads to cache, not saved to `plugins/Yeow/`, not retained after restart) |
| `/yeow install <url>`                      | Download and **install**: Rename to standard format `<name>-<version>.yeow.zip` and save to `plugins/Yeow/` (auto-scanned and loaded on next startup), and load immediately            |
| `/yeow update <url>`                       | Download and **force replace** old version: Scan all `.yeow.zip` in `plugins/Yeow/`, match old version by `name` in `yeow.json` → Move old file to `plugins/Yeow/.backup/` → Write new version; if plugin is running, auto-reload |
| `/yeow unload <plugin\|all>`               | Unload plugin (same unload logic as hot reload, 5s forced termination)                                                                                                                 |
| `/yeow uninstall <plugin>`                 | Unload and move corresponding `.yeow.zip` from `plugins/Yeow/` to `plugins/Yeow/.backup/` (data directory `plugins/<plugin>/` needs manual cleanup)                                    |
| `/yeow reload <plugin\|all> [path\|url]`   | Reload. `<plugin>` can optionally load from new source with `path` or `url` (URL is temporary, not persisted); `all` reloads all by original path                                       |
| `/yeow approve <code>`                     | Approve plugin with **one-time approval code** from console prompt (plugins declaring native services are refused after approval **automatically loads** it; code invalidated after use, written to `approve.json` on shutdown) |
| `/yeow profile`                            | Performance snapshot (requires `profile.enabled: true` to enable full analysis)                                                                                                         |
| `/yeow track <plugin> <seconds>`           | Single plugin deep tracking (requires `profile.enabled: true`)                                                                                                                          |

```bash
/yeow load plugins/Yeow/my-plugin-1.0.0.yeow.zip              # Runtime dynamic loading
/yeow load https://example.com/my-plugin.yeow.zip             # Direct download load (temporary, not retained after restart)
/yeow install https://example.com/my-plugin.yeow.zip          # Download and install to plugins/Yeow/ (standard format)
/yeow update https://example.com/my-plugin.yeow.zip           # Replace old version (old package backed up to plugins/Yeow/.backup/)
/yeow reload my-plugin https://example.com/my-plugin.yeow.zip # Reload from new source (temporary)
```

> **`.yeow.zip` Priority Rule**: Yeow's management commands (load / install / update / reload) primarily support `.yeow.zip` (JAR only supports local path for `load`/`reload`). **If a plugin has both template JAR (`plugins/<name>.jar`) and `.yeow.zip` (`plugins/Yeow/`) deployed, both register the same plugin name, causing conflict warning (duplicate loading refused)** — needs manual resolution: keep one, remove the other.

Duplicate loading of same-name plugin outputs warning and refuses (regardless of auto-scan, command, or template JAR registration).

## Runtime Configuration

Generated after first startup at `plugins/Yeow/runtime/config.yml`. Paper platform parameters at top level, Folia platform parameters in `folia:` section (different semantics, not confused):

```yaml
tick-budget-ms: 20               # Per tick task time budget (ms)
priority-ratios: [0.5, 0.3, 0.2] # Three-level priority ratios
auto-demote: true                # Automatic demotion
demote-threshold: 200            # Demotion threshold (times/second)
idle-spin-us: 100                # Idle spin (us), 0 disables
task-sync-timeout-ms: 10000      # Synchronous task call timeout (ms), greatly affected by server load, default 10s

util:
  max-input-bytes: 268435456     # util channel single input limit (raw bytes, default 256 MiB)
  max-output-bytes: 268435456    # gzip decompression output limit (anti-compression bomb, default 256 MiB)

native-service-require-approval: true  # Plugins declaring native services require approval (default true; false = approve by default).
                                       # Runtime modification takes effect immediately (config.yml is trusted source).

profile:
  enabled: false                 # Full performance analysis (per-task collection), disabled by default
  warnings-enabled: true         # Early warning engine (enabled by default, independent of full analysis)
  warn-cooldown-seconds: 1800    # Same-type warning cooldown (30min)
  latency-warn-threshold-ms: 200 # Heartbeat timeout threshold (ms)
  event-slow-threshold-ms: 2000  # Event response warning threshold (ms; timeout still 5000)
  tab-slow-threshold-ms: 500     # Completion response warning threshold (ms; timeout still 1000)
  callback-timeout-event-ms: 5000 # Event callback wait limit (ms, runtime effective)
  callback-timeout-tabcomplete-ms: 1000 # Command completion wait limit (ms, runtime effective)
  suspend-warn-seconds: 30       # Plugin hang detection threshold (s)
  backlog-threshold: 35          # Expansion signal: backlog count threshold in 40 ticks
  backlog-window-ticks: 40
  scheduler-saturation-pct: 80   # Scheduler saturation alert percentage

  scaler:
    enabled: true                # Dynamic expansion
    expansion-factor: 1.3        # Expansion multiplier each time
    max-multiplier: 3.0          # Maximum expansion limit

folia:
  tick-budget-ms: 20             # Maximum physical time scheduler active within 50ms window (ms)
  max-inflight: 100              # Maximum concurrent undelivered tasks
  scheduler-idle-wait-us: 2000   # Scheduler loop idle blocking wait limit (us)
  migration-threshold: 2         # Hot-spot migration threshold (consecutive non-local tasks)
```

> **Runtime Configuration Directory Write Protection**: `plugins/Yeow/runtime/` directory (including `config.yml` / `approve.json`) has fs write protection — plugins cannot modify files in it via fs API.

## Alerts and Performance Analysis

Complete documentation for runtime health detection (heartbeat timeout, event/completion timeout, plugin hang, queue backlog, scheduler saturation) and performance analysis commands see [Runtime Warning Guide](runtime-warning.md).

## Deployment Form Quick Reference

| Form                  | Location        | Description                                            |
| --------------------- | --------------- | ------------------------------------------------------ |
| Standard JAR          | `plugins/`      | Same as native Java plugin deployment (requires Yeow runtime installed simultaneously) |
| `.yeow.zip`           | `plugins/Yeow/` | Auto-scanned and loaded on startup; **platform-independent** (Paper / Folia compatible) |
| `/yeow install <url>` | `plugins/Yeow/` | One-click download and install, retained after restart |

Distribution and checklist see [Build & Distribution](distribution.md).