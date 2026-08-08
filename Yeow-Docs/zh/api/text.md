# 文本与 MiniMessage

Yeow 的所有面向玩家的文本（聊天消息、广播、标题、ActionBar、MOTD、GUI 标题、物品名/lore 等）统一经 **MiniMessage** 解析——**MiniMessage 优先，含 `§` 时回退 legacy § 格式**。

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

Yeow 的文本管线（`TextUtil`）对**字面反斜杠序列**做保护——`\` 不是转义字符，不会被隐式转换：

| 输入 | 结果 |
|------|------|
| 真实换行 / `<newline>` 标签 | **真实换行** |
| 字面 `\n`（反斜杠 + n，两字符） | **字面保留**（不会变成换行） |
| 字面 `\b`、`\t`、`\r` 等 | **字面保留**（不会变成退格/tab 等） |
| `C:\new\data`（Windows 路径） | **原样保留**（`\n` 是路径的一部分） |
| `\\`（两个反斜杠） | 两个字面反斜杠（不折叠） |

要点：

- 表达换行请用**真实换行**（如 `\n` 转义后的真字符）或 MiniMessage 的 `<newline>` 标签
- 不要依赖 `\n`（两字符）隐式变换行——它按字面处理
- 文本不经过任何"反斜杠反转义"，`§` 颜色码仅在 legacy 回退路径生效

### 需要字面 `\n` 变真实换行时

如果插件**允许用户书写 `\n` 表达换行**（如玩家配置的 MOTD 文本），插件需自行转换：

```js
const motd = userText.replace(/\\n/g, '\n');   // 字面 \n → 真实换行
```

即：把"用户输入的转义约定"翻译成 Yeow 的真实换行语义。
