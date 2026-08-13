package yeow.folia;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;

/**
 * Folia 文本工具（独立实现，不与 Paper 共享）：MiniMessage 解析 + Message 对象
 * （{key, args, text} 可翻译组件）→ adventure Component。
 *
 * 与协议一致（对齐 Paper TextUtil.parseMessage）：
 * - 字符串 → MiniMessage 解析，含 § 且结果仍为纯文本时回退 legacy 解析
 * - {key, args, text} → 可翻译组件（args 递归支持 string/number/Message 嵌套）
 * - {text} → 纯文本（MiniMessage/legacy 解析）
 */
public class FoliaTextUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 协议层消息 → Component。 */
    public static Component parse(JsonElement el) {
        if (el == null || el.isJsonNull()) return Component.empty();
        if (el.isJsonObject()) {
            var o = el.getAsJsonObject();
            if (o.has("key")) {
                var b = Component.translatable().key(o.get("key").getAsString());
                if (o.has("args")) {
                    var args = new ArrayList<Component>();
                    for (var a : o.getAsJsonArray("args")) args.add(parse(a));
                    b.args(args);
                }
                if (o.has("text")) b.fallback(o.get("text").getAsString());
                return b.build();
            }
            return o.has("text") ? parse(o.get("text")) : Component.empty();
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return Component.text(el.getAsString());
        }
        return deserialize(el.getAsString());
    }

    /** Component → legacy 字符串（setMotd 等 String 目标）。 */
    public static String toLegacy(Component c) {
        return LEGACY.serialize(c);
    }

    /** MiniMessage 优先；输入含 § 且解析结果仍为纯文本时回退 legacy（对齐 Paper TextUtil）。 */
    private static Component deserialize(String t) {
        if (t == null || t.isEmpty()) return Component.empty();
        try {
            var parsed = MM.deserialize(t);
            // MiniMessage succeeded - but if the result is just literal text
            // and the input has §, the user probably intended legacy format
            if (t.indexOf('§') != -1 && parsed.equals(Component.text(t))) {
                return LEGACY.deserialize(t);
            }
            return parsed;
        } catch (Exception ignored) {}
        return LEGACY.deserialize(t);
    }
}
