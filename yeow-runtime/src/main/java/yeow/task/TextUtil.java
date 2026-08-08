package yeow.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class TextUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    /**
     * 字面反斜杠 + 常见转义字母的临时保护标记（私有区 PUA 双字符组合 + 原字母）：
     * 使 `\n` `\t` 等字面反斜杠序列不被 MiniMessage 当作转义，也不被下游还原。
     * `\<` 等其余反斜杠序列**原样放行**给 MiniMessage（`\<` 是其"字面 `<`"转义）。
     */
    private static final String BS_MARK = "\uE000\uE001";

    public static Component parse(String t) {
        if (t == null || t.isEmpty())
            return Component.empty();
        var protectedText = protectBackslash(t);
        try {
            var parsed = MM.deserialize(protectedText);
            // MiniMessage succeeded — but if the result is just literal text
            // and the input has §, the user probably intended legacy format
            if (t.indexOf('§') != -1 && parsed.equals(Component.text(protectedText))) {
                return restoreBackslash(LEGACY.deserialize(protectedText));
            }
            return restoreBackslash(parsed);
        } catch (Exception ignored) {
        }
        return restoreBackslash(LEGACY.deserialize(protectedText));
    }

    public static String toLegacy(Component c) {
        return restoreBackslashText(unescapeLegacy(LEGACY.serialize(markBackslash(c))));
    }

    /** 递归恢复 Component 文本中的反斜杠标记（解析后调用）。 */
    private static Component restoreBackslash(Component c) {
        if (c instanceof TextComponent t) {
            var children = t.children().stream().map(TextUtil::restoreBackslash).toList();
            return t.content(restoreBackslashText(t.content())).children(children);
        }
        return c;
    }

    /** 常见转义组合的字面反斜杠 → 标记（`\` + 转义字母整体保护）。 */
    private static String protectBackslash(String s) {
        return s.replace("\\n", BS_MARK + "n")
                .replace("\\t", BS_MARK + "t")
                .replace("\\r", BS_MARK + "r")
                .replace("\\b", BS_MARK + "b")
                .replace("\\f", BS_MARK + "f");
    }

    /** 标记 → 字面反斜杠序列（恢复）。 */
    private static String restoreBackslashText(String s) {
        return s.replace(BS_MARK + "n", "\\n")
                .replace(BS_MARK + "t", "\\t")
                .replace(BS_MARK + "r", "\\r")
                .replace(BS_MARK + "b", "\\b")
                .replace(BS_MARK + "f", "\\f");
    }

    /**
     * 递归把 Component 文本中的常见转义组合（`\n` 等）替换为标记（序列化前调用），
     * 使序列化的控制字符转义（真实换行 → `\n`）与用户字面反斜杠序列可区分。
     */
    private static Component markBackslash(Component c) {
        if (c instanceof TextComponent t) {
            var children = t.children().stream().map(TextUtil::markBackslash).toList();
            return t.content(protectBackslash(t.content())).children(children);
        }
        return c;
    }

    /**
     * legacy 序列化会把真实换行/制表符等控制字符转义为字面 `\n` `\t` 等——
     * 反转为真实字符（此时用户字面反斜杠已被标记保护，不会被误还原）。
     */
    private static String unescapeLegacy(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                switch (s.charAt(i + 1)) {
                    case 'n' -> {
                        sb.append('\n');
                        i++;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i++;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i++;
                    }
                    case 'b' -> {
                        sb.append('\b');
                        i++;
                    }
                    case 'f' -> {
                        sb.append('\f');
                        i++;
                    }
                    case '\\' -> {
                        sb.append('\\');
                        i++;
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
