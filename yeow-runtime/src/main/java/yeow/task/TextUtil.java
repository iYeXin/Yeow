package yeow.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class TextUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

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

    public static String toLegacy(Component c) {
        return LEGACY.serialize(c);
    }
}
