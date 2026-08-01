package yeow.channel;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SyncCallbackHelper {
    static final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    static final Gson gson = new Gson();

    /**
     * Build the Java→JS callback message {@code {"t":"cb","p":"<cbId>","r":<result>}}.
     * Null-safe: task results and service responses may legitimately be null
     * (e.g. Player.get for an offline player), and Map.of would throw NPE on null values.
     */
    public static String cbMessage(String cbId, Object result) {
        var m = new LinkedHashMap<String, Object>();
        m.put("t", "cb");
        m.put("p", cbId);
        m.put("r", result);
        return gson.toJson(m);
    }

    public static class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        final Runnable onComplete;
        volatile Object result;

        Pending() { this.onComplete = null; }
        Pending(Runnable onComplete) { this.onComplete = onComplete; }

        public boolean isDone() { return latch.getCount() == 0; }
        public Object getResult() { return result; }
    }

    public static Pending register(String id) {
        var p = new Pending();
        pending.put(id, p);
        return p;
    }

    public static Pending register(String id, Runnable onComplete) {
        var p = new Pending(onComplete);
        pending.put(id, p);
        return p;
    }

    public static void complete(String id, Object result) {
        var p = pending.remove(id);
        if (p != null) { p.result = result; p.latch.countDown(); if (p.onComplete != null) p.onComplete.run(); }
    }

    public static Object waitFor(String id, long timeoutMs) {
        var p = pending.get(id);
        if (p == null) return null;
        try { p.latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return p.result;
    }

    public static void remove(String id) {
        pending.remove(id);
    }
}
