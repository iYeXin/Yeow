package wiki.yexin.quickjs;

import java.io.Closeable;
import java.util.HashMap;

/**
 * A single-threaded QuickJS context.
 *
 * <p>This is the Yeow-specific bridge: the Java &lt;-&gt; JS boundary is limited to
 * context lifecycle, {@link #evaluate}, global function registration
 * ({@link #setGlobalFunction}, a JS -&gt; Java upcall) and global function
 * invocation ({@link #callGlobal}, a Java -&gt; JS downcall), plus the promise
 * microtask pump and the interrupt hook. JS objects never cross the boundary;
 * values are converted to/from plain Java types.
 *
 * <p>All methods must be called from the thread that created the context, except
 * {@link #interrupt()}, which is thread-safe (it only flips a native atomic flag).
 * A foreign-thread call to any other method throws {@link QuickJSException} instead
 * of corrupting the engine.
 */
public final class QuickJSContext implements Closeable {

    /** A Java function callable from JS. Arguments are converted from JS values. */
    @FunctionalInterface
    public interface Callback {
        Object call(Object... args);
    }

    /** Resident transport buffer size shared with native (bytes). */
    public static final int BUFFER_SIZE = 16 * 1024;

    private volatile long handle;
    private final long ownerThreadId = Thread.currentThread().threadId();
    private final java.nio.ByteBuffer buffer =
            java.nio.ByteBuffer.allocateDirect(BUFFER_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    private final HashMap<Integer, Callback> callbacks = new HashMap<>();
    private int callbackSeq = 0;

    private QuickJSContext() {}

    public static QuickJSContext create() {
        QuickJSNativeLoader.load();
        QuickJSContext ctx = new QuickJSContext();
        long h = ctx.nativeCreate();
        if (h == 0) {
            throw new QuickJSException("Failed to create QuickJS context");
        }
        ctx.handle = h;
        ctx.nativeRegisterBuffer(h, ctx.buffer);
        return ctx;
    }

    /** The resident transport buffer shared with native (little-endian). */
    public java.nio.ByteBuffer buffer() {
        return buffer;
    }

    public Object evaluate(String code) {
        return evaluate(code, "unknown.js");
    }

    public Object evaluate(String code, String fileName) {
        checkAlive();
        if (code == null) throw new NullPointerException("code");
        return nativeEvaluate(handle, code, fileName == null ? "unknown.js" : fileName);
    }

    /** Registers {@code name} on the JS global object, dispatching to {@code fn}. */
    public void setGlobalFunction(String name, Callback fn) {
        checkAlive();
        if (name == null || fn == null) throw new NullPointerException("name/callback");
        int id = ++callbackSeq;
        callbacks.put(id, fn);
        nativeSetGlobalFunction(handle, name, id);
    }

    /** Calls the global function {@code name} with a single string argument. */
    public Object callGlobal(String name, String arg) {
        checkAlive();
        if (name == null) throw new NullPointerException("name");
        return nativeCallGlobal(handle, name, arg);
    }

    /** Whether a callable global function with this name exists. */
    public boolean hasGlobalFunction(String name) {
        checkAlive();
        if (name == null) throw new NullPointerException("name");
        return nativeHasGlobalFunction(handle, name);
    }

    /** Binds a global function and returns a handle for repeated calls (0 if absent). */
    public long bindGlobal(String name) {
        checkAlive();
        if (name == null) throw new NullPointerException("name");
        return nativeBindGlobal(handle, name);
    }

    /** Calls a handle returned by {@link #bindGlobal} with a single string argument. */
    public Object callHandle(long fnHandle, String arg) {
        checkAlive();
        return nativeCallHandle(handle, fnHandle, arg);
    }

    /** Runs all pending microtasks in one native transition. */
    public void drainJobs() {
        checkAlive();
        nativeDrainJobs(handle);
    }

    /**
     * Requests that the currently executing JS code be aborted. Thread-safe;
     * one-shot. Aborts the current (or next) evaluate/call with an error.
     *
     * <p>The interrupt is raised as an <b>uncatchable</b> error: JS {@code catch}
     * and {@code finally} blocks do not run for it. It is only observed at
     * interpreter poll points, so a long-running native operation (e.g.
     * catastrophic regex backtracking) may not be interrupted promptly.
     */
    public void interrupt() {
        if (handle != 0) nativeInterrupt(handle);
    }

    public void destroy() {
        if (handle == 0) return;
        checkThread();
        long h = handle;
        handle = 0;
        callbacks.clear();
        nativeDestroy(h);
    }

    @Override
    public void close() {
        destroy();
    }

    /** Invoked from native code only. */
    Object invokeCallback(int id, Object[] args) {
        Callback cb = callbacks.get(id);
        if (cb == null) return null;
        return cb.call(args);
    }

    private void checkThread() {
        if (Thread.currentThread().threadId() != ownerThreadId) {
            throw new QuickJSException(
                    "QuickJS context must be used from its creating thread (owner=" + ownerThreadId + ")");
        }
    }

    private void checkAlive() {
        checkThread();
        if (handle == 0) {
            throw new QuickJSException("QuickJS context has been destroyed");
        }
    }

    private native long nativeCreate();

    private native void nativeRegisterBuffer(long handle, java.nio.ByteBuffer buffer);

    private native void nativeDestroy(long handle);

    private native Object nativeEvaluate(long handle, String code, String fileName);

    private native void nativeSetGlobalFunction(long handle, String name, int callbackId);

    private native Object nativeCallGlobal(long handle, String name, String arg);

    private native boolean nativeHasGlobalFunction(long handle, String name);

    private native long nativeBindGlobal(long handle, String name);

    private native Object nativeCallHandle(long handle, long fnHandle, String arg);

    private native void nativeDrainJobs(long handle);

    private native void nativeInterrupt(long handle);
}
