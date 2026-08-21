package yeow.paper;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** TextUtil 测试：文本按 MiniMessage 规范解析（转义生效），真实控制字符不消失。 */
class TextUtilTest {

    /** 反射获取 TranslatableComponent 的翻译参数（Component 列表）——
     *  兼容 Adventure 4.20+（args() 返回空，数据在 arguments() 中）。
     *  通过接口（而非实现类）反射，避免模块系统对 Adventure 内部类的访问限制。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<net.kyori.adventure.text.Component> argsOf(net.kyori.adventure.text.TranslatableComponent tc) {
        try {
            var m = net.kyori.adventure.text.TranslatableComponent.class.getMethod("args");
            var raw = m.invoke(tc);
            if (raw instanceof java.util.List<?> list && !list.isEmpty()) return (java.util.List<net.kyori.adventure.text.Component>) list;
        } catch (Exception ignored) {}
        // Fallback: arguments() → TranslationArgument → Component
        try {
            var m = net.kyori.adventure.text.TranslatableComponent.class.getMethod("arguments");
            var raw = m.invoke(tc);
            if (raw instanceof java.util.List<?> list) {
                var out = new java.util.ArrayList<net.kyori.adventure.text.Component>();
                for (var a : list) {
                    var val = a instanceof net.kyori.adventure.text.TranslationArgument ta ? ta.value() : a;
                    if (val instanceof net.kyori.adventure.text.Component comp) out.add(comp);
                    else if (val instanceof net.kyori.adventure.text.ComponentLike cl) out.add(cl.asComponent());
                    else if (val != null) out.add(net.kyori.adventure.text.Component.text(val.toString()));
                }
                return out;
            }
        } catch (Exception ignored) {}
        return java.util.List.of();
    }

    // ── MiniMessage / legacy 文本 ──

    @Test void realNewlineSurvives() {
        var out = TextUtil.toLegacy(TextUtil.parse("<gold>a</gold>\n<b>c</b>"));
        assertTrue(out.contains("\n"), "real newline preserved: " + out.replace("\n", "<NL>"));
        assertFalse(out.contains("\\n"), "no literal \\n: " + out);
    }

    @Test void miniMessageNewlineTag() {
        var out = TextUtil.toLegacy(TextUtil.parse("<gold>a</gold><newline><b>c</b>"));
        assertTrue(out.contains("\n"), "newline tag -> real newline: " + out.replace("\n", "<NL>"));
    }

    @Test void literalBackslashNStaysLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\nb"));
        assertEquals("a\\nb", out, "literal \\n stays literal");
        assertFalse(out.contains("\n"), "must not become newline");
    }

    @Test void escapedAngleLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\<b"));
        assertEquals("a<b", out);
    }

    @Test void escapedAngleTagLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\<red>b"));
        assertEquals("a<red>b", out, "\\<red> stays literal");
    }

    @Test void doubleBackslashLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\\\b"));
        assertEquals("a\\b", out, "\\\\ -> literal backslash");
    }

    @Test void doubleBackslashNStaysLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\\\nb"));
        assertEquals("a\\nb", out, "\\\\n stays literal");
        assertFalse(out.contains("\n"), "must not become newline");
    }

    @Test void legacySectionInputWithNewline() {
        var out = TextUtil.toLegacy(TextUtil.parse("§6a\nb"));
        assertTrue(out.contains("\n"), "legacy section input newline preserved: " + out.replace("\n", "<NL>"));
    }

    @Test void legacySectionLiteralBackslashN() {
        var out = TextUtil.toLegacy(TextUtil.parse("§6a\\nb"));
        assertEquals("§6a\\nb", out, "legacy literal \\n stays literal");
        assertFalse(out.contains("\n"), "must not become newline");
    }

    @Test void tabRoundTrip() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\tb"));
        assertEquals("a\tb", out, "real tab round-trips");
    }

    // ── Message 对象（可翻译组件）──

    @Test void messageString() {
        assertEquals("hello", TextUtil.toLegacy(TextUtil.parseMessage(JsonParser.parseString("\"hello\""))));
    }

    @Test void messageKey() {
        var c = TextUtil.parseMessage(JsonParser.parseString("{\"key\":\"death.attack.player\",\"args\":[\"Steve\"]}"));
        assertTrue(c instanceof net.kyori.adventure.text.TranslatableComponent, "translatable component");
        var tc = (net.kyori.adventure.text.TranslatableComponent) c;
        assertEquals("death.attack.player", tc.key());
        assertEquals(1, argsOf(tc).size());
        assertEquals("Steve", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(argsOf(tc).get(0)));
    }

    @Test void messageKeyNoArgs() {
        var c = TextUtil.parseMessage(JsonParser.parseString("{\"key\":\"death.attack.generic\"}"));
        assertTrue(c instanceof net.kyori.adventure.text.TranslatableComponent);
        assertEquals(0, argsOf((net.kyori.adventure.text.TranslatableComponent) c).size());
    }

    @Test void messageText() {
        var out = TextUtil.toLegacy(TextUtil.parseMessage(JsonParser.parseString("{\"text\":\"<red>死了</red>\"}")));
        assertEquals("§c死了", out);
    }

    @Test void messageKeyPrecedesText() {
        var c = TextUtil.parseMessage(JsonParser.parseString("{\"key\":\"k\",\"text\":\"ignored\"}"));
        assertTrue(c instanceof net.kyori.adventure.text.TranslatableComponent);
    }

    @Test void messageNestedArgs() {
        var c = TextUtil.parseMessage(JsonParser.parseString("{\"key\":\"k\",\"args\":[{\"text\":\"<green>x</green>\"}]}"));
        var tc = (net.kyori.adventure.text.TranslatableComponent) c;
        assertEquals(1, argsOf(tc).size());
        assertTrue(argsOf(tc).get(0).style().color() != null, "nested Message parsed with style");
    }
}
