# Command API

```js
import { registerCommand } from 'yeow-api';
```

## registerCommand(name, options)

| Field       | Type                                             | Description  |
| ----------- | ------------------------------------------------ | ------------ |
| `executor`  | `(p: CommandPayload) => void`                    | Executor     |
| `description` | `string`                                       | Description  |
| `permission` | `string \| Permission \| { node, default? }` | Permission node (string compatible / permission node object / `registerPermission` return value); registered into Paper system permission system for management; **checked at execution** — `permissionCheck` event priority, falls back to `hasPermission` when no handler |
| `aliases`   | `string[]`                                       | Aliases      |
| `completer` | `(sender, args) => string[] \| Promise<string[]>` / `ManualCompleter` | Tab completer (auto mode: synchronous return array immediately echoed; returning Promise **doesn't wait** (treated as no completion, immediately released); async completion use manual mode `complete(res)`, see below) |

```js
// Declare permission node + regular players executable by default (server admin can revoke with permission plugin)
import { registerPermission, registerCommand } from 'yeow-api';
const homePerm = registerPermission({ node: 'myplugin.home', default: 'all' });

registerCommand('home', {
    permission: homePerm,          // Or { node: 'myplugin.home', default: 'all' }, or 'myplugin.home'
    executor: (p) => { /* ... */ },
});
```

## ManualCompleter

Manual control of completion result release:

```ts
interface ManualCompleter {
    manualRelease: true;
    handler: (sender: CommandSender, args: string[], complete: (result: string[]) => void) => void;
}
```

**`sender` (CommandSender)**: When player executes it's **real `Player` object** (async `sendMessage` etc. all methods); console is string `'CONSOLE'`:

```js
registerCommand('hello', {
    executor: async (p) => {
        if (p.sender === 'CONSOLE') { console.log('hi from console'); return; }
        await p.sender.sendMessage('Hi!');       // Async (Player method)
    },
    completer: (sender, args) => ['opt1', 'opt2'],
});
```

```js
// Auto mode (default) — Synchronous return array, immediately echoed
registerCommand('hello', {
    executor: (p) => p.sender.sendMessage('Hi!'),
    completer: (sender, args) => ['opt1', 'opt2'],
});

// Auto mode + async — Returning Promise doesn't wait (consistent with event handling: treated as no completion, immediately released).
// For async queries use manual mode:
registerCommand('hello', {
    executor: (p) => p.sender.sendMessage('Hi!'),
    completer: async (sender, args) => {
        return await fetchOptions();  // Result won't be echoed (completion already released)
    },
});

// Manual mode — Correct way for async completion (callback complete(res) echoes result)
registerCommand('hello', {
    executor: (p) => p.sender.sendMessage('Hi!'),
    completer: {
        manualRelease: true,
        handler: (sender, args, complete) => {
            fetchOptions(opts => complete(opts));
        },
    },
});
```

## yeow-command Overload Commands

[yeow-command](https://www.npmjs.com/package/yeow-command) (npm) provides typed overload command builder:

```js
import { Command, CommandSchema } from 'yeow-command';
```

`Command.create(name, options?)` creates command builder, `.add()` adds overload, `Command.register(cmd)` registers.

### Schema Building

| Method                | Params | Parse Result | Description                     |
| --------------------- | ------ | ------------ | ------------------------------- |
| `.enum(name, values)` | 1      | `string`     | Enum values                     |
| `.number(name)`       | 1      | `number`     | Number                          |
| `.string(name)`       | 1      | `string`     | String                          |
| `.player(name)`       | 1      | `string`     | Player name                     |
| `.world(name)`        | 1      | `string`     | World name                      |
| `.bool(name)`         | 1      | `boolean`    | `"true"` → `true`               |
| `.pos(name)`          | 3      | `number[]`   | Coordinates (`x,y,z`, `~` auto-parsed) |
| `.angel(name)`        | 2      | `number[]`   | Angle (`yaw,pitch`)             |

**Parameters are required by default.** To make optional pass `false`: `.string('note', false)`.

Optional parameters must be at command end, cannot add required parameters after. Violation throws runtime error at `.add()`, also TypeScript level rejects (`required` is literal type).

When a parameter reaches optional parameter section, fills first matchable parameter position. For example:

```js 
new CommandSchema().player('p').number('p1', false).number('p2', false)
```

`100` in `/someCmd YeXin 100` will be parsed as `p1`

We **don't recommend abusing optional parameters**, for complex needs, recommend splitting into multiple overloads. For example design as one execution entry + multiple overloads calling it (parameter normalization in overloads).

`p.parsed` type automatically inferred by `schema` passed at `.add()` — required parameters always present, optional parameters are `T | undefined`, no `as` assertion needed.

### CommandOptions

```ts
interface CommandOptions {
  description?: string;   // Command description
  permission?: string | Permission | PermissionOptions;  // Permission node: string or { node, default? } object / registerPermission return value (same as registerCommand)
  aliases?: string[];     // Aliases
  usage?: string;         // Usage hint
  default?: (p) => void;  // Default executor (called when no overload matches)
}
```

### Example

```js
import { getBlocks } from 'yeow-api';
const blocks = await getBlocks();

const healCmd = Command.create('heal', { description: 'Heal a player' })
    .add(
        new CommandSchema().number('amount', false),
        (p) => {
            const hp = p.parsed.amount || 20;  // number | undefined ✅
            const player = Player.getSync(p.sender.uuid);
            if (player) player.health = hp;
        },
        { amount: { enum: ['1', '2', '5', '10', '20'] } },
    )
    .build(); // Explicit build() (optional)

Command.register(healCmd);

const fillCmd = Command.create('fill2', {
    description: 'Fill blocks between two positions',
    usage: '/fill2 <from> <to> <block>',
})
    .add(
        new CommandSchema().pos('from').pos('to').string('block'),
        (p) => {
            const [x1, y1, z1] = p.parsed.from;  // number[] ✅
            const [x2, y2, z2] = p.parsed.to;    // number[] ✅
        },
        { block: { enum: blocks } },
    )
    .add(
        new CommandSchema().pos('to').string('block'),
        (p) => {
            const [x2, y2, z2] = p.parsed.to;    // number[] ✅
        },
        { block: { enum: blocks } },
    );

Command.register(fillCmd); // Auto-calls build()
```

`usage` automatically sends usage hint when no overload matches. Can also customize executor via `default`.

`.build()` locks command object, after which no more `.add()` allowed. Usually, developers don't need to explicitly write, `Command.register()` can auto `.build()`.

### CompleterOptions

`new Completer(schema, options)` second parameter overrides completion behavior by parameter name:

```ts
{
    block: {
        completer: (parsed, localIdx) => customSuggestions(parsed), // Priority call
        enum: ['minecraft:stone', 'minecraft:diamond_block'],  // Static enum
        default: '<block>',            // Hint when no match
        maxSuggestions: 50,            // Max completion count (default 100)
    }
}
```

**Substring matching rules:**
- `enum` values filtered by user input substring (e.g., `"torc` can match `minecraft:copper_torch`)
- Truncated after reaching `maxSuggestions` limit

**Multiple overload completion merging:**
- After user inputs prefix parameters (`from`, `to`), all matching overloads participate in completion
- `enum` values at same position automatically merged, deduplicated
- After user selects a value, automatically matches corresponding overload

Priority: `completer` > `enum` > `default` > default type hint

### Multi-value Parameters

`pos` (3 parameters), `angel` (2 parameters) `enum` and `default` support arrays/nested arrays:

```ts
{
    loc: {
        enum: [['<x>'], ['<y>'], ['<z>']],
        default: '<num>',                 // Same for all positions
    }
}
```

`completer`'s `localIndex` indicates current completion position index within multi-value parameter (0, 1, 2).