# Permission

Yeow provides its own permission node API — **compatible with the Paper ecosystem** (permissions.yml / LuckPerms and other permission management plugins), while also offering a **higher-priority permission check hook** for the **Yeow ecosystem** (`permissionCheck` event, cross-platform, depending only on the Yeow specification).

```js
import { registerPermission } from 'yeow-api';

const perm = registerPermission({ node: 'myplugin.home', default: 'all' });
// → { node: 'myplugin.home', default: 'all' }
```

## registerPermission

```ts
registerPermission({ node: string, default?: 'all' | 'op' | 'none' }): Permission
```

Registers a permission node (idempotent) and returns a `Permission` object (`{ node, default }`). `default` is **`'op'`** by default.

| `default` | Meaning |
|-----------|------|
| `'all'` | Everyone **has it by default** (regular players can use it; the server owner can revoke it via a permission plugin) |
| `'op'` | Only op has it by default — the **default value** |
| `'none'` | No one by default (requires authorization via a permission plugin / permissions.yml) |

- The node is **registered into the Paper permission system** (`PermissionDefault` mapping) — on the Paper platform it can be statically declared via `permissions.yml` or managed by permission management plugins such as LuckPerms
- Coarse-grained: it only declares the default; fine-grained management is delegated to permission plugins (Paper ecosystem) or `permissionCheck` (Yeow ecosystem)

## Command permissions

`registerCommand`'s `permission` accepts: a string (wrapped on the JS side as `{ node, default: 'op' }`), a permission node object (`{ node, default }`), or the return value of `registerPermission`:

```js
import { registerPermission, registerCommand } from 'yeow-api';

const homePerm = registerPermission({ node: 'myplugin.home', default: 'all' });

registerCommand('home', {
    permission: homePerm,              // or { node: 'myplugin.home', default: 'all' }, or 'myplugin.home'
    executor: (p) => { /* ... */ },
});
```

- The node is registered into the Paper permission system (manageable by permission plugins)
- **Checked at execution time** (commands don't set a Paper-side `setPermission` to intercept): the `permissionCheck` event result takes priority; when unhandled it falls back to Paper-side `hasPermission`; players without permission are shown "No permission."; tab completion is not filtered by permission

## Checking permissions

Plugins can use `player.hasPermission` anywhere:

```js
const ok = await player.hasPermission('myplugin.home');   // string
const ok2 = await player.hasPermission(homePerm);         // permission node object
```

Check flow (`permissionCheck` is triggered **only in the Yeow ecosystem**; other Java plugins' hasPermission / command execution do not go through it):

```
player.hasPermission / Yeow command execution check
  → permissionCheck event (any Yeow plugin handling it?)
      → returns { allowed }  → adopted (overrides Paper ecosystem)
      → returns nothing      → falls back to Paper-side hasPermission
```

## permissionCheck (Yeow ecosystem permission check)

Designed for **Yeow ecosystem permission management plugins** (cross-platform, depending only on the Yeow specification), and in the Yeow ecosystem it takes **priority over the Paper permission system**:

```js
import { eventOn } from 'yeow-api';

eventOn('permissionCheck', (e) => {
    const { target, node, permission } = e;   // permission: { node, default }
    if (node === 'myplugin.home' && isVip(target)) {
        return { allowed: true };   // overrides the Paper ecosystem result
    }
    // returns nothing → falls back to Paper-side hasPermission
});
```

- **Trigger scope (Yeow ecosystem only)**: `player.hasPermission` tasks + execution checks of commands registered by Yeow plugins — other Java plugins' permission checks do not go through it
- **Priority**: when a value is returned it overrides the Paper ecosystem; when multiple handlers return conflicting values, **the last one to return wins** (order of execution not guaranteed); a timeout (following the normal event timeout config `profile.callback-timeout-event-ms`, default 5s) is treated as unhandled
- Event data: `{ target, node, permission }` — `permission` is the permission object (including the node's default value)
- **⚠ Not recommended for ordinary plugins** (it fires on every permission check, hurting performance) — reserved for Yeow ecosystem permission management plugins
- **⚠ Infinite loop**: calling `hasPermission` inside a `permissionCheck` handler triggers this check again — **this can cause an infinite loop**, so avoid it (if you need to query, read your own data directly)

## Permission management

| Method | Applies to | Description |
|------|------|------|
| Paper ecosystem permission plugins (LuckPerms, etc.) / permissions.yml | Server owners (Paper ecosystem) | Nodes are registered into the Paper permission system and can be granted/revoked finely |
| `permissionCheck` event | Yeow ecosystem permission management plugins | Arbitrary logic (allowlist/time-limited/VIP, etc.), cross-platform, higher priority |
