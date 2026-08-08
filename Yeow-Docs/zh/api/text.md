# 文本与 MiniMessage

Yeow 的所有面向玩家的文本（聊天消息、广播、标题、ActionBar、MOTD、GUI 标题、物品名/lore 等）统一经 **MiniMessage** 解析——**MiniMessage 优先，回退 legacy § 格式**。

> MiniMessage 是 Adventure（Paper 的文本库）的标记语言，支持颜色、装饰、点击/悬停事件等。语法参考：[Paper 官方 MiniMessage 文档](https://docs.papermc.io/adventure/minimessage/)。

```js
// 聊天消息（MiniMessage 标记）
await player.sendMessage('<red>你死了！</red> <click:run_command:/back><aqua><u>/back</u></aqua></click>');

// 广播
await broadcast('<gradient:red:gold>服务器公告</gradient>');

// MOTD（serverPing 回写）
eventOn('serverPing', () => ({ motd: '<green>第一行</green><newline><aqua>第二行</aqua>' }));
```

## 转义规则

Yeow 将文本视为 **MiniMessage 字面量**——解析完全遵循 [MiniMessage 规范](https://docs.papermc.io/adventure/minimessage/)：`\` 只转义标签字符（`\\` → 字面 `\`、`\<` → 字面 `<`），**`\n` 等不是 MiniMessage 转义，按字面处理**。同时确保**真实控制字符**（真实换行/tab 等）在转换中不消失：

| 输入                            | 结果                                                              |
| ------------------------------- | ----------------------------------------------------------------- |
| 真实换行（换行字符本身）        | **真实换行**（不消失）                                            |
| MiniMessage `<newline>` 标签    | **真实换行**                                                      |
| 字面 `\n`（反斜杠 + n，两字符） | **字面保留**（MiniMessage 不转义 `\n`）                           |
| `\\`（两个反斜杠）              | 字面 `\`（MiniMessage 转义）                                      |
| `\<`（反斜杠 + 小于号）         | 字面 `<`（MiniMessage 转义，如 `\<red>` 显示 `<red>` 不解析颜色） |
| 真实 tab                        | 真实 tab（不消失）                                                |

要点：

- **表达换行**：用**真实换行**或 MiniMessage 的 `<newline>` 标签
- **不要依赖 `\n`（两字符）变换行**——它是字面文本
- 真实控制字符（换行/tab/回车等）经转换管线全程保留，不会消失
- JSON 解析器已经自动进行了转义（JSON.parse('{"t":"a\nb"}') 会解析 \n 为真正的换行符）
- `§` 颜色码仅在 legacy 回退路径生效

### 需要字面 `\n` 变真实换行时

如果插件**允许用户书写字面 `\n` 表达换行**（如从 motds.txt 中读得的文本），插件需自行转换：

```js
const motd = userText.replace(/\\n/g, '\n');   // 字面 \n → 真实换行
```

即：把"用户输入的转义约定"翻译成 Yeow 的真实换行语义。

## Message 对象（可翻译组件）

涉及文本的载荷支持 **Message 对象**——可翻译组件（客户端按语言本地化）或纯文本：

```js
// 可翻译组件：Minecraft 翻译键 + 参数
{ key: 'death.attack.player', args: ['Steve', 'Zombie'] }

// 纯文本（MiniMessage/legacy 解析）
{ text: '<red>你死了</red>' }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `key` | string | Minecraft 翻译键（如 `death.attack.player`）；存在时构造可翻译组件 |
| `args` | (string \| number \| Message)[] | 翻译参数（可选，可嵌套 Message） |
| `text` | string | 纯文本（`key` 缺失时使用） |

- `key` 与 `text` 同时存在时 **`key` 优先**
- 纯字符串等价于 `{ text: "<string>" }`
- 发送消息 API（`player.sendMessage`、`player.sendActionBar`、`broadcast`）均接受
- 事件侧：`playerDeath` 的 `deathMessage` 直接就是 Message 对象（`{key, args}` 或 `{text}`）

```js
// 死亡消息本地化转发——Message 对象直接透传
eventOn('playerDeath', (e) => {
    broadcast(e.deathMessage);   // 客户端按语言本地化（可翻译组件）或直接显示（纯文本）
});
```
