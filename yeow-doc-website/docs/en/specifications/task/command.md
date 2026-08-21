# Command Tasks

Command registration, execution, and Tab completion.

---

## `command.register`

Registers a command.

- **Request**:

```json
{
  "pluginName": "<name>",
  "commandName": "<name>",
  "callbackId": "<cbId>",
  "completerCbId": "<cbId>",
  "description": "<text>",
  "usage": "<text>",
  "permission": { "node": "<节点>", "default": "all | op | none" },
  "aliases": ["<alias1>", "<alias2>"]
}
```

| Field | Required | Description |
|------|------|------|
| `pluginName` | Yes | Owning plugin name |
| `commandName` | Yes | Command name (without `/`) |
| `callbackId` | Yes | Command execution callback ID (`persistent: true`) |
| `completerCbId` | No | Tab completion callback ID (`persistent: true`) |
| `description` | No | Command description |
| `usage` | No | Usage hint (e.g. `"/cmd <arg1> <arg2>"`) |
| `permission` | No | Permission node object `{ "node", "default" }` (default: `"all"` all players have by default / `"op"` ops by default / `"none"`; wrapping a string into an object is done on the JS side, Java does no compatible conversion). The node is registered into the Paper permission system (manageable by permission plugins); **checked at execution time**: the `permissionCheck` event takes priority, falling back to `hasPermission` when unhandled |
| `aliases` | No | Alias list |

- **Return**: `boolean`

When a player (or the console) executes the command, the runtime delivers data to `callbackId` through the `cb` channel:

```json
{
  "sender": { "name": "<name>", "uuid": "<uuid>", "isPlayer": true },
  "args": ["<arg1>", "<arg2>"],
  "label": "<commandName>"
}
```

- `args`: the array of command arguments typed by the player
- `label`: the command name actually used (may be an alias)
- `isPlayer`: `false` indicates console execution, in which case `uuid` is empty

**JS-side sender conversion** (yeow-api): `isPlayer: true` → a real `Player` object (with all methods such as asynchronous `sendMessage`); otherwise → the string `'CONSOLE'`. Executor check: `p.sender === 'CONSOLE'`.

The executor may be an async function and use `await` internally to call asynchronous APIs.

## `command.dispatch`

Executes a command as the console.

- **Request**: `{ "command": "<cmd>" }`
- **Return**: `boolean`

Behavior is equivalent to calling `dispatchCommand` or `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)`.

## `command.unregisterAll`

Unregisters all commands of the specified plugin.

- **Request**: `{ "pluginName": "<name>" }`
- **Return**: `true`

Usually called before a plugin hot-reload to ensure that the old code's commands are removed correctly.

---

## Tab Completion

### Triggering Completion

When `completerCbId` was passed at `command.register` time, and the player presses the Tab key, the runtime delivers a completion request to that ID through the `cb` channel:

```json
{
  "sender": { "name": "<name>", "uuid": "<uuid>", "isPlayer": true },
  "args": ["<typed arg1>", "<typing arg2>"]
}
```

- The last element of `args` is the argument currently being typed (may be an empty string)
- The completer **must** return results through the `command.tabComplete` task

### `command.tabComplete`

The completion response task.

- **Request**:
```json
{
  "type": "command.tabComplete",
  "params": {
    "callbackId": "<cbId>",
    "completions": ["<suggestion1>", "<suggestion2>"]
  }
}
```

`callbackId` must exactly match the `completerCbId` passed at `command.register` time.

- **Return**: produces no return value (`true`)

### Asynchronous Completion

The completer can be an async function. If it returns a Promise, the completion is automatically submitted as an empty completion list at the first `await` (equivalent to `complete([])`); the asynchronous logic continues in the background but has no effect on the current Tab completion.

### Manual Completion Mode

If the application code needs to fetch data asynchronously before completing, it can choose not to return a completion list immediately, and instead call `command.tabComplete` on its own to submit the result after the asynchronous operation finishes.
