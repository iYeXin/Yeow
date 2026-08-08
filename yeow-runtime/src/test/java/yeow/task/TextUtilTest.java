package yeow.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** TextUtil 转义规则测试：真实换行 / MiniMessage <newline> / 字面反斜杠序列。 */
class TextUtilTest {

    /** 真实换行（MiniMessage 文本节点）→ legacy 往返后必须保留为真实换行。 */
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

    /** 用户字面输入 \n（反斜杠+n 两字符）必须保留为字面，不能变换行。 */
    @Test void literalBackslashNPreserved() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\nb"));
        assertEquals("a\\nb", out, "literal \\n stays literal");
        assertFalse(out.contains("\n"), "literal input must not become newline");
    }

    /** 字面反斜杠（无转义字母）保留。 */
    @Test void literalBackslashPreserved() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\b"));
        assertEquals("a\\b", out);
    }

    /** Windows 路径等含 \n 字面序列的文本不被破坏。 */
    @Test void windowsPathPreserved() {
        var out = TextUtil.toLegacy(TextUtil.parse("C:\\new\\data"));
        assertEquals("C:\\new\\data", out);
    }

    /** 含 § 的 legacy 输入（真实换行）往返保留。 */
    @Test void legacySectionInputWithNewline() {
        var out = TextUtil.toLegacy(TextUtil.parse("§6a\nb"));
        assertTrue(out.contains("\n"), "legacy section input newline preserved: " + out.replace("\n", "<NL>"));
    }

    /** legacy 输入中的字面 \n 同样保留为字面（legacy 转义与 MiniMessage 转义均被保护）。 */
    @Test void legacySectionLiteralBackslashN() {
        var out = TextUtil.toLegacy(TextUtil.parse("§6a\\nb"));
        assertTrue(out.contains("\\n"), "legacy literal \\n stays literal: " + out.replace("\n", "<NL>"));
        assertFalse(out.contains("\n"), "no newline");
    }

    /** 真实 tab 往返。 */
    @Test void tabRoundTrip() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\tb"));
        assertEquals("a\tb", out, "real tab round-trips");
    }

    /** 用户文本中的单个 PUA 字符（标记组合的一部分）不受影响——标记是双字符组合。 */
    @Test void singlePuaCharUntouched() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\uE000b"));
        assertEquals("a\uE000b", out);
    }

    /** 输出中不残留标记。 */
    @Test void noMarkLeak() {
        var out = TextUtil.toLegacy(TextUtil.parse("a\\nb"));
        assertFalse(out.contains("\uE000"), "no mark leak: " + out);
    }
}
