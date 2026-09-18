package yeow.tests.quickjs;

import wiki.yexin.quickjs.QuickJSContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 零依赖 QuickJS 桥测试 Runner。
 *
 * <p>两类用例：Java 用例（{@link Cases}，直接驱动构建出的 wrapper）与 JS 语料
 * （{@code cases/*.js}，通过注入的 {@code test()/assert} 编写）。每个用例使用独立的
 * {@link QuickJSContext}，互不影响。
 *
 * <pre>
 *   java -cp "yeow-quickjs.jar:out/classes" yeow.tests.quickjs.Runner \
 *        [--cases=&lt;dir&gt;] [--filter=&lt;substr&gt;] [--json=&lt;path&gt;] [--quiet]
 * </pre>
 */
public final class Runner {

    // ── 用例模型 ────────────────────────────────────────────────────

    public record Result(String suite, String name, boolean ok, String detail) {}

    @FunctionalInterface
    public interface Body {
        void run(QuickJSContext ctx) throws Exception;
    }

    public record Case(String suite, String name, Body body) {}

    // ── bootstrap：注入 test()/testAsync()/assert ───────────────────

    private static final String BOOTSTRAP = """
            globalThis.test = function (name, fn) {
                try { fn(); __caseOk(String(name), ''); }
                catch (e) { __caseFail(String(name), (e && (e.stack || e.message)) || String(e)); }
            };
            globalThis.testAsync = function (name, fn) {
                try {
                    Promise.resolve(fn()).then(
                        function () { __caseOk(String(name), ''); },
                        function (e) { __caseFail(String(name), (e && (e.stack || e.message)) || String(e)); });
                } catch (e) { __caseFail(String(name), (e && (e.stack || e.message)) || String(e)); }
            };
            globalThis.assert = {
                ok: function (v, m) { if (!v) throw new Error(m || ('expected truthy, got ' + v)); },
                eq: function (a, b, m) {
                    var same = (a === b) || (JSON.stringify(a) === JSON.stringify(b));
                    if (!same) throw new Error((m ? m + ': ' : '') + 'expected ' + JSON.stringify(b) + ', got ' + JSON.stringify(a));
                },
                ne: function (a, b, m) { if (a === b) throw new Error((m ? m + ': ' : '') + 'expected values to differ: ' + JSON.stringify(a)); },
                contains: function (s, sub, m) {
                    if (String(s).indexOf(sub) < 0) throw new Error((m ? m + ': ' : '') + JSON.stringify(s) + ' does not contain ' + JSON.stringify(sub));
                },
                throws: function (fn, m) { var threw = false; try { fn(); } catch (e) { threw = true; } if (!threw) throw new Error(m || 'expected function to throw'); }
            };
            """;

    // ── 状态 ────────────────────────────────────────────────────────

    private static final List<Result> results = new ArrayList<>();
    private static String filter = null;
    private static boolean quiet = false;
    private static long startedAt = 0;

    public static void main(String[] args) throws Exception {
        String casesDir = null;
        String jsonOut = null;
        for (String a : args) {
            if (a.startsWith("--filter=")) filter = a.substring("--filter=".length());
            else if (a.startsWith("--json=")) jsonOut = a.substring("--json=".length());
            else if (a.startsWith("--cases=")) casesDir = a.substring("--cases=".length());
            else if (a.equals("--quiet")) quiet = true;
        }
        startedAt = System.nanoTime();

        List<Case> cases = Cases.all();
        int ran = 0;
        for (Case c : cases) {
            if (!match(c.suite(), c.name())) continue;
            runJavaCase(c);
            ran++;
        }
        if (casesDir != null) runJsCases(Path.of(casesDir));

        report(jsonOut, ran);
    }

    // ── Java 用例 ───────────────────────────────────────────────────

    private static void runJavaCase(Case c) {
        QuickJSContext ctx;
        try {
            ctx = QuickJSContext.create();
        } catch (Throwable t) {
            add(c.suite(), c.name(), false, "context create failed: " + stack(t));
            return;
        }
        try {
            c.body().run(ctx);
            add(c.suite(), c.name(), true, null);
        } catch (Throwable t) {
            add(c.suite(), c.name(), false, stack(t));
        } finally {
            try { ctx.destroy(); } catch (Throwable ignored) { }
        }
    }

    // ── JS 语料 ─────────────────────────────────────────────────────

    private static void runJsCases(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            add("js", "(cases dir)", false, "not found: " + dir);
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".js"))
             .sorted(Comparator.comparing(p -> p.getFileName().toString()))
             .forEach(files::add);
        }
        for (Path f : files) {
            String suite = "js/" + f.getFileName();
            QuickJSContext ctx = QuickJSContext.create();
            try {
                ctx.setGlobalFunction("__caseOk", a -> { add(suite, str(a, 0), true, null); return null; });
                ctx.setGlobalFunction("__caseFail", a -> { add(suite, str(a, 0), false, str(a, 1)); return null; });
                ctx.evaluate(BOOTSTRAP, "test-bootstrap.js");
                ctx.evaluate(Files.readString(f, StandardCharsets.UTF_8), f.getFileName().toString());
                ctx.drainJobs();
                ctx.drainJobs();
            } catch (Throwable t) {
                add(suite, "(file)", false, stack(t));
            } finally {
                try { ctx.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    // ── 结果收集与输出 ──────────────────────────────────────────────

    private static boolean match(String suite, String name) {
        return filter == null || (suite + "." + name).contains(filter)
                || (suite + "/" + name).contains(filter);
    }

    private static void add(String suite, String name, boolean ok, String detail) {
        if (!match(suite, name)) return;
        results.add(new Result(suite, name, ok, detail));
        if (!quiet) {
            if (ok) System.out.println("  ok   " + suite + " :: " + name);
            else System.out.println("  FAIL " + suite + " :: " + name + "  — " + oneLine(detail));
        }
    }

    private static void report(String jsonOut, int javaRan) throws IOException {
        long failed = results.stream().filter(r -> !r.ok()).count();
        double ms = (System.nanoTime() - startedAt) / 1e6;
        System.out.printf("%n[quickjs] %d passed, %d failed  (java cases run: %d, %.0f ms)%n",
                results.size() - failed, failed, javaRan, ms);
        if (failed > 0) {
            System.out.println("Failures:");
            results.stream().filter(r -> !r.ok()).forEach(r ->
                    System.out.println("  - " + r.suite() + " :: " + r.name() + "\n    " + r.detail()));
        }
        if (jsonOut != null) {
            Files.writeString(Path.of(jsonOut), toJson(), StandardCharsets.UTF_8);
        }
        if (failed > 0) System.exit(1);
    }

    private static String toJson() {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"suite\":\"").append(esc(r.suite()))
              .append("\",\"name\":\"").append(esc(r.name()))
              .append("\",\"ok\":").append(r.ok())
              .append(",\"detail\":").append(r.detail() == null ? "null" : "\"" + esc(r.detail()) + "\"")
              .append('}');
        }
        return sb.append("]}").toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
                }
            }
        }
        return b.toString();
    }

    private static String str(Object[] args, int i) {
        return (args != null && args.length > i && args[i] != null) ? String.valueOf(args[i]) : "";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl) + " …";
    }

    private static String stack(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n    at ").append(e);
            if (sb.length() > 4000) break;
        }
        return sb.toString();
    }
}
