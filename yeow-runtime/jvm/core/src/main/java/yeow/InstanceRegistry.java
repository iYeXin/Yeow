package yeow;

import java.util.concurrent.ConcurrentHashMap;

/**
 * JS 句柄实例注册表（id → 释放器）。
 *
 * 实例 id 是**不透明句柄**--不带任何业务信息（不约定前缀/格式）；
 * 释放语义由注册方（平台）以闭包提供。JS 侧 GC 回收句柄后经
 * lifecycle gc-collect 通道回传原始 id，运行时查表释放。
 */
public class InstanceRegistry {
    private final ConcurrentHashMap<String, Runnable> releasers = new ConcurrentHashMap<>();

    public void register(String id, Runnable releaser) {
        if (id != null && releaser != null) releasers.put(id, releaser);
    }

    /** 释放并移除；未知 id（未注册/已释放）为无操作。 */
    public void release(String id) {
        var r = releasers.remove(id);
        if (r != null) r.run();
    }

    public void clear() { releasers.clear(); }
}
