/**
 * Message —— 可翻译组件 / 纯文本的统一消息对象。
 *
 * - `{ key, args? }`：Minecraft 翻译键组件（如死亡消息 `death.attack.player`），
 *   由客户端按语言本地化；`args` 为翻译参数（字符串/数字/嵌套 Message）
 * - `{ text }`：纯文本（MiniMessage/legacy 解析）——所有实现**至少应支持 text 字段**
 * - 两者同时存在时 `key` 优先
 *
 * 发送消息 API（`player.sendMessage`、`broadcast`、`sendActionBar` 等）与
 * 事件字段（如 `playerDeath.deathMessage`）均接受该对象或纯字符串。
 */
export interface Message {
  /** Minecraft 翻译键（如 `death.attack.player`）。 */
  key?: string;
  /** 翻译参数（字符串/数字/嵌套 Message）。 */
  args?: (string | number | Message)[];
  /** 纯文本（MiniMessage 解析；key 缺失时使用）。 */
  text?: string;
}
