package wiki.yexin.quickjs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** End-to-end smoke test for the Yeow QuickJS bridge. */
public final class SmokeTest {

    private static int failures = 0;

    public static void main(String[] args) {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            testUpcall(ctx);
            testDowncall(ctx);
            testValues(ctx);
            testPolyfill(ctx);
            testTextCodec(ctx);
            testBinding(ctx);
            testBinaryCodec(ctx);
            testBinaryDispatch(ctx);
            testUpcallResultBinary(ctx);
            testPromisePump(ctx);
            testError(ctx);
            testInterrupt(ctx);
        } finally {
            ctx.destroy();
        }
        ctx.destroy(); // idempotent

        testPerContextOrigin();
        testThreadAffinity();
        testTerminateCheckpoint();

        if (failures > 0) {
            System.err.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void testUpcall(QuickJSContext ctx) {
        List<Object> seen = new ArrayList<>();
        ctx.setGlobalFunction("send", a -> {
            for (Object o : a) seen.add(o);
            return "ok";
        });
        ctx.evaluate("function greet(name){ return send('hi', name); }");
        Object r = ctx.callGlobal("greet", "yeow");
        eq("upcall return", "ok", r);
        eq("upcall argc", 2, seen.size());
        eq("upcall arg0", "hi", seen.get(0));
        eq("upcall arg1", "yeow", seen.get(1));
    }

    private static void testDowncall(QuickJSContext ctx) {
        ctx.evaluate("function num(){ return 7; }");
        eq("downcall number", 7L, ctx.callGlobal("num", null));
        check("hasGlobalFunction present", ctx.hasGlobalFunction("num"));
        check("hasGlobalFunction absent", !ctx.hasGlobalFunction("nope"));
        ctx.evaluate("function boom(){ throw new Error('nope'); }");
        try {
            ctx.callGlobal("boom", null);
            fail("downcall throw should propagate");
        } catch (QuickJSException e) {
            check("downcall throw", e.getMessage().contains("nope"));
        }
        try {
            ctx.callGlobal("doesNotExist", null);
            fail("missing global should throw");
        } catch (QuickJSException e) {
            check("missing global", e.getMessage().contains("not found"));
        }
    }

    private static void testValues(QuickJSContext ctx) {
        ctx.evaluate("function arr(){ return [1, 'two', true]; }");
        Object a = ctx.callGlobal("arr", null);
        check("array type", a instanceof Object[]);
        if (a instanceof Object[] arr) {
            eq("array len", 3, arr.length);
            eq("array[0]", 1L, arr[0]);
            eq("array[1]", "two", arr[1]);
            eq("array[2]", Boolean.TRUE, arr[2]);
        }

        ctx.evaluate("function obj(){ return {a: 1, b: 'x'}; }");
        Object o = ctx.callGlobal("obj", null);
        check("object type", o instanceof Map);
        if (o instanceof Map<?, ?> m) {
            eq("map.a", 1L, m.get("a"));
            eq("map.b", "x", m.get("b"));
        }

        ctx.evaluate("function none(){ return null; }");
        eq("null value", null, ctx.callGlobal("none", null));

        ctx.evaluate("function uni(){ return '\\u{1F600}'; }");
        eq("non-BMP string", "\uD83D\uDE00", ctx.callGlobal("uni", null));
    }

    private static void testPolyfill(QuickJSContext ctx) {
        eq("performance.now type", "function", ctx.evaluate("typeof performance.now"));
        eq("performance.timeOrigin type", "number", ctx.evaluate("typeof performance.timeOrigin"));
        Object now = ctx.evaluate("performance.now()");
        check("performance.now returns a number", now instanceof Double || now instanceof Long);
        eq("performance.now monotonic", Boolean.TRUE,
                ctx.evaluate("(function(){ var a = performance.now(); var s = 0;"
                        + " for (var i = 0; i < 20000; i++) s += i; return performance.now() >= a; })()"));
    }

    private static void testTextCodec(QuickJSContext ctx) {
        eq("TextEncoder.encoding", "utf-8", ctx.evaluate("new TextEncoder().encoding"));
        eq("TextDecoder.encoding", "utf-8", ctx.evaluate("new TextDecoder().encoding"));
        eq("TextEncoder bad label", "RangeError",
                ctx.evaluate("(function(){ try { new TextEncoder('gbk'); return ''; } catch (e) { return e.name; } })()"));
        eq("TextDecoder bad label", "RangeError",
                ctx.evaluate("(function(){ try { new TextDecoder('latin1'); return ''; } catch (e) { return e.name; } })()"));
        eq("encode ascii", Boolean.TRUE,
                ctx.evaluate("JSON.stringify(Array.from(new TextEncoder().encode('hi'))) === '[104,105]'"));
        eq("roundtrip non-BMP", "\u4E2D\uD83D\uDE00",
                ctx.evaluate("new TextDecoder().decode(new TextEncoder().encode('\\u4E2D\\u{1F600}'))"));
        eq("decode invalid -> U+FFFD", "A\uFFFDB",
                ctx.evaluate("new TextDecoder().decode(new Uint8Array([65, 255, 66]))"));
        eq("decode ArrayBuffer", "AB",
                ctx.evaluate("new TextDecoder().decode(new Uint8Array([65, 66]).buffer)"));
        eq("decode view offset", "B",
                ctx.evaluate("new TextDecoder().decode(new Uint8Array([65, 66, 67]).subarray(1, 2))"));
        eq("decode empty", "", ctx.evaluate("new TextDecoder().decode()"));
        eq("primitive hidden", "undefined", ctx.evaluate("typeof __yeowUtf8Encode"));
    }

    private static void testBinding(QuickJSContext ctx) {
        ctx.evaluate("function bound(x){ return 'got:' + x; }"
                + " function schedule(){ Promise.resolve().then(function(){ globalThis.__job = 1; }); }");
        long h = ctx.bindGlobal("bound");
        check("bindGlobal found", h != 0);
        eq("callHandle", "got:hi", ctx.callHandle(h, "hi"));
        eq("bindGlobal missing", 0L, ctx.bindGlobal("noSuchFn"));
        long s = ctx.bindGlobal("schedule");
        ctx.callHandle(s, null);
        eq("callHandle drains jobs", 1L, ctx.evaluate("globalThis.__job"));
        ctx.drainJobs();
        check("drainJobs ok", true);
    }

    private static void testPerContextOrigin() {
        QuickJSContext c1 = QuickJSContext.create();
        try {
            double first = ((Number) c1.evaluate("performance.now()")).doubleValue();
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
            long before = System.currentTimeMillis();
            QuickJSContext c2 = QuickJSContext.create();
            long after = System.currentTimeMillis();
            try {
                double second = ((Number) c2.evaluate("performance.now()")).doubleValue();
                check("per-context origin (c1=" + first + ", c2=" + second + ")", second < 100.0);
                double timeOrigin = ((Number) c2.evaluate("performance.timeOrigin")).doubleValue();
                check("timeOrigin is precise epoch ms",
                        timeOrigin >= before - 100 && timeOrigin <= after + 100);
            } finally {
                c2.destroy();
            }
        } finally {
            c1.destroy();
        }
    }

    private static void testThreadAffinity() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            final boolean[] rejected = {false};
            Thread t = new Thread(() -> {
                try {
                    ctx.evaluate("1 + 1");
                } catch (QuickJSException e) {
                    rejected[0] = true;
                }
            });
            t.setDaemon(true);
            t.start();
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
            }
            check("foreign-thread evaluate rejected", rejected[0]);
        } finally {
            ctx.destroy();
        }
    }

    private static void testTerminateCheckpoint() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            ctx.setGlobalFunction("cb", args -> "ok");
            // try/catch would swallow an ordinary (catchable) error.
            ctx.evaluate("function f(){ try { return cb(); } catch (e) { return 'caught'; } }");
            eq("checkpoint before terminate", "ok", ctx.callGlobal("f", null));

            ctx.interrupt(); // one-shot poll flag + sticky terminating flag

            try {
                ctx.callGlobal("f", null);
                fail("first call after interrupt should abort");
            } catch (QuickJSException e) {
                check("interrupt abort", true);
            }
            // Now the one-shot flag is consumed; the sticky terminating flag must make the
            // $_send upcall itself abort uncatchably (try/catch cannot return 'caught').
            try {
                Object r = ctx.callGlobal("f", null);
                fail("$_send checkpoint should abort uncatchably, got: " + r);
            } catch (QuickJSException e) {
                check("$_send uncatchable checkpoint",
                        e.getMessage() != null && e.getMessage().contains("terminated"));
            }
        } finally {
            ctx.destroy();
        }
    }

    private static void testBinaryCodec(QuickJSContext ctx) {
        eq("__yeowWrite ok", Boolean.TRUE,
                ctx.evaluate("__yeowWrite('task', {a:1, b:'x', c:true, d:null, e:[1,2,3], f:{g:'h'}, n:3.5})"));
        eq("binary round-trip",
                "{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,\"e\":[1,2,3],\"f\":{\"g\":\"h\"},\"n\":3.5}",
                ctx.evaluate("JSON.stringify(__yeowRead())"));
        eq("binary empty containers", "{\"ea\":[],\"eo\":{}}",
                ctx.evaluate("__yeowWrite('c', {ea:[], eo:{}}), JSON.stringify(__yeowRead())"));
        // Regression: a 9-byte key's length byte equals T_OBJ_END (9) and must not be
        // mistaken for the object end marker.
        eq("binary key/end-marker collision", "{\"blockType\":\"minecraft:air\"}",
                ctx.evaluate("__yeowWrite('c', {blockType:'minecraft:air'}), JSON.stringify(__yeowRead())"));
        eq("binary non-BMP", "\uD83D\uDE00", ctx.evaluate("__yeowWrite('c', {u:'\\u{1F600}'}), __yeowRead().u"));
        eq("binary overflow", Boolean.FALSE,
                ctx.evaluate("__yeowWrite('c', {s: 'x'.repeat(40000)})"));
        eq("binary unsupported", Boolean.FALSE,
                ctx.evaluate("__yeowWrite('c', {fn: function(){}})"));
    }

    private static void testBinaryDispatch(QuickJSContext ctx) {
        ctx.evaluate("__yeowWrite('task', {t:'cb', p:'1', r:42});"
                + " globalThis.$hm = (json) => { globalThis.__got = (json == null) ? __yeowRead() : JSON.parse(json); };");
        long hm = ctx.bindGlobal("$hm");
        check("bind hm", hm != 0);
        ctx.callHandle(hm, null); // binary: message already in the resident buffer
        eq("binary dispatch", "{\"t\":\"cb\",\"p\":\"1\",\"r\":42}",
                ctx.evaluate("JSON.stringify(globalThis.__got)"));
        ctx.callHandle(hm, "{\"t\":\"cb\",\"p\":\"2\",\"r\":7}"); // JSON fallback
        eq("json dispatch", "{\"t\":\"cb\",\"p\":\"2\",\"r\":7}",
                ctx.evaluate("JSON.stringify(globalThis.__got)"));
    }

    /**
     * $send result path: the Java upcall returns the binary status code 1, telling JS to read
     * the resident buffer (filled here by {@code __yeowWrite}). This mirrors
     * PluginThread/WorkerThread.encodeResult writing the result then returning Integer 1.
     */
    private static void testUpcallResultBinary(QuickJSContext ctx) {
        ctx.setGlobalFunction("statusSend", args -> 1);
        ctx.evaluate("__yeowWrite('res', {ok:true, n:7});"
                + " globalThis.__upResult = (function(){"
                + "   var raw = statusSend('x');"
                + "   return raw === 1 ? __yeowRead() : raw;"
                + " })();");
        eq("upcall binary result status", "{\"ok\":true,\"n\":7}",
                ctx.evaluate("JSON.stringify(globalThis.__upResult)"));

        // Status 0 means "null result" and must not read the buffer.
        ctx.setGlobalFunction("zeroSend", args -> 0);
        eq("upcall status 0 -> null", null,
                ctx.evaluate("(function(){ var raw = zeroSend('x'); return raw === 0 ? null : 'unexpected'; })()"));
    }

    private static void testPromisePump(QuickJSContext ctx) {
        List<Object> ticks = new ArrayList<>();
        ctx.setGlobalFunction("tick", a -> {
            ticks.add(a[0]);
            return null;
        });
        ctx.evaluate("Promise.resolve(41).then(v => tick(String(v + 1)));");
        eq("promise pumped", 1, ticks.size());
        eq("promise value", "42", ticks.get(0));
    }

    private static void testError(QuickJSContext ctx) {
        try {
            ctx.evaluate("throw new Error('evaluate failed');", "boom.js");
            fail("evaluate throw should propagate");
        } catch (QuickJSException e) {
            check("evaluate error", e.getMessage().contains("evaluate failed"));
        }
    }

    private static void testInterrupt(QuickJSContext ignoredCtx) {
        QuickJSContext c = QuickJSContext.create();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            c.interrupt();
        });
        t.setDaemon(true);
        t.start();
        try {
            c.evaluate("while (true) {}");
            fail("interrupt should abort an infinite loop");
        } catch (QuickJSException e) {
            check("interrupt aborted", true);
        } finally {
            c.destroy();
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            System.out.println("  ok   " + what + " = " + actual);
        } else {
            fail(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            fail(what);
        }
    }

    private static void fail(String what) {
        failures++;
        System.err.println("  FAIL " + what);
    }
}
