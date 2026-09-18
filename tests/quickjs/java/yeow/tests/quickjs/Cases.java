package yeow.tests.quickjs;

import wiki.yexin.quickjs.QuickJSContext;
import wiki.yexin.quickjs.QuickJSException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java 侧用例：直接驱动构建出的 wrapper（{@code wiki.yexin.quickjs}），覆盖
 * 上下文生命周期、值映射、上行/下行、Promise 泵、中断与线程亲和，以及保留的
 * 二进制编解码原语。
 *
 * <p>每个用例拿到一个全新的 {@link QuickJSContext}（由 {@link Runner} 创建与销毁）。
 */
public final class Cases {

    private Cases() {}

    private static Runner.Case c(String suite, String name, Runner.Body body) {
        return new Runner.Case(suite, name, body);
    }

    public static List<Runner.Case> all() {
        List<Runner.Case> l = new ArrayList<>();
        context(l);
        values(l);
        polyfill(l);
        textCodec(l);
        callbacks(l);
        jobs(l);
        errors(l);
        lifecycleSafety(l);
        binary(l);
        return l;
    }

    // ── context ─────────────────────────────────────────────────────

    private static void context(List<Runner.Case> l) {
        l.add(c("context", "eval-basic", ctx -> {
            Assert.eq(7L, ctx.evaluate("3 + 4"), "int");
            Assert.eq("ab", ctx.evaluate("'a' + 'b'"), "string");
        }));
        l.add(c("context", "destroy-idempotent", ctx -> {
            ctx.destroy();
            ctx.destroy();
            try {
                ctx.evaluate("1");
                Assert.fail("evaluate after destroy should throw");
            } catch (QuickJSException expected) {
                Assert.contains(String.valueOf(expected.getMessage()), "destroyed", "message");
            }
        }));
        l.add(c("context", "has-global-function", ctx -> {
            ctx.evaluate("function present(x){ return x; }");
            Assert.isTrue(ctx.hasGlobalFunction("present"), "present");
            Assert.eq(Boolean.FALSE, ctx.hasGlobalFunction("absent"), "absent");
        }));
    }

    // ── values ──────────────────────────────────────────────────────

    private static void values(List<Runner.Case> l) {
        l.add(c("values", "numbers", ctx -> {
            Assert.num(1.0, ctx.evaluate("1"), "int");
            Assert.num(1.5, ctx.evaluate("1.5"), "double");
            Assert.num(-3.0, ctx.evaluate("-3"), "negative");
        }));
        l.add(c("values", "bool-null", ctx -> {
            Assert.eq(Boolean.TRUE, ctx.evaluate("true"), "true");
            Assert.eq(Boolean.FALSE, ctx.evaluate("false"), "false");
            Assert.eq(null, ctx.evaluate("null"), "null");
            Assert.eq("undefined", ctx.evaluate("typeof undefined"), "undefined typeof");
        }));
        l.add(c("values", "array", ctx -> {
            Object v = ctx.evaluate("[1, 'two', true]");
            Assert.ok(v instanceof Object[], "array type, was " + (v == null ? "null" : v.getClass()));
            Object[] a = (Object[]) v;
            Assert.eq(3, a.length, "length");
            Assert.eq(1L, a[0], "[0]");
            Assert.eq("two", a[1], "[1]");
            Assert.eq(Boolean.TRUE, a[2], "[2]");
        }));
        l.add(c("values", "object", ctx -> {
            Object v = ctx.evaluate("({a: 1, b: 'x'})");
            Assert.ok(v instanceof Map, "map type, was " + (v == null ? "null" : v.getClass()));
            Map<?, ?> m = (Map<?, ?>) v;
            Assert.eq(1L, m.get("a"), "a");
            Assert.eq("x", m.get("b"), "b");
        }));
        l.add(c("values", "string-non-bmp", ctx -> {
            Assert.eq("\uD83D\uDE00", ctx.evaluate("'\\u{1F600}'"), "emoji");
            Assert.eq("\u4E2D\uD83D\uDE00", ctx.evaluate("'\\u4E2D\\u{1F600}'"), "cjk+emoji");
        }));
    }

    // ── polyfill ────────────────────────────────────────────────────

    private static void polyfill(List<Runner.Case> l) {
        l.add(c("polyfill", "performance", ctx -> {
            Assert.eq("function", ctx.evaluate("typeof performance.now"), "now type");
            Assert.eq("number", ctx.evaluate("typeof performance.timeOrigin"), "timeOrigin type");
            Assert.isTrue(ctx.evaluate("""
                    (function () { var a = performance.now(); var s = 0;
                      for (var i = 0; i < 20000; i++) s += i;
                      return performance.now() >= a; })()
                    """), "monotonic");
        }));
    }

    // ── text codec ──────────────────────────────────────────────────

    private static void textCodec(List<Runner.Case> l) {
        l.add(c("textcodec", "labels", ctx -> {
            Assert.eq("utf-8", ctx.evaluate("new TextEncoder().encoding"), "encoder");
            Assert.eq("utf-8", ctx.evaluate("new TextDecoder().encoding"), "decoder");
            Assert.eq("RangeError", ctx.evaluate(
                    "(function(){ try { new TextEncoder('gbk'); return ''; } catch (e) { return e.name; } })()"), "bad label");
        }));
        l.add(c("textcodec", "utf8-roundtrip", ctx -> {
            Assert.isTrue(ctx.evaluate(
                    "JSON.stringify(Array.from(new TextEncoder().encode('hi'))) === '[104,105]'"), "encode");
            Assert.eq("\u4E2D\uD83D\uDE00",
                    ctx.evaluate("new TextDecoder().decode(new TextEncoder().encode('\\u4E2D\\u{1F600}'))"), "roundtrip");
            Assert.eq("A\uFFFDB",
                    ctx.evaluate("new TextDecoder().decode(new Uint8Array([65,255,66]))"), "invalid -> U+FFFD");
            Assert.eq("AB",
                    ctx.evaluate("new TextDecoder().decode(new Uint8Array([65,66]).buffer)"), "ArrayBuffer");
            Assert.eq("B",
                    ctx.evaluate("new TextDecoder().decode(new Uint8Array([65,66,67]).subarray(1,2))"), "view offset");
        }));
        l.add(c("textcodec", "primitives-hidden", ctx ->
                Assert.eq("undefined", ctx.evaluate("typeof __yeowUtf8Encode"), "hidden")));
    }

    // ── callbacks ───────────────────────────────────────────────────

    private static void callbacks(List<Runner.Case> l) {
        l.add(c("callbacks", "upcall-args-return", ctx -> {
            List<Object> seen = new ArrayList<>();
            ctx.setGlobalFunction("send", a -> {
                for (Object o : a) seen.add(o);
                return "ok";
            });
            ctx.evaluate("function greet(name){ return send('hi', name); }");
            Assert.eq("ok", ctx.callGlobal("greet", "yeow"), "return");
            Assert.eq(2, seen.size(), "argc");
            Assert.eq("hi", seen.get(0), "arg0");
            Assert.eq("yeow", seen.get(1), "arg1");
        }));
        l.add(c("callbacks", "downcall-number", ctx -> {
            ctx.evaluate("function num(){ return 7; }");
            Assert.eq(7L, ctx.callGlobal("num", null), "number");
        }));
        l.add(c("callbacks", "downcall-missing", ctx -> {
            try {
                ctx.callGlobal("doesNotExist", null);
                Assert.fail("missing global should throw");
            } catch (QuickJSException e) {
                Assert.contains(String.valueOf(e.getMessage()), "not found", "message");
            }
        }));
        l.add(c("callbacks", "downcall-throw", ctx -> {
            ctx.evaluate("function boom(){ throw new Error('nope'); }");
            try {
                ctx.callGlobal("boom", null);
                Assert.fail("throw should propagate");
            } catch (QuickJSException e) {
                Assert.contains(String.valueOf(e.getMessage()), "nope", "message");
            }
        }));
        l.add(c("callbacks", "bind-and-call-handle", ctx -> {
            ctx.evaluate("function bound(x){ return 'got:' + x; }"
                    + " function schedule(){ Promise.resolve().then(function(){ globalThis.__job = 1; }); }");
            long h = ctx.bindGlobal("bound");
            Assert.ok(h != 0, "bind");
            Assert.eq("got:hi", ctx.callHandle(h, "hi"), "callHandle");
            Assert.eq(0L, ctx.bindGlobal("noSuchFn"), "missing -> 0");
            long s = ctx.bindGlobal("schedule");
            ctx.callHandle(s, null);
            ctx.drainJobs();
            Assert.eq(1L, ctx.evaluate("globalThis.__job"), "drainJobs ran microtask");
        }));
    }

    // ── jobs ────────────────────────────────────────────────────────

    private static void jobs(List<Runner.Case> l) {
        l.add(c("jobs", "promise-pump", ctx -> {
            List<Object> ticks = new ArrayList<>();
            ctx.setGlobalFunction("tick", a -> { ticks.add(a[0]); return null; });
            ctx.evaluate("Promise.resolve(41).then(v => tick(String(v + 1)));");
            ctx.drainJobs();
            Assert.eq(1, ticks.size(), "pumped");
            Assert.eq("42", ticks.get(0), "value");
        }));
    }

    // ── errors ──────────────────────────────────────────────────────

    private static void errors(List<Runner.Case> l) {
        l.add(c("errors", "evaluate-throw", ctx -> {
            try {
                ctx.evaluate("throw new Error('evaluate failed');", "boom.js");
                Assert.fail("evaluate throw should propagate");
            } catch (QuickJSException e) {
                Assert.contains(String.valueOf(e.getMessage()), "evaluate failed", "message");
            }
        }));
    }

    // ── lifecycle safety ────────────────────────────────────────────

    private static void lifecycleSafety(List<Runner.Case> l) {
        l.add(c("safety", "thread-affinity", ctx -> {
            boolean[] rejected = {false};
            Thread t = new Thread(() -> {
                try { ctx.evaluate("1 + 1"); } catch (QuickJSException e) { rejected[0] = true; }
            });
            t.setDaemon(true);
            t.start();
            t.join(2000);
            Assert.isTrue(rejected[0], "foreign-thread evaluate rejected");
        }));
        l.add(c("safety", "interrupt-infinite-loop", ctx -> {
            QuickJSContext victim = QuickJSContext.create();
            Thread t = new Thread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                victim.interrupt();
            });
            t.setDaemon(true);
            t.start();
            try {
                victim.evaluate("while (true) {}");
                Assert.fail("interrupt should abort an infinite loop");
            } catch (QuickJSException expected) {
                // ok
            } finally {
                victim.destroy();
            }
        }));
        l.add(c("safety", "uncatchable-checkpoint", ctx -> {
            ctx.setGlobalFunction("cb", a -> "ok");
            ctx.evaluate("function f(){ try { return cb(); } catch (e) { return 'caught'; } }");
            Assert.eq("ok", ctx.callGlobal("f", null), "before terminate");
            ctx.interrupt();
            try {
                ctx.callGlobal("f", null);
                Assert.fail("first call after interrupt should abort");
            } catch (QuickJSException expected) {
                // one-shot flag consumed
            }
            try {
                Object r = ctx.callGlobal("f", null);
                Assert.fail("$_send checkpoint should abort uncatchably, got: " + r);
            } catch (QuickJSException e) {
                Assert.contains(String.valueOf(e.getMessage()), "terminated", "message");
            }
        }));
    }

    // ── binary codec（保留但停用；此处仅覆盖编解码原语）────────────

    private static void binary(List<Runner.Case> l) {
        l.add(c("binary", "roundtrip", ctx -> {
            Assert.isTrue(ctx.evaluate(
                    "__yeowWrite('task', {a:1, b:'x', c:true, d:null, e:[1,2,3], f:{g:'h'}, n:3.5})"), "write");
            Assert.eq("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,\"e\":[1,2,3],\"f\":{\"g\":\"h\"},\"n\":3.5}",
                    ctx.evaluate("JSON.stringify(__yeowRead())"), "read");
        }));
        l.add(c("binary", "empty-containers", ctx ->
                Assert.eq("{\"ea\":[],\"eo\":{}}",
                        ctx.evaluate("__yeowWrite('c', {ea:[], eo:{}}), JSON.stringify(__yeowRead())"), "empty")));
        l.add(c("binary", "key-end-marker-collision", ctx ->
                Assert.eq("{\"blockType\":\"minecraft:air\"}",
                        ctx.evaluate("__yeowWrite('c', {blockType:'minecraft:air'}), JSON.stringify(__yeowRead())"),
                        "9-byte key")));
        l.add(c("binary", "non-bmp", ctx ->
                Assert.eq("\uD83D\uDE00", ctx.evaluate("__yeowWrite('c', {u:'\\u{1F600}'}), __yeowRead().u"), "emoji")));
        l.add(c("binary", "overflow-and-unsupported", ctx -> {
            Assert.eq(Boolean.FALSE, ctx.evaluate("__yeowWrite('c', {s: 'x'.repeat(40000)})"), "overflow");
            Assert.eq(Boolean.FALSE, ctx.evaluate("__yeowWrite('c', {fn: function(){}})"), "unsupported");
        }));
        l.add(c("binary", "dispatch-binary-and-json", ctx -> {
            ctx.evaluate("__yeowWrite('task', {t:'cb', p:'1', r:42});"
                    + " globalThis.$got = null;"
                    + " globalThis.$hm = (json) => { globalThis.$got = (json == null) ? __yeowRead() : JSON.parse(json); };");
            long hm = ctx.bindGlobal("$hm");
            Assert.ok(hm != 0, "bind $hm");
            ctx.callHandle(hm, null);
            Assert.eq("{\"t\":\"cb\",\"p\":\"1\",\"r\":42}", ctx.evaluate("JSON.stringify(globalThis.$got)"), "binary");
            ctx.callHandle(hm, "{\"t\":\"cb\",\"p\":\"2\",\"r\":7}");
            Assert.eq("{\"t\":\"cb\",\"p\":\"2\",\"r\":7}", ctx.evaluate("JSON.stringify(globalThis.$got)"), "json");
        }));
        l.add(c("binary", "upcall-result-status", ctx -> {
            ctx.setGlobalFunction("statusSend", a -> 1);
            ctx.evaluate("__yeowWrite('res', {ok:true, n:7});"
                    + " globalThis.__up = (function(){ var raw = statusSend('x'); return raw === 1 ? __yeowRead() : raw; })();");
            Assert.eq("{\"ok\":true,\"n\":7}", ctx.evaluate("JSON.stringify(globalThis.__up)"), "status 1");
            ctx.setGlobalFunction("zeroSend", a -> 0);
            Assert.eq(null, ctx.evaluate(
                    "(function(){ var raw = zeroSend('x'); return raw === 0 ? null : 'unexpected'; })()"), "status 0");
        }));
    }
}
