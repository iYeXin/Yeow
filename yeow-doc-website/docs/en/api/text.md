# Text and MiniMessage

All of Yeow's player-facing text (chat messages, broadcasts, titles, ActionBar, MOTD, custom inventory titles, item names/lore, etc.) is parsed uniformly through **MiniMessage** — **MiniMessage first, falling back to legacy § format**.

> MiniMessage is the markup language of Adventure (Paper's text library), supporting colors, decorations, click/hover events, etc. For syntax reference: [Paper official MiniMessage documentation](https://docs.papermc.io/adventure/minimessage/).

```js
// Chat message (MiniMessage markup)
await player.sendMessage('<red>You died!</red> <click:run_command:/back><aqua><u>/back</u></aqua></click>');

// Broadcast
await broadcast('<gradient:red:gold>Server announcement</gradient>');

// MOTD (serverPing write-back)
eventOn('serverPing', () => ({ motd: '<green>First line</green><newline><aqua>Second line</aqua>' }));
```

## Escape rules

Yeow treats text as **MiniMessage literals** — parsing follows the [MiniMessage specification](https://docs.papermc.io/adventure/minimessage/) exactly: `\` only escapes tag characters (`\\` → literal `\`, `\<` → literal `<`); **`\n` and the like are not MiniMessage escapes and are treated literally**. At the same time it ensures that **real control characters** (real line breaks/tabs, etc.) are not lost during conversion:

| Input                            | Result                                                              |
| ------------------------------- | ----------------------------------------------------------------- |
| Real line break (the newline character itself)        | **Real line break** (not lost)                                            |
| MiniMessage `<newline>` tag    | **Real line break**                                                      |
| Literal `\n` (backslash + n, two characters) | **Kept literally** (MiniMessage does not escape `\n`)                           |
| `\\` (two backslashes)              | Literal `\` (MiniMessage escape)                                      |
| `\<` (backslash + less-than sign)         | Literal `<` (MiniMessage escape, e.g. `\<red>` displays `<red>` without parsing the color) |
| Real tab                        | Real tab (not lost)                                                |

Key points:

- **Expressing a line break**: use a **real line break** or MiniMessage's `<newline>` tag
- **Don't rely on `\n` (two characters) for line breaks** — it is literal text
- Real control characters (line break/tab/carriage return, etc.) are preserved throughout the conversion pipeline and never lost
- The JSON parser already escapes automatically (`JSON.parse('{"t":"a\nb"}')` parses `\n` into a real newline character)
- `§` color codes only take effect on the legacy fallback path

### When you need a literal `\n` to become a real line break

If a plugin **allows users to write a literal `\n` to express a line break** (e.g. text read from motds.txt), the plugin must convert it itself:

```js
const motd = userText.replace(/\\n/g, '\n');   // literal \n → real line break
```

That is, translate the "user-written escape convention" into Yeow's real-newline semantics.

## Message objects (translatable components)

Payloads involving text support **Message objects** — translatable components (localized by the client per language) or plain text:

```js
// Translatable component: Minecraft translation key + arguments + plain text fallback (key and text can coexist)
{ key: 'death.attack.player', args: ['Steve', 'Zombie'], text: '§cSteve was killed by Zombie' }

// Plain text (MiniMessage/legacy parsing)
{ text: '<red>You died</red>' }
```

| Field   | Type                            | Description                                                               |
| ------ | ------------------------------- | ------------------------------------------------------------------ |
| `key`  | string                          | Minecraft translation key (e.g. `death.attack.player`); when present, a translatable component is constructed |
| `args` | (string \| number \| Message)[] | Translation arguments (optional; can nest Message)                                   |
| `text` | string                          | Plain text fallback (MiniMessage/legacy parsing)                              |

- When `key` and `text` **both exist**: `key` is used for client-side localization, `text` is the plain text fallback (e.g. when forwarding across implementations)
- A plain string is equivalent to `{ text: "<string>" }`
- The message-sending APIs (`player.sendMessage`, `player.sendActionBar`, `broadcast`) all accept them
- On the event side: `playerDeath`'s `deathMessage` is a Message object (`{key, args, text}` or `{text}`); `playerAdvancementDone`'s `title`/`description` are Message objects (vanilla advancement titles/descriptions are translatable components)

```js
// Localized death message forwarding — pass the Message object straight through (key localized + text fallback)
eventOn('playerDeath', (e) => {
    broadcast(e.deathMessage);
});

// Advancement completion notices (title/description may be translatable components)
eventOn('playerAdvancementDone', (e) => {
    broadcast(e.title ?? e.description);
});
```
