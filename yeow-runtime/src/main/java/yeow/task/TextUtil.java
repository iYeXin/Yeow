package yeow.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class TextUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    /**
     * 字面反斜杠的临时保护标记：私有区（PUA）双字符组合，正常文本不会出现，
     * 且非控制字符——不受 MiniMessage / legacy 序列化的转义或特殊处理影响；
     * 解析/序列化前后替换，使 `\n` `\b` 等字面反斜杠序列不被当作转义，也不被下游还原。
     */
    private static final String BS_MARK = "\uE000\uE001";

    public static Component parse(String t) {
        if (t == null || t.isEmpty())
            return Component.empty();
        var protectedText = t.replace("\\", BS_MARK);
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
        return unescapeLegacy(LEGACY.serialize(markBackslash(c))).replace(BS_MARK, "\\");
    }

    /** 递归恢复 Component 文本中的反斜杠标记（解析后调用）。 */
    private static Component restoreBackslash(Component c) {
        if (c instanceof TextComponent t) {
            var children = t.children().stream().map(TextUtil::restoreBackslash).toList();
            return t.content(t.content().replace(BS_MARK, "\\")).children(children);
        }
        return c;
    }

    /**
     * 递归把 Component 文本中的字面反斜杠替换为标记（序列化前调用），
     * 使序列化的控制字符转义（真实换行 → `\n`）与用户字面反斜杠序列可区分。
     */
    private static Component markBackslash(Component c) {
        if (c instanceof TextComponent t) {
            var children = t.children().stream().map(TextUtil::markBackslash).toList();
            return t.content(t.content().replace("\\", BS_MARK)).children(children);
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
