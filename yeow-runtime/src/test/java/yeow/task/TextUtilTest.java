package yeow.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** TextUtil 测试：文本按 MiniMessage 规范解析（转义生效），真实控制字符不消失。 */
class TextUtilTest {

    /** 真实换行 → 换行（不消失）。 */
    @Test void realNewlineSurvives() {
        var out = TextUtil.toLegacy(TextUtil.parse("<gold>a</gold>\n<b>c</b>"));
        assertTrue(out.contains("\n"), "real newline preserved: " + out.replace("\n", "<NL>"));
        assertFalse(out.contains("\\n"), "no literal \\n: " + out);
    }

    /** MiniMessage <newline> 标签 → 真实换行。 */
    @Test void miniMessageNewlineTag() {
        var out = TextUtil.toLegacy(TextUtil.parse("<gold>a</gold><newline><b>c</b>"));
        assertTrue(out.contains("\n"), "newline tag -> real newline: " + out.replace("\n", "<NL>"));
    }

    /** 字面 `\n`（两字符）按 MiniMessage 规范是**字面文本**（MiniMessage 只转义标签字符
     *  `\\` / `\<` 等，`\n` 不转义）——不会被隐式变换行。 */
    @Test void literalBackslashNStaysLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\nb"));
        assertEquals("a\\nb", out, "literal \\n stays literal");
        assertFalse(out.contains("\n"), "must not become newline");
    }

    /** `\<` 按 MiniMessage 规范：字面 `<`（反斜杠被消耗）。 */
    @Test void escapedAngleLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\<b"));
        assertEquals("a<b", out);
    }

    /** `\<red>` 不解析为颜色标签（字面 `<red>`）。 */
    @Test void escapedAngleTagLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\<red>b"));
        assertEquals("a<red>b", out, "\\<red> stays literal");
    }

    /** `\\` 按 MiniMessage 规范：字面反斜杠（且不被误还原）。 */
    @Test void doubleBackslashLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\\\b"));
        assertEquals("a\\b", out, "\\\\ -> literal backslash");
    }

    /** `\\n`（双反斜杠 + n）按 MiniMessage 规范：字面 `\` + `n`——不被误还原为换行。 */
    @Test void doubleBackslashNStaysLiteral() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\\\nb"));
        assertEquals("a\\nb", out, "\\\\n stays literal");
        assertFalse(out.contains("\n"), "must not become newline");
    }

    /** 含 § 的 legacy 输入（真实换行）往返保留。 */
    @Test void legacySectionInputWithNewline() {
        var out = TextUtil.toLegacy(TextUtil.parse("§6a\nb"));
        assertTrue(out.contains("\n"), "legacy section input newline preserved: " + out.replace("\n", "<NL>"));
    }

    /** legacy 输入中的字面 `\n`（两字符）同样保持字面（legacy deserialize 不还原转义）。 */
    @Test void legacySectionLiteralBackslashN() {
        var out = TextUtil.toLegacy(TextUtil.parse("§6a\\nb"));
        assertEquals("§6a\\nb", out, "legacy literal \\n stays literal");
        assertFalse(out.contains("\n"), "must not become newline");
    }

    /** 真实 tab 往返。 */
    @Test void tabRoundTrip() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\tb"));
        assertEquals("a\tb", out, "real tab round-trips");
    }
}
