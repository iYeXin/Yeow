# Command API

```js
import { registerCommand } from 'yeow-api';
```

## registerCommand(name, options)

| 字段          | 类型                                             | 说明       |
| ------------- | ------------------------------------------------ | ---------- |
| `executor`    | `(p: CommandPayload) => void`                    | 执行器     |
| `description` | `string`                                         | 描述       |
| `permission`  | `string \| Permission \| { node, default? }` | 权限节点（字符串兼容 / 权限节点对象 / `registerPermission` 返回值）；注册进 Paper 系权限系统供管理；**执行时检查**——`permissionCheck` 事件优先，无处理时回退 `hasPermission` |
| `aliases`     | `string[]`                                       | 别名       |
| `completer`   | `(sender, args) => string[] \| Promise<string[]>` / `ManualCompleter` | Tab 补全器（自动模式：同步返回数组立即回传；返回 Promise **不等待**（视为无补全立即释放）；异步补全请使用手动模式 `complete(res)`，见下） |

```js
// 声明权限节点 + 普通玩家默认可执行（服主可用权限插件撤销）
import { registerPermission, registerCommand } from 'yeow-api';
const homePerm = registerPermission({ node: 'myplugin.home', default: 'all' });

registerCommand('home', {
    permission: homePerm,          // 或 { node: 'myplugin.home', default: 'all' }，或 'myplugin.home'
    executor: (p) => { /* ... */ },
});
```

## ManualCompleter

手动控制补全结果释放：

```ts
interface ManualCompleter {
    manualRelease: true;
    handler: (sender: CommandSender, args: string[], complete: (result: string[]) => void) => void;
}
```

**`sender`（CommandSender）**：玩家执行时为**真正的 `Player` 对象**（异步 `sendMessage` 等全部方法）；控制台为字符串 `'CONSOLE'`：

```js
registerCommand('hello', {
    executor: async (p) => {
        if (p.sender === 'CONSOLE') { console.log('hi from console'); return; }
        await p.sender.sendMessage('Hi!');       // 异步（Player 方法）
    },
    completer: (sender, args) => ['opt1', 'opt2'],
});
```

```js
// 自动模式（默认）— 同步返回数组，立即回传
registerCommand('hello', {
    executor: (p) => p.sender.sendMessage('Hi!'),
    completer: (sender, args) => ['opt1', 'opt2'],
});

// 自动模式 + async — 返回 Promise 不等待（与事件处理一致：视为无补全，立即释放）。
// 需要异步查询请使用手动模式：
registerCommand('hello', {
    executor: (p) => p.sender.sendMessage('Hi!'),
    completer: async (sender, args) => {
        return await fetchOptions();  // 结果不会被回传（补全已释放）
    },
});

// 手动模式 — 异步补全的正确方式（回调中 complete(res) 回传结果）
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

## yeow-utils 重载式命令

```js
import { Command, CommandSchema } from 'yeow-utils';
```

`Command.create(name, options?)` 创建命令 builder，`.add()` 添加重载，`Command.register(cmd)` 注册。

### Schema 构建

| 方法                  | 参数数 | 解析结果   | 说明                          |
| --------------------- | ------ | ---------- | ----------------------------- |
| `.enum(name, values)` | 1      | `string`   | 枚举值                        |
| `.number(name)`       | 1      | `number`   | 数字                          |
| `.string(name)`       | 1      | `string`   | 字符串                        |
| `.player(name)`       | 1      | `string`   | 玩家名                        |
| `.world(name)`        | 1      | `string`   | 世界名                        |
| `.bool(name)`         | 1      | `boolean`  | `"true"` → `true`             |
| `.pos(name)`          | 3      | `number[]` | 坐标（`x,y,z`，`~` 自动解析） |
| `.angel(name)`        | 2      | `number[]` | 角度（`yaw,pitch`）           |

**参数默认必填。** 设为可选传 `false`：`.string('note', false)`。

可选参数必须位于命令末端，之后不能再加必填参数。违规在 `.add()` 时抛出运行时错误，同时 TypeScript 层面拒绝通过（`required` 为字面量类型）。

在某个参数到达可选参数部分时，会填入第一个可匹配的参数位。例如：

```js 
new CommandSchema().player('p').number('p1', false).number('p2', false)
```

`/someCmd YeXin 100` 中的 `100` 将被解析为 `p1`

我们**不建议滥用可选参数**，对于复杂的需求，建议拆分为多个重载。例如设计为一个执行入口 + 多个调用它的重载（在重载中进行参数归一化）。

`p.parsed` 类型由 `.add()` 时传入的 `schema` 自动推断——必选参数始终存在，可选参数为 `T | undefined`，无需 `as` 断言。

### CommandOptions

```ts
interface CommandOptions {
  description?: string;   // 命令描述
  permission?: string;    // 权限节点
  aliases?: string[];     // 别名
  usage?: string;         // 用法提示
  default?: (p) => void;  // 默认执行器（无重载匹配时调用）
}
```

### 示例

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
    .build(); // 显式 build() （可选）

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

Command.register(fillCmd); // 自动调用 build()
```

`usage` 在无重载匹配时自动发送用法提示。也可通过 `default` 自定义执行器。

`.build()` 锁定命令对象，之后不允许再 `.add()`。通常情况下，开发者无需显式书写，`Command.register()` 可自动 `.build()`。

### CompleterOptions

`new Completer(schema, options)` 的第二个参数按参数名覆盖补全行为：

```ts
{
    block: {
        completer: (parsed, localIdx) => customSuggestions(parsed), // 优先调用
        enum: ['minecraft:stone', 'minecraft:diamond_block'],  // 静态枚举
        default: '<block>',            // 无匹配时提示
        maxSuggestions: 50,            // 最大补全数量（默认 100）
    }
}
```

**子串匹配规则：**
- `enum` 中的值根据用户输入的子串自动过滤（如 `"torc` 可命中 `minecraft:copper_torch`）
- 达到 `maxSuggestions` 上限后截断

**多重重载补全合并：**
- 用户输入前缀参数（`from`、`to`）后，所有匹配的重载都会参与补全
- 同一位置的 `enum` 值自动合并、去重
- 用户选择一个值后，自动匹配对应重载

优先级：`completer` > `enum` > `default` > 默认类型提示

### 多值参数

`pos`（3 参数）、`angel`（2 参数）的 `enum` 和 `default` 支持数组/嵌套数组：

```ts
{
    loc: {
        enum: [['<x>'], ['<y>'], ['<z>']],
        default: '<num>',                 // 所有位置相同
    }
}
```

`completer` 的 `localIndex` 表示当前补全位置在多值参数内的索引（0、1、2）。