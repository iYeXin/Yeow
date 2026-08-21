# Build & Distribution

`npm run build` produces **two distribution formats** in one build, recommended to upload both.

## Build Artifacts

```bash
npm run build
```

| Artifact                         | Content                                                                                          | Purpose                                      |
| -------------------------------- | ------------------------------------------------------------------------------------------------ | -------------------------------------------- |
| `dist/<name>-<version>.jar`      | Template Bootstrap class + `.yeow/main.js` + `assets/` + `yeow.json` + `plugin.yml` (`depend: Yeow`) | Compatible distribution: Same deployment as native Java plugins |
| `dist/<name>-<version>.yeow.zip` | `.yeow/main.js` + `assets/` + `yeow.json` (**no template class**)                               | Recommended distribution: Pure plugin package, platform-independent |

---

## Method 1: JAR (Compatible)

- Place in server `plugins/` directory to run, same behavior as other Java plugins (Paper series will load the prerequisite Yeow runtime on startup)
- Can be distributed directly on major platforms like **Modrinth / CurseForge / Hangar**
- Suitable for:
  - Users using other Java plugin managers
  - Scenarios requiring identical deployment experience as native Java plugins

## Method 2: .yeow.zip (Recommended)

- **Platform-independent**: Pure ZIP (JS code + resources + metadata), no Java / Paper series dependency — any runtime implementing the [Platform Specification](specifications/README.md) can run the same plugin. **Now supports Paper and [Folia](https://papermc.io/software/folia/) dual-platform** (Folia servers only need to use Folia version Yeow runtime, plugin packages are completely compatible with Paper, see [Advanced Knowledge · Folia](advanced/folia.md)), with better cross-platform compatibility in the future
- Deployment methods:
  - Place in `plugins/Yeow/`, **automatically scanned and loaded** on server startup
  - Or admin executes `/yeow install <url>` for one-click installation
  - Or `/yeow load <url | path>` for temporary/dynamic loading
- Suitable for:
  - Distribution targeting Yeow ecosystem (users have Yeow runtime installed)
  - Multi-platform targets (future non-Paper series runtimes)

Users choose one based on their server situation: Have Yeow runtime installed → use `.yeow.zip`; pure compatibility scenario → use `.jar`.

---

## One-Click Install: `/yeow install <url>`

Server admins can install your plugin directly in-game/console:

```
/yeow install <your-plugin-url>
```

- **Prerequisite**: Server must have [Yeow Plugin](https://hangar.papermc.io/iYeXin/Yeow/versions) (runtime) installed, otherwise there's no `/yeow` command
- `<url>` must **directly point to a `.yeow.zip` file** (not a web page). You can host on your own site (like GitHub Release) or use Modrinth's direct link:
  - Web interface: File download button → Right-click copy link (usually `https://cdn.modrinth.com/data/<project>/versions/<version>/<file>.yeow.zip`)
- Behavior: Download → Save in standard format `<name>-<version>.yeow.zip` to `plugins/Yeow/` → Load immediately (auto-scan maintains installation after restart)
- Companion commands:
  - `/yeow update <url>` — Replace old version (old package automatically moved to `plugins/Yeow/.backup/`)
  - `/yeow uninstall <plugin>` — Uninstall and backup package (data directory manual cleanup)
  - `/yeow load <url>` — Temporary load (not persistent)

### Server Admin Quick Reference

```
1. Install Yeow runtime (one-time): Place yeow-runtime jar in plugins/ and restart
2. /yeow install https://cdn.modrinth.com/.../my-plugin-1.0.0.yeow.zip   ← One-click install
3. /yeow update <same direct link>   ← Upgrade
4. /yeow uninstall my-plugin ← Remove
```

---

## Distribution Checklist

- [ ] `npm run build` generates `.jar` and `.yeow.zip`, both uploaded
- [ ] Project description notes prerequisite: Yeow Plugin (https://hangar.papermc.io/iYeXin/Yeow/versions)
- [ ] Explain plugin's declared [permission nodes](permissions.md) (`fs:server.*` / `fs:outer.*`, `http:*`, `service:registerNative` etc.; assets channel has no permission interception)
- [ ] Provide `/yeow install <direct link>` one-click installation example
- [ ] Explain data directory location (`plugins/<name>/`) and backup mechanism (`plugins/Yeow/.backup/`)