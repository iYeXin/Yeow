package yeow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 平台感知默认值：Folia 专用/语义不同参数在 `folia:` section，不与 Paper 顶层混在一起。 */
class YeowConfigTest {

    @TempDir
    Path tmp;

    @Test
    void paperDefaults() {
        var cfg = new YeowConfig(tmp.resolve("paper").toFile(), false);
        assertEquals(20_000_000L, cfg.tickBudgetNs());
        assertTrue(cfg.autoDemote());
        assertEquals(100, cfg.idleSpinUs());
        assertEquals(100, cfg.maxInflight());
        assertEquals(2000, cfg.schedulerIdleWaitUs());
        // 0.5.3：资源内存缓存默认启用；不可信原生服务默认允许加载（仅警告）
        assertTrue(cfg.assetsCacheEnabled());
        assertEquals(30L * 1024 * 1024, cfg.assetsCacheMaxBytes());
        assertTrue(cfg.nativeServiceAllowUntrusted());
    }

    @Test
    void foliaDefaults() throws Exception {
        var cfg = new YeowConfig(tmp.resolve("folia").toFile(), true);
        // Folia：预算/自旋语义不同，读 folia section
        assertEquals(20_000_000L, cfg.tickBudgetNs());
        assertEquals(100, cfg.maxInflight());
        assertEquals(2000, cfg.schedulerIdleWaitUs());
        // 生成的默认文件包含 folia: section
        var text = read(tmp.resolve("folia").toFile(), "config.yml");
        assertTrue(text.contains("folia:"), "config.yml 应含 folia: section");
        assertTrue(text.contains("scheduler-idle-wait-us: 2000"));
    }

    @Test
    void foliaBudgetOverridesTopLevel() throws Exception {
        var dir = tmp.resolve("override").toFile();
        var runtime = new File(dir, "runtime");
        runtime.mkdirs();
        // 顶层 tick-budget-ms 是 Paper 语义；folia.tick-budget-ms 生效于 Folia
        Files.writeString(new File(runtime, "config.yml").toPath(),
            "tick-budget-ms: 5\nfolia:\n  tick-budget-ms: 15\n");
        var cfg = new YeowConfig(dir, true);
        assertEquals(15_000_000L, cfg.tickBudgetNs());
        var paper = new YeowConfig(dir, false);
        assertEquals(5_000_000L, paper.tickBudgetNs());
    }

    @Test
    void existingFileKeysArePreserved() throws Exception {
        var dir = tmp.resolve("existing").toFile();
        var runtime = new File(dir, "runtime");
        runtime.mkdirs();
        Files.writeString(new File(runtime, "config.yml").toPath(),
            "folia:\n  scheduler-idle-wait-us: 5000\n  max-inflight: 50\n");
        var cfg = new YeowConfig(dir, true);
        assertEquals(5000, cfg.schedulerIdleWaitUs());
        assertEquals(50, cfg.maxInflight());
        // 缺失键补默认
        assertEquals(20_000_000L, cfg.tickBudgetNs());
    }

    @Test
    void nativeAllowUntrustedCanBeDisabled() throws Exception {
        var dir = tmp.resolve("native").toFile();
        var runtime = new File(dir, "runtime");
        runtime.mkdirs();
        Files.writeString(new File(runtime, "config.yml").toPath(),
            "native-service-allow-untrusted: false\n");
        var cfg = new YeowConfig(dir, false);
        assertFalse(cfg.nativeServiceAllowUntrusted());
        // 未显式配置时默认允许
        var cfg2 = new YeowConfig(tmp.resolve("native2").toFile(), false);
        assertTrue(cfg2.nativeServiceAllowUntrusted());
    }

    @Test
    void assetsCacheCanBeDisabled() throws Exception {
        var dir = tmp.resolve("assets").toFile();
        var runtime = new File(dir, "runtime");
        runtime.mkdirs();
        Files.writeString(new File(runtime, "config.yml").toPath(),
            "assets:\n  cache-enabled: false\n");
        var cfg = new YeowConfig(dir, false);
        assertFalse(cfg.assetsCacheEnabled());
    }

    @Test
    void assetsCacheMaxBytesConfigurable() throws Exception {
        var dir = tmp.resolve("assets-max").toFile();
        var runtime = new File(dir, "runtime");
        runtime.mkdirs();
        Files.writeString(new File(runtime, "config.yml").toPath(),
            "assets:\n  cache-enabled: true\n  cache-max-bytes: 1048576\n");
        var cfg = new YeowConfig(dir, false);
        assertEquals(1024 * 1024, cfg.assetsCacheMaxBytes());
    }

    @Test
    void missingKeysAreWrittenBack() throws Exception {
        // 平滑升级：旧配置文件缺失新字段 → 加载时合并默认并写回，用户值保留
        var dir = tmp.resolve("upgrade").toFile();
        var runtime = new File(dir, "runtime");
        runtime.mkdirs();
        var cfgFile = new File(runtime, "config.yml");
        Files.writeString(cfgFile.toPath(), "tick-budget-ms: 5\n");
        assertFalse(Files.readString(cfgFile.toPath()).contains("native-service-allow-untrusted"));
        new YeowConfig(dir, false);
        var after = Files.readString(cfgFile.toPath());
        assertTrue(after.contains("tick-budget-ms: 5"), "用户值必须保留");
        assertTrue(after.contains("native-service-allow-untrusted: true"), "新字段必须补上并写回");
        assertTrue(after.contains("cache-enabled: true"), "新增 assets 段必须补上并写回");
        assertTrue(after.contains("cache-max-bytes: 31457280"), "新增 assets 阈值必须补上并写回");
    }

    @Test
    void completeFileIsUntouched() throws Exception {
        // 无缺失时不动文件（保留格式，不做多余写回）
        var dir = tmp.resolve("stable").toFile();
        new YeowConfig(dir, false);
        var cfgFile = new File(new File(dir, "runtime"), "config.yml");
        var before = Files.readString(cfgFile.toPath());
        new YeowConfig(dir, false);
        var after = Files.readString(cfgFile.toPath());
        assertEquals(before, after);
    }

    private static String read(File dataFolder, String name) throws Exception {
        return Files.readString(new File(new File(dataFolder, "runtime"), name).toPath());
    }
}
