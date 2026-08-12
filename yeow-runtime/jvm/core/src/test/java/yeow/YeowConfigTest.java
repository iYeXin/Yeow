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

    private static String read(File dataFolder, String name) throws Exception {
        return Files.readString(new File(new File(dataFolder, "runtime"), name).toPath());
    }
}
