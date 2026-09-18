package yeow.tests.quickjs;

/** 零依赖断言工具；失败抛 {@link AssertionError}，由 Runner 统一收集。 */
public final class Assert {

    private Assert() {}

    public static void ok(boolean value, String msg) {
        if (!value) throw new AssertionError(msg == null ? "expected true" : msg);
    }

    public static void eq(Object expected, Object actual, String msg) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            throw new AssertionError((msg == null ? "" : msg + ": ")
                    + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /** 数值比较（桥对整数返回 Long、小数返回 Double）。 */
    public static void num(double expected, Object actual, String msg) {
        if (!(actual instanceof Number n)) {
            throw new AssertionError((msg == null ? "" : msg + ": ")
                    + "expected number <" + expected + "> but was <" + actual + ">");
        }
        if (n.doubleValue() != expected) {
            throw new AssertionError((msg == null ? "" : msg + ": ")
                    + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void contains(String haystack, String needle, String msg) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError((msg == null ? "" : msg + ": ")
                    + "expected <" + haystack + "> to contain <" + needle + ">");
        }
    }

    public static void isTrue(Object v, String msg) {
        eq(Boolean.TRUE, v, msg);
    }

    public static void fail(String msg) {
        throw new AssertionError(msg == null ? "explicit failure" : msg);
    }
}
