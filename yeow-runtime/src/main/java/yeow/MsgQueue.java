package yeow;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MsgQueue {
    public final BlockingQueue<String> toJava = new LinkedBlockingQueue<>();
    public final BlockingQueue<String> toJs = new LinkedBlockingQueue<>();
    private final Semaphore jsNotify = new Semaphore(0);
    private volatile Runnable jsWakeListener;

    public void setJsWakeListener(Runnable r) { this.jsWakeListener = r; }

    public void sendJava(String json) { toJava.add(json); }
    public String pollJava() { return toJava.poll(); }
    public void sendJs(String json) {
        toJs.add(json);
        jsNotify.release();
        var w = jsWakeListener; if (w != null) w.run();
    }
    public String pollJs(long ms) throws InterruptedException {
        // Wait for notification, then drain
        var r = toJs.poll();
        if (r != null) return r;
        jsNotify.tryAcquire(ms, TimeUnit.MILLISECONDS);
        return toJs.poll();
    }
    public void clear() { toJava.clear(); toJs.clear(); jsNotify.drainPermits(); }
}
