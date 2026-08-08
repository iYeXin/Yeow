package yeow.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;

public class TextUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    /** 真实控制字符的临时保护标记（私有区 PUA 双字符组合，正常文本不会出现）：
     * 序列化前把真实换行/tab 等替换为标记（legacy 序列化只转义控制字符、反斜杠原样），
     * 序列化后还原——真实控制字符不消失，且字面反斜杠序列（如 `\\n` 转义出的 `\n`）
     * 不会被序列化转义或误还原。 */
    private static final String NL_MARK = "\uE000\uE010";
    private static final String TAB_MARK = "\uE000\uE011";
    private static final String CR_MARK = "\uE000\uE012";
    private static final String BS_MARK = "\uE000\uE013";
    private static final String FF_MARK = "\uE000\uE014";

    public static Component parse(String t) {
        if (t == null || t.isEmpty()) return Component.empty();
        try {
            var parsed = MM.deserialize(t);
            // MiniMessage succeeded — but if the result is just literal text
            // and the input has §, the user probably intended legacy format
            if (t.indexOf('§') != -1 && parsed.equals(Component.text(t))) {
                return LEGACY.deserialize(t);
            }
            return parsed;
        } catch (Exception ignored) {}
        return LEGACY.deserialize(t);
    }

    /**
     * 解析消息载荷（Message 对象，跨实现建议支持）：
     * - 字符串 → 纯文本（MiniMessage/legacy 解析）
     * - `{ "key": "<翻译键>", "args": [<string|number|Message>...] }` → 可翻译组件
     * - `{ "text": "<纯文本>" }` → 纯文本（MiniMessage/legacy 解析）
     * - `key` 与 `text` 同时存在时 `key` 优先
     */
    public static Component parseMessage(JsonElement e) {
        if (e == null || e.isJsonNull()) return Component.empty();
        if (e.isJsonPrimitive()) return parse(e.getAsString());
        if (e.isJsonObject()) {
            var o = e.getAsJsonObject();
            if (o.has("key") && !o.get("key").isJsonNull() && !o.get("key").getAsString().isEmpty()) {
                var comp = Component.translatable(o.get("key").getAsString());
                if (o.has("args") && o.get("args").isJsonArray() && o.getAsJsonArray("args").size() > 0) {
                    var args = new ArrayList<Component>();
                    for (var a : o.getAsJsonArray("args")) {
                        if (a.isJsonObject() || a.isJsonPrimitive())
                            args.add(parseMessage(a));
                        else
                            args.add(Component.text(a.toString()));
                    }
                    comp = comp.args(args);
                }
                return comp;
            }
            if (o.has("text") && !o.get("text").isJsonNull())
                return parse(o.get("text").getAsString());
            return Component.empty();
        }
        return Component.empty();
    }

    public static String toLegacy(Component c) {
        return restoreControl(LEGACY.serialize(markControl(c)));
    }

    /** 递归把 Component 文本中的真实控制字符替换为标记（序列化前调用）。 */
    private static Component markControl(Component c) {
        if (c instanceof TextComponent t) {
            var children = t.children().stream().map(TextUtil::markControl).toList();
            return t.content(t.content()
                .replace("\n", NL_MARK)
                .replace("\t", TAB_MARK)
                .replace("\r", CR_MARK)
                .replace("\b", BS_MARK)
                .replace("\f", FF_MARK)).children(children);
        }
        return c;
    }

    /** 标记 → 真实控制字符（序列化后调用）。 */
    private static String restoreControl(String s) {
        return s.replace(NL_MARK, "\n")
                .replace(TAB_MARK, "\t")
                .replace(CR_MARK, "\r")
                .replace(BS_MARK, "\b")
                .replace(FF_MARK, "\f");
    }
}
