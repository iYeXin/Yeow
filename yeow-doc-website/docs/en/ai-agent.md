# AI-Assisted Startup Guide (Agent)

For **AI agents / Vibe Coding**: How to start a Yeow plugin project.

## Yeow Project Introduction

Write Minecraft server plugins with **TypeScript / JavaScript** — runtime (QuickJS engine, Java/Paper plugin) starts **independent JS thread** for each plugin, plugin code doesn't block server main thread. Build output: Standard Paper JAR (`plugins/`) and platform-independent `.yeow.zip` (`plugins/Yeow/`).

## How to Start Project

```bash
npm create yeow@latest -- -y --ts    # Create project (-y non-interactive; --ts use TypeScript — strongly recommended)
cd my-plugin
npm install                          # Install dependencies
npm run dev                          # Start Paper development server (WebSocket hot reload + source-map error location)
```

- `--ts`: **TypeScript** (strongly recommended — complete type inference, AI/editor gets type support, eliminates static errors and model hallucinations)
- `--name=xxx`: Specify project name; without `-y` interactive language selection
- `npm run dev -- --stop=2m`: Auto-stop after 2 minutes

## Debug Workflow (AI Agent)

Headless mode suitable for AI agents/CI: Auto-accept EULA → Download server → Start → Detect load complete → Wait then command auto-ends, logs saved to disk, server process controllable:

```bash
npm run dev -- --eula --keep --timeout=2m --wait=30s --outfile=log.txt
```

- `--eula`: Auto-accept EULA; `--timeout=2m`: Load timeout (default 2m, timeout may indicate network issues, check proxy or increase timeout); `--wait=30s`: Wait after successful load (default 30s, command auto-ends when time is up); `--outfile=log.txt`: Log output file; `--keep`: **Keep server process** after command ends
- Flow: Output `Server PID` → Download/start → Detect `Done (...)! For help`视为 load complete → Wait then command ends
- Debug methods: 1. Guide user to enter test server for real testing  2. Start http-server, debug via external requests   3. Edit source code, then kill process and restart development server (headless has no hot reload)
- **After successful load check logs** (`--outfile` or console output); when no longer needed kill process by output `Server PID` (`kill <pid>`)

## Next Steps

1. Edit `src/index.ts` — register commands, events, Workers etc. (examples see [Quick Start](getting-started.md))
2. `npm run build` → `dist/<name>-<version>.jar` + `.yeow.zip`
3. Deploy: JAR to server `plugins/`; `.yeow.zip` to `plugins/Yeow/` (auto-scan load) or `/yeow install <url>`

## How to Consult Documentation

| Material                                     | URL                                             |
| -------------------------------------------- | ----------------------------------------------- |
| **Sitemap** (all page titles + summaries + URLs) | https://yexin.wiki/yeow/v1/sitemap         |
| **Documentation Package** (full Markdown, can directly feed to AI) | https://yexin.wiki/yeow/v1/docs.zip        |
| Quick Start                                  | https://yexin.wiki/yeow/v1/getting-started |
| API Reference (index)                        | https://yexin.wiki/yeow/v1/api/            |
| Advanced (architecture/thread/scheduler)     | https://yexin.wiki/yeow/v1/advanced        |
| Platform Specification (protocol layer)      | https://yexin.wiki/yeow/v1/specifications/ |

> **Strategy**: In any Harness product (Codex, OpenCode, Zcode, Trae etc.), copy **this page content or sitemap** to AI, describe your needs (e.g., "create a plugin with a /back command"), AI will guide you through project creation, development and debugging.