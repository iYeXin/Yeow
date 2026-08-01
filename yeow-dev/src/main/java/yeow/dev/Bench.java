package yeow.dev;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.whl.quickjs.wrapper.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class Bench {

    static final Gson gson = new Gson();
    static final int COUNT = 100_000;
    static final int SCHED_N = 200;
    static final int ASYNC_N = 2000;
    static final int SEQ_N = 500;
    static final int THROUGHPUT_N = 50_000;
    static final int TICK_MS = 50;
    static final int BUDGET_NS = 20_000_000;
    static final int IDLE_SPIN_NS = 100_000;

    // ── Scheduler ──
    enum Pri { HIGH, NORMAL, LOW }
    record Task(String type, JsonObject params, Consumer<Object> cb, Pri pri, String plugin) {}

    static class Sched {
        final Queue<Task> q = new ConcurrentLinkedQueue<>();

        void submit(String type, JsonObject params, Consumer<Object> cb) {
            q.add(new Task(type, params, cb, Pri.NORMAL, "bench"));
        }

        void tick(Consumer<Task> exec) {
            long deadline = System.nanoTime() + BUDGET_NS;
            while (!q.isEmpty() && System.nanoTime() < deadline) {
                var t = q.poll();
                if (t != null) exec.accept(t);
            }
            if (System.nanoTime() < deadline) {
                while (System.nanoTime() < deadline) {
                    var t = q.poll();
                    if (t != null) { exec.accept(t); continue; }
                    long end = System.nanoTime() + IDLE_SPIN_NS;
                    while (System.nanoTime() < end && System.nanoTime() < deadline) {
                        if (!q.isEmpty()) break;
                        Thread.onSpinWait();
                    }
                }
            }
        }

        void purge() { q.clear(); }
    }

    // ── MsgQueue ──
    static class MQ {
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        final Semaphore sem = new Semaphore(0);

        void send(String json) {
            q.add(json);
            sem.release();
        }

        String poll(long ms) {
            try {
                var r = q.poll();
                if (r != null) return r;
                sem.tryAcquire(ms, TimeUnit.MILLISECONDS);
                return q.poll();
            } catch (InterruptedException e) { return null; }
        }

        void clear() { q.clear(); sem.drainPermits(); }
    }

    // ── Tick thread ──
    static class TickPump extends Thread {
        final Sched sched;
        final Consumer<Task> exec;
        volatile boolean running = true;

        TickPump(Sched s, Consumer<Task> e) {
            super("tick"); sched = s; exec = e; setDaemon(true);
        }

        public void run() {
            while (running) {
                var t0 = System.nanoTime();
                sched.tick(exec);
                var elapsed = System.nanoTime() - t0;
                var sleep = TICK_MS - elapsed / 1_000_000;
                if (sleep > 0) try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
                }
    }
    }

    // ── Timing ──
    record Stats(long min, long max, double avg, long p50, long p99, int n) {
        static Stats from(long[] d) {
            if (d.length == 0) return new Stats(0, 0, 0, 0, 0, 0);
            Arrays.sort(d);
            int n = d.length;
            long s = 0;
            for (long v : d) s += v;
            return new Stats(d[0], d[n - 1], (double) s / n, d[n / 2], d[(int) (n * 0.99)], n);
        }

        void print(String label) {
            System.out.printf("  %-28s min=%7d  max=%7d  avg=%9.1f  p50=%7d  p99=%7d%n",
                label, min / 1000, max / 1000, avg / 1000, p50 / 1000, p99 / 1000);
        }
    }

    static void printHeader(String title) {
        System.out.printf("%n%s%n", title);
        System.out.printf("  %-28s %8s  %9s  %10s  %8s  %9s%n",
            "stage", "min(us)", "max(us)", "avg(us)", "p50(us)", "p99(us)");
    }

    static long percentile(long[] sorted, double p) {
        var copy = sorted.clone();
        Arrays.sort(copy);
        return copy[(int) (copy.length * p)];
    }

    // ── Main ──
    public static void main(String[] argv) throws Exception {
        var initCode = loadResource("/bench_init.js");
        if (initCode == null) { System.err.println("bench_init.js not found"); return; }

        try (var ctx = QuickJSContext.create()) {
            if (ctx == null) { System.err.println("QuickJSContext.create() failed"); return; }
            var g = ctx.getGlobalObject();
            var sched = new Sched();
            var mq = new MQ();

            var syncResults = new ConcurrentLinkedQueue<long[]>();
            var asyncResults = new ConcurrentLinkedQueue<long[]>();

            // ══════════════════════════════════════════════════════════
            // JNI bindings
            // ══════════════════════════════════════════════════════════

            g.setProperty("$rawEcho", (JSCallFunction) a ->
                a.length > 0 && a[0] != null ? String.valueOf(a[0]) : "");

            g.setProperty("$jsonEcho", (JSCallFunction) a -> {
                try {
                    var j = String.valueOf(a.length > 0 ? a[0] : "{}");
                    var o = gson.fromJson(j, JsonObject.class);
                    o.addProperty("_t", "echo");
                    return gson.toJson(o);
                } catch (Exception e) { return "{}"; }
            });

            // ── Sync call (legacy) ──
            g.setProperty("$callJSON", (JSCallFunction) a -> {
                try {
                    var slot = new long[5];
                    syncResults.add(slot);
                    var t0 = System.nanoTime();
                    var json = String.valueOf(a.length > 0 ? a[0] : "{}");
                    var params = gson.fromJson(json, JsonObject.class);
                    var t1 = System.nanoTime();
                    var f = new CompletableFuture<String>();
                    final long tSubmit = t1;
                    sched.submit("bench", params, r -> {
                        slot[3] = System.nanoTime() - tSubmit;
                        f.complete(gson.toJson(Map.of("r", r)));
                    });
                    var t2 = System.nanoTime();
                    var result = f.get(5, TimeUnit.SECONDS);
                    var t3 = System.nanoTime();
                    slot[0] = t1 - t0;
                    slot[1] = t2 - t1;
                    slot[2] = t3 - t2;
                    slot[4] = t3 - t0;
                    return result;
                } catch (Exception e) { return "err"; }
            });

            g.setProperty("$callRaw", (JSCallFunction) a -> {
                try {
                    var slot = new long[5];
                    syncResults.add(slot);
                    var t0 = System.nanoTime();
                    var data = String.valueOf(a.length > 0 ? a[0] : "");
                    var t1 = System.nanoTime();
                    var f = new CompletableFuture<String>();
                    final long tSubmit = t1;
                    sched.submit("bench", new JsonObject(), r -> {
                        slot[3] = System.nanoTime() - tSubmit;
                        f.complete(data);
                    });
                    var t2 = System.nanoTime();
                    var result = f.get(5, TimeUnit.SECONDS);
                    var t3 = System.nanoTime();
                    slot[0] = t1 - t0;
                    slot[1] = t2 - t1;
                    slot[2] = t3 - t2;
                    slot[4] = t3 - t0;
                    return result;
                } catch (Exception e) { return "err"; }
            });

            // ── Async send (production post() mirror) ──
            var slotMap = new ConcurrentHashMap<String, long[]>();

            g.setProperty("$asyncSendJSON", (JSCallFunction) a -> {
                try {
                    var slot = new long[8];
                    var t0 = System.nanoTime();
                    slot[0] = t0;

                    var json = String.valueOf(a.length > 0 ? a[0] : "{}");
                    var obj = gson.fromJson(json, JsonObject.class);
                    var params = obj.getAsJsonObject("params");
                    var cbId = obj.get("cb").getAsString();
                    slot[1] = System.nanoTime() - t0;

                    slotMap.put(cbId, slot);

                    sched.submit("bench", params, r -> {
                        long tPickup = System.nanoTime();
                        slot[3] = tPickup - t0;
                        slot[4] = System.nanoTime() - t0;

                        var msg = gson.toJson(Map.of("t", "cb", "p", cbId, "r", true));
                        slot[5] = System.nanoTime() - t0;

                        mq.send(msg);
                    });

                    slot[2] = System.nanoTime() - t0;
                    return null;
                } catch (Exception e) { return "err"; }
            });

            g.setProperty("$asyncSendRaw", (JSCallFunction) a -> {
                try {
                    var slot = new long[8];
                    var t0 = System.nanoTime();
                    slot[0] = t0;

                    var cbId = a.length > 0 ? String.valueOf(a[0]) : "cb_0";
                    slot[1] = System.nanoTime() - t0;

                    slotMap.put(cbId, slot);

                    sched.submit("bench", new JsonObject(), r -> {
                        long tPickup = System.nanoTime();
                        slot[3] = tPickup - t0;
                        slot[4] = System.nanoTime() - t0;

                        var msg = gson.toJson(Map.of("t", "cb", "p", cbId, "r", true));
                        slot[5] = System.nanoTime() - t0;

                        mq.send(msg);
                    });

                    slot[2] = System.nanoTime() - t0;
                    return null;
                } catch (Exception e) { return "err"; }
            });

            // ── Heavy async send (production-size payloads) ──
            // Send: ~104 bytes (production world.setBlock)
            // Return: ~120 bytes (Player.getAll with 2 players)
            var heavyResultData = List.of(
                Map.of("uuid", "550e8400-e29b-41d4-a716-446655440000", "name", "PlayerOne"),
                Map.of("uuid", "550e8400-e29b-41d4-a716-446655440001", "name", "PlayerTwo")
            );
            var heavyReturn = gson.toJson(Map.of("t", "cb", "p", "_", "r", heavyResultData));

            g.setProperty("$asyncSendHeavy", (JSCallFunction) a -> {
                try {
                    var slot = new long[8];
                    var t0 = System.nanoTime();
                    slot[0] = t0;

                    var json = String.valueOf(a.length > 0 ? a[0] : "{}");
                    var obj = gson.fromJson(json, JsonObject.class);
                    var params = obj.getAsJsonObject("params");
                    var cbId = obj.get("cb").getAsString();
                    slot[1] = System.nanoTime() - t0;

                    slotMap.put(cbId, slot);

                    sched.submit("bench", params, r -> {
                        long tPickup = System.nanoTime();
                        slot[3] = tPickup - t0;
                        slot[4] = System.nanoTime() - t0;

                        // Return payload with realistic data size (~120 bytes)
                        var msg = heavyReturn.replace("\"_\"", "\"" + cbId + "\"");
                        slot[5] = System.nanoTime() - t0;

                        mq.send(msg);
                    });

                    slot[2] = System.nanoTime() - t0;
                    return null;
                } catch (Exception e) { return "err"; }
            });

            g.setProperty("$nanoTime", (JSCallFunction) a -> System.nanoTime());

            // Load init.js
            ctx.evaluate(initCode, "bench_init.js");

            // ── Base64 function check ──────────────────────────────
            System.out.println("\n=== 0. Base64 functions ===\n");
            try {
                ctx.evaluate("var b64 = Uint8ArrayToBase64(new Uint8Array([72,101,108,108,111]).buffer); if (b64 !== 'SGVsbG8=') throw new Error('encode mismatch: ' + b64);", "test.js");
                System.out.println("  Uint8ArrayToBase64: OK");
                ctx.evaluate("var a = new Uint8Array(Base64ToUint8Array('SGVsbG8=')); if (a[0]!==72||a[4]!==111) throw new Error('decode mismatch');", "test.js");
                System.out.println("  Base64ToUint8Array: OK");
                // Round-trip
                var rt = ctx.evaluate("var a = new Uint8Array(256); for(var i=0;i<256;i++)a[i]=i; var b64 = Uint8ArrayToBase64(a.buffer); var b = new Uint8Array(Base64ToUint8Array(b64)); var ok = true; for(var i=0;i<256;i++)if(a[i]!==b[i]){ok=false;break;} ok", "test.js");
                System.out.println("  Round-trip (256 bytes): " + rt);
            } catch (QuickJSException e) {
                System.out.println("  FAILED: " + e.getMessage());
            }
            System.out.println();

            var hmObj = ctx.getGlobalObject().getProperty("$hm");
            if (!(hmObj instanceof JSFunction hmFunc)) {
                System.err.println("$hm not found in JS context");
                return;
            }

            // ══════════════════════════════════════════════════════════
            // Test 1: Raw JNI echo
            // ══════════════════════════════════════════════════════════
            System.out.println("\n=== 1. Raw String (JNI round trip) ===\n");
            bench(ctx, "testRawEcho", COUNT);

            // ══════════════════════════════════════════════════════════
            // Test 2: JSON 4-way conversion
            // ══════════════════════════════════════════════════════════
            System.out.println("\n=== 2. JSON (stringify\u2192fromJson\u2192toJson\u2192parse) ===\n");
            bench(ctx, "testJsonEcho", COUNT);

            // ══════════════════════════════════════════════════════════
            // Start tick pump
            // ══════════════════════════════════════════════════════════
            var ticker = new TickPump(sched, t -> t.cb().accept(true));
            ticker.start();
            Thread.sleep(100);

            // ══════════════════════════════════════════════════════════
            // Tests 3/4: Sync call (legacy comparison)
            // ══════════════════════════════════════════════════════════
            System.out.println("\n=== 3/4. Sync call via scheduler (f.get) ===\n");

            for (int pass = 1; pass <= 2; pass++) {
                var label = pass == 1 ? "3. JSON path (sync, f.get)" : "4. Raw path (sync, f.get, no JSON)";
                var fn = pass == 1 ? "testCallJSON" : "testCallRaw";
                syncResults.clear();
                ctx.evaluate(fn + "(" + SCHED_N + ")");

                var data = new ArrayList<long[]>();
                syncResults.forEach(d -> data.add(new long[]{d[0], d[1], d[2], d[3], d[4]}));

                var total = data.stream().mapToLong(d -> d[4]).toArray();
                System.out.println("  " + label + " (n=" + SCHED_N + "):");
                printHeader("--- Full data ---");
                Stats.from(total).print("total (sync)");
                Stats.from(data.stream().mapToLong(d -> d[0]).toArray()).print("  parse (gson.fromJson)");
                Stats.from(data.stream().mapToLong(d -> d[1]).toArray()).print("  submit (sched.add)");
                Stats.from(data.stream().mapToLong(d -> d[3] - d[1]).toArray()).print("  queue wait (in sched)");
                Stats.from(data.stream().mapToLong(d -> d[2] - (d[3] - d[1])).toArray()).print("  f.get park/rest");
                System.out.println();

                sched.purge();
            }

            // ══════════════════════════════════════════════════════════
            // Test 5: Batch async (continuous dispatch, matches production)
            // ══════════════════════════════════════════════════════════
            System.out.println("=== 5. Batch async with MsgQueue (production-like continuous dispatch) ===\n");

            for (int pass = 3; pass <= 4; pass++) {
                var label = pass == 3 ? "5a. JSON batch (n=" + ASYNC_N + ")" : "5b. Raw batch (n=" + ASYNC_N + ")";
                var fn = pass == 3 ? "testAsyncJSON" : "testAsyncRaw";
                asyncResults.clear(); slotMap.clear(); sched.purge(); mq.clear();

                ctx.evaluate(fn + "(100);");
                dispatchLoop(hmFunc, mq, slotMap, asyncResults, 2000);
                asyncResults.clear(); slotMap.clear(); sched.purge(); mq.clear();

                ctx.evaluate(fn + "(" + ASYNC_N + ");");
                dispatchLoop(hmFunc, mq, slotMap, asyncResults, 5000);

                var data = new ArrayList<long[]>();
                asyncResults.forEach(d -> data.add(new long[]{d[1], d[2], d[3], d[4], d[5], d[6], d[7]}));

                var totalA = data.stream().mapToLong(d -> d[6]).toArray();
                var queueWaitA = data.stream().mapToLong(d -> d[2] - d[1]).toArray();

                System.out.println("  " + label + " (collected=" + data.size() + "):");
                printHeader("--- Full data (batch) ---");
                Stats.from(totalA).print("total (async)");
                Stats.from(data.stream().mapToLong(d -> d[0]).toArray()).print("  parse (gson.fromJson)");
                Stats.from(data.stream().mapToLong(d -> d[1]).toArray()).print("  submit (sched.add)");
                Stats.from(queueWaitA).print("  queue wait (in sched)");
                Stats.from(data.stream().mapToLong(d -> d[3] - d[2]).toArray()).print("  exec (task no-op)");
                Stats.from(data.stream().mapToLong(d -> d[4] - d[3]).toArray()).print("  callback (toJson+MQ.send)");
                Stats.from(data.stream().mapToLong(d -> d[5] - d[4]).toArray()).print("  MQ poll+parse+dispatch");
                Stats.from(data.stream().mapToLong(d -> d[6] - d[5]).toArray()).print("  JS callback (resolve)");

                // Hot-path: only tasks with idle-spin queue wait (< 10us)
                var hot = data.stream().filter(d -> (d[2] - d[1]) < 10_000).toList();
                if (hot.size() >= 10) {
                    System.out.println();
                    printHeader("--- Hot path (queue wait < 10us, idle-spin, n=" + hot.size() + ") ---");
                    Stats.from(hot.stream().mapToLong(d -> d[6]).toArray()).print("total (async)");
                    Stats.from(hot.stream().mapToLong(d -> d[0]).toArray()).print("  parse (gson.fromJson)");
                    Stats.from(hot.stream().mapToLong(d -> d[1]).toArray()).print("  submit (sched.add)");
                    Stats.from(hot.stream().mapToLong(d -> d[2] - d[1]).toArray()).print("  queue wait (in sched)");
                    Stats.from(hot.stream().mapToLong(d -> d[3] - d[2]).toArray()).print("  exec (task no-op)");
                    Stats.from(hot.stream().mapToLong(d -> d[4] - d[3]).toArray()).print("  callback (toJson+MQ.send)");
                    Stats.from(hot.stream().mapToLong(d -> d[5] - d[4]).toArray()).print("  MQ poll+parse+dispatch");
                    Stats.from(hot.stream().mapToLong(d -> d[6] - d[5]).toArray()).print("  JS callback (resolve)");
                }
                System.out.println();

                sched.purge();
            }

            // ══════════════════════════════════════════════════════════
            // Test 6: Sequential dispatch (submit one → dispatch → next)
            // Best emulates the production await loop:
            //   for(const loc of locs) await world.setBlock(...)
            //   → each iteration: post() → dispatch → resolve → next
            // ══════════════════════════════════════════════════════════
            System.out.println("=== 6. Sequential async (submit+dispatch one-at-a-time, emulates await loop) ===\n");

            for (int pass = 3; pass <= 4; pass++) {
                var label = pass == 3 ? "6a. JSON seq (n=" + SEQ_N + ")" : "6b. Raw seq (n=" + SEQ_N + ")";
                var fn = pass == 3 ? "submitOneJSON" : "submitOneRaw";
                asyncResults.clear(); slotMap.clear(); sched.purge(); mq.clear();

                // Warmup
                for (int i = 0; i < 50; i++) {
                    ctx.evaluate(fn + "(" + i + ");");
                    dispatchFor(hmFunc, mq, slotMap, asyncResults, 1, 500);
                }
                asyncResults.clear(); slotMap.clear(); sched.purge(); mq.clear();

                // Real run
                long seqStart = System.nanoTime();
                for (int i = 0; i < SEQ_N; i++) {
                    ctx.evaluate(fn + "(" + i + ");");
                    dispatchFor(hmFunc, mq, slotMap, asyncResults, 1, 500);
                }
                long seqElapsed = System.nanoTime() - seqStart;

                var data = new ArrayList<long[]>();
                asyncResults.forEach(d -> data.add(new long[]{d[1], d[2], d[3], d[4], d[5], d[6], d[7]}));

                var totalS = data.stream().mapToLong(d -> d[6]).toArray();

                System.out.println("  " + label + " (collected=" + data.size() + ", wall=" + String.format("%.1f", seqElapsed / 1_000_000.0) + "ms, " + String.format("%.1f", (double) seqElapsed / SEQ_N / 1000) + "us/op):");
                printHeader("--- Sequential dispatch (one at a time) ---");
                Stats.from(totalS).print("total (async seq)");
                Stats.from(data.stream().mapToLong(d -> d[0]).toArray()).print("  parse (gson.fromJson)");
                Stats.from(data.stream().mapToLong(d -> d[1]).toArray()).print("  submit (sched.add)");
                Stats.from(data.stream().mapToLong(d -> d[2] - d[1]).toArray()).print("  queue wait (in sched)");
                Stats.from(data.stream().mapToLong(d -> d[3] - d[2]).toArray()).print("  exec (task no-op)");
                Stats.from(data.stream().mapToLong(d -> d[4] - d[3]).toArray()).print("  callback (toJson+MQ.send)");
                Stats.from(data.stream().mapToLong(d -> d[5] - d[4]).toArray()).print("  MQ poll+parse+dispatch");
                Stats.from(data.stream().mapToLong(d -> d[6] - d[5]).toArray()).print("  JS callback (resolve)");
                System.out.println();

                sched.purge();
            }

            // ══════════════════════════════════════════════════════════
            // Test 7: Throughput (steady-state pipeline)
            // ══════════════════════════════════════════════════════════
            System.out.println("=== 7. Throughput (full pipeline: submit\u2192tick\u2192MQ\u2192dispatch\u2192resolve) ===\n");

            for (int pass = 3; pass <= 4; pass++) {
                var label = pass == 3 ? "7a. JSON pipeline" : "7b. Raw pipeline";
                var fn = pass == 3 ? "testAsyncJSON" : "testAsyncRaw";
                asyncResults.clear(); slotMap.clear(); sched.purge(); mq.clear();

                // Submit large batch
                long submitStart = System.nanoTime();
                ctx.evaluate(fn + "(" + THROUGHPUT_N + ");");
                long submitDone = System.nanoTime();

                // Run dispatch until all results collected (with timeout)
                long dispatchStart = System.nanoTime();
                int drained = dispatchCount(hmFunc, mq, slotMap, asyncResults, THROUGHPUT_N, 60_000);
                long dispatchDone = System.nanoTime();

                int collected = drained;
                double wallMs = (dispatchDone - submitStart) / 1_000_000.0;
                double submitMs = (submitDone - submitStart) / 1_000_000.0;
                double dispatchMs = (dispatchDone - dispatchStart) / 1_000_000.0;
                double opsPerSec = collected / ((dispatchDone - submitStart) / 1_000_000_000.0);

                System.out.println("  " + label + " (n=" + THROUGHPUT_N + ", collected=" + collected + "):");
                System.out.printf("    JS submit:      %8.1f ms  (%8.0f ops/s)%n",
                    submitMs, THROUGHPUT_N / (submitMs / 1000));
                System.out.printf("    dispatch drain: %8.1f ms  (%8.0f ops/s)%n",
                    dispatchMs, collected / (dispatchMs / 1000));
                System.out.printf("    end-to-end:     %8.1f ms  (%8.0f ops/s)%n",
                    wallMs, opsPerSec);
                System.out.printf("    per-op:         %8.1f us/op (wall), %.1f us/op (dispatch)%n%n",
                    wallMs * 1000 / collected, dispatchMs * 1000 / collected);

                sched.purge(); mq.clear();
            }

            // ── Scheduler-only throughput (raw tick processing speed) ──
            System.out.println("  --- Scheduler-only throughput (tick\u2192callback, no dispatch) ---\n");
            for (int pass = 3; pass <= 4; pass++) {
                var label = pass == 3 ? "  JSON" : "  Raw";
                var fn = pass == 3 ? "testAsyncJSON" : "testAsyncRaw";
                slotMap.clear(); sched.purge(); mq.clear();

                ctx.evaluate(fn + "(" + THROUGHPUT_N / 2 + ");");

                // Drain MQ as fast as tick produces (only counting tick-side, not dispatch)
                long start = System.nanoTime();
                int schedCompletions = 0;
                while (schedCompletions < THROUGHPUT_N / 2) {
                    var raw = mq.poll(10);
                    if (raw == null) continue;
                    schedCompletions++;
                }
                long elapsed = System.nanoTime() - start;

                double schedOpsPerSec = schedCompletions / (elapsed / 1_000_000_000.0);
                System.out.printf("    %s: %d tasks, %.1f ms, %8.0f sched-ops/s (tick\u2192MQ only)%n",
                    label, schedCompletions, elapsed / 1_000_000.0, schedOpsPerSec);

                sched.purge(); mq.clear();
            }
            System.out.println();

            // ── Dispatch-only throughput (raw MQ→$hm speed) ──
            System.out.println("  --- Dispatch-only throughput (MQ\u2192$hm\u2192resolve, no tick) ---\n");
            for (int pass = 3; pass <= 4; pass++) {
                var label = pass == 3 ? "  JSON" : "  Raw";
                slotMap.clear(); mq.clear();

                // Pre-fill MQ with messages (simulating tick already processed them)
                int m = THROUGHPUT_N / 2;
                for (int i = 0; i < m; i++) {
                    var cbId = "cb_" + i;
                    var slot = new long[8];
                    slot[0] = System.nanoTime();
                    slotMap.put(cbId, slot);
                    var msg = gson.toJson(Map.of("t", "cb", "p", cbId, "r", true));
                    mq.send(msg);
                    if (pass == 3) {
                        slot[5] = System.nanoTime() - slot[0];  // simulates up to MQ.send
                    }
                }

                long start = System.nanoTime();
                int dispatched = dispatchCountNoSlot(hmFunc, mq, m, 10_000);
                long elapsed = System.nanoTime() - start;

                double dispatchOpsPerSec = dispatched / (elapsed / 1_000_000_000.0);
                System.out.printf("    %s: %d msgs, %.1f ms, %8.0f dispatch-ops/s (MQ\u2192$hm only)%n",
                    label, dispatched, elapsed / 1_000_000.0, dispatchOpsPerSec);

                mq.clear(); slotMap.clear();
            }
            System.out.println();

            // ══════════════════════════════════════════════════════════
            // Test 8: Payload size comparison (sequential dispatch)
            // ══════════════════════════════════════════════════════════
            System.out.println("=== 8. Payload size comparison (sequential, one-at-a-time) ===\n");
            System.out.println("  Send payload sizes:");
            System.out.println("    Light (JSON):  57B  {\"type\":\"bench\",\"params\":{...}}");
            System.out.println("    Medium (setBlock): 104B  +4 extra fields");
            System.out.println("    Return payloads:");
            System.out.println("    Light/Medium:  31B  {\"t\":\"cb\",\"p\":\"cb_42\",\"r\":true}");
            System.out.println("    Heavy:         ~120B  +2 players in r[]");
            System.out.println();

            int PAYLOAD_N = 500;
            var payloadLabels = new String[]{"8a. Light (57B/31B)", "8b. Heavy (104B/~120B)"};
            var payloadFns = new String[]{"submitOneJSON", "submitOneHeavy"};

            for (int p = 0; p < 2; p++) {
                asyncResults.clear(); slotMap.clear(); sched.purge(); mq.clear();

                // Warmup
                for (int i = 0; i < 50; i++) {
                    ctx.evaluate(payloadFns[p] + "(" + i + ");");
                    dispatchFor(hmFunc, mq, slotMap, asyncResults, 1, 500);
                }
                asyncResults.clear(); slotMap.clear(); sched.purge(); mq.clear();

                // Real run
                long seqStart = System.nanoTime();
                for (int i = 0; i < PAYLOAD_N; i++) {
                    ctx.evaluate(payloadFns[p] + "(" + i + ");");
                    dispatchFor(hmFunc, mq, slotMap, asyncResults, 1, 500);
                }
                long seqElapsed = System.nanoTime() - seqStart;

                var data = new ArrayList<long[]>();
                asyncResults.forEach(d -> data.add(new long[]{d[1], d[2], d[3], d[4], d[5], d[6], d[7]}));

                var total = data.stream().mapToLong(d -> d[6]).toArray();

                System.out.println("  " + payloadLabels[p] + " (n=" + PAYLOAD_N + ", wall=" + String.format("%.1f", seqElapsed / 1_000_000.0) + "ms, " + String.format("%.1f", (double) seqElapsed / PAYLOAD_N / 1000) + "us/op):");
                printHeader("--- Sequential dispatch ---");
                Stats.from(total).print("total (async)");
                Stats.from(data.stream().mapToLong(d -> d[0]).toArray()).print("  parse (gson.fromJson)");
                Stats.from(data.stream().mapToLong(d -> d[1]).toArray()).print("  submit (sched.add)");
                Stats.from(data.stream().mapToLong(d -> d[2] - d[1]).toArray()).print("  queue wait (in sched)");
                Stats.from(data.stream().mapToLong(d -> d[3] - d[2]).toArray()).print("  exec (task no-op)");
                Stats.from(data.stream().mapToLong(d -> d[4] - d[3]).toArray()).print("  callback (toJson+MQ.send)");
                Stats.from(data.stream().mapToLong(d -> d[5] - d[4]).toArray()).print("  MQ poll+parse+dispatch");
                Stats.from(data.stream().mapToLong(d -> d[6] - d[5]).toArray()).print("  JS callback (resolve)");
                System.out.println();

                sched.purge();
            }

            // ══════════════════════════════════════════════════════════
            // Summary
            // ══════════════════════════════════════════════════════════
            System.out.println("=== Summary: async overhead & throughput ===\n");
            System.out.println("  Single-call latency (p50, sequential, idle-spin):");
            System.out.println("    Light  (57B send /  31B return):  ~8 us  (Test 6a)");
            System.out.println("    Heavy (104B send / ~120B return):  ~X us  (Test 8b)");
            System.out.println("    Raw   (no JSON at all):           ~7 us  (Test 6b)");
            System.out.println();
            System.out.println("  Production extrapolation:");
            System.out.println("    comm baseline = ~8 us (light) or ~X us (heavy)");
            System.out.println("    + Bukkit API exec  + tick budget \u00d72.5  + GC/contention");

            ticker.running = false;
            ticker.join(1000);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Continuous dispatch loop (mirrors PluginThread.run() message loop).
     */
    static void dispatchLoop(JSFunction hmFunc, MQ mq,
                             ConcurrentHashMap<String, long[]> slotMap,
                             ConcurrentLinkedQueue<long[]> results,
                             int timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            var raw = mq.poll(1);
            if (raw == null) continue;

            var tRecv = System.nanoTime();
            JsonObject obj;
            try { obj = gson.fromJson(raw, JsonObject.class); }
            catch (Exception e) { continue; }

            var cbId = obj.has("p") ? obj.get("p").getAsString() : null;
            var slot = cbId != null ? slotMap.remove(cbId) : null;
            if (slot != null) slot[6] = tRecv - slot[0];

            hmFunc.call(raw);

            if (slot != null) {
                slot[7] = System.nanoTime() - slot[0];
                results.add(slot);
            }
        }
    }

    /**
     * Dispatch exactly N messages (or timeout).
     */
    static void dispatchFor(JSFunction hmFunc, MQ mq,
                            ConcurrentHashMap<String, long[]> slotMap,
                            ConcurrentLinkedQueue<long[]> results,
                            int n, int timeoutMs) {
        int count = 0;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (count < n && System.nanoTime() < deadline) {
            var raw = mq.poll(1);
            if (raw == null) continue;

            var tRecv = System.nanoTime();
            JsonObject obj;
            try { obj = gson.fromJson(raw, JsonObject.class); }
            catch (Exception e) { continue; }

            var cbId = obj.has("p") ? obj.get("p").getAsString() : null;
            var slot = cbId != null ? slotMap.remove(cbId) : null;
            if (slot != null) slot[6] = tRecv - slot[0];

            hmFunc.call(raw);

            if (slot != null) {
                slot[7] = System.nanoTime() - slot[0];
                results.add(slot);
            }
            count++;
        }
    }

    /**
     * Dispatch exactly N messages, return how many were dispatched.
     * Records timing via slotMap. Stops at N or timeout.
     */
    static int dispatchCount(JSFunction hmFunc, MQ mq,
                             ConcurrentHashMap<String, long[]> slotMap,
                             ConcurrentLinkedQueue<long[]> results,
                             int n, int timeoutMs) {
        int count = 0;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (count < n && System.nanoTime() < deadline) {
            var raw = mq.poll(1);
            if (raw == null) continue;

            var tRecv = System.nanoTime();
            JsonObject obj;
            try { obj = gson.fromJson(raw, JsonObject.class); }
            catch (Exception e) { continue; }

            var cbId = obj.has("p") ? obj.get("p").getAsString() : null;
            var slot = cbId != null ? slotMap.remove(cbId) : null;
            if (slot != null) slot[6] = tRecv - slot[0];

            hmFunc.call(raw);

            if (slot != null) {
                slot[7] = System.nanoTime() - slot[0];
                results.add(slot);
            }
            count++;
        }
        return count;
    }

    /**
     * Dispatch messages without slot tracking (for throughput-only tests).
     */
    static int dispatchCountNoSlot(JSFunction hmFunc, MQ mq, int n, int timeoutMs) {
        int count = 0;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (count < n && System.nanoTime() < deadline) {
            var raw = mq.poll(1);
            if (raw == null) continue;
            hmFunc.call(raw);
            count++;
        }
        return count;
    }

    static void bench(QuickJSContext ctx, String fn, int n) {
        var warm = Math.min(n, 1000);
        ctx.evaluate(fn + "(" + warm + ")");
        var t0 = System.nanoTime();
        ctx.evaluate(fn + "(" + n + ")");
        var ns = System.nanoTime() - t0;
        System.out.printf("  Total: %.3f ms  %8.2f ns/op%n%n", ns / 1_000_000.0, (double) ns / n);
    }

    static String loadResource(String path) {
        try (var is = Bench.class.getResourceAsStream(path)) {
            return is != null ? new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : null;
        } catch (Exception e) { return null; }
    }
}
