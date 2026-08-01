package yeow.profile.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 快照文本格式化：紧凑摘要（控制台）与详细报告（文件）。 */
public final class ProfileFormatter {

    public static String compact(ProfileSnapshot s) {
        var sb = new StringBuilder();
        sb.append("\n  -- Yeow Profile (" + s.windowCount + " x 1s windows) --\n\n");
        sb.append(String.format("  working  %5.1fms/window\n\n", s.workingMsPerWindow));

        String icon = s.health.level().equals("ok") ? "[ok]" : s.health.level().equals("warn") ? "[!]" : "[X]";
        sb.append(String.format("  Health  %s  %d/100\n", icon, s.health.score));
        for (var r : s.health.reasons) sb.append("    " + r + "\n");
        sb.append("\n");

        if (s.hn != null) {
            sb.append("  Realtime (HIGH+NORMAL)  avg " + ms(s.hn.avgMs()) + "  p95 " + ms(s.hn.p95Ms())
                + "  max " + ms(s.hn.maxMs()) + "  " + s.hn.calls() + " calls\n");
        }
        if (s.low != null && s.low.calls() > 0) {
            sb.append("  Bulk (LOW)              avg " + ms(s.low.avgMs()) + "  p95 " + ms(s.low.p95Ms())
                + "  " + s.low.calls() + " calls  (允许积压/延迟)\n");
        }
        sb.append("\n");

        if (s.jsThreads != null && !s.jsThreads.isEmpty()) {
            for (var js : s.jsThreads) {
                String status = js.hung() ? "[!] hung" : js.slow() ? "[!] slow" : "[ok]";
                sb.append(String.format("  JS Thread  %-18s  avg %-7s p95 %-7s max %-7s %s\n",
                    js.plugin(), ms(js.avgMs()), ms(js.p95Ms()), ms(js.maxMs()), status));
            }
            sb.append("\n");
        }

        if (s.events != null && !s.events.isEmpty()) {
            boolean bad = s.events.stream().anyMatch(e -> e.slow() > 0 || e.timeouts() > 0);
            sb.append("  Events  " + s.events.size() + " types  " + (bad ? "[!]" : "[ok]") + "\n");
            for (var ev : s.events.subList(0, Math.min(3, s.events.size()))) {
                sb.append(String.format("    %-22s  avg %-6s max %-6s %d calls",
                    ev.key(), ms(ev.avgMs()), ms(ev.maxMs()), ev.calls()));
                if (ev.slow() > 0) sb.append("  [!] slow×" + ev.slow());
                if (ev.timeouts() > 0) sb.append("  [!] timeout×" + ev.timeouts());
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (s.commands != null && !s.commands.isEmpty()) {
            boolean bad = s.commands.stream().anyMatch(c -> c.slow() > 0 || c.timeouts() > 0);
            sb.append("  Tab-complete  " + s.commands.size() + " types  " + (bad ? "[!]" : "[ok]") + "\n");
            for (var c : s.commands.subList(0, Math.min(3, s.commands.size()))) {
                sb.append(String.format("    %-22s  avg %-6s max %-6s %d calls",
                    c.key(), ms(c.avgMs()), ms(c.maxMs()), c.calls()));
                if (c.slow() > 0) sb.append("  [!] slow×" + c.slow());
                if (c.timeouts() > 0) sb.append("  [!] timeout×" + c.timeouts());
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (s.plugins != null && !s.plugins.isEmpty()) {
            sb.append("  Plugins\n");
            for (var p : s.plugins.subList(0, Math.min(3, s.plugins.size()))) {
                sb.append(String.format("    %-16s  avg %6s/w  %5.0f calls  (%2.0f%%)\n",
                    p.name(), ms(p.avgMsPerWindow()), p.avgCallsPerWindow(), p.pctOfScheduler()));
                for (var t : p.topTasks().subList(0, Math.min(2, p.topTasks().size()))) {
                    sb.append(String.format("      %-24s  %6s/w  %7s/call\n",
                        t.name(), ms(t.avgMsPerWindow()), ms(t.perCallMs())));
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public static String detailed(ProfileSnapshot s, java.util.List<yeow.profile.collector.WindowMetrics> recent) {
        var sb = new StringBuilder(compact(s));
        sb.append("  -----------------------------------------------------------------\n\n");
        if (s.plugins != null) {
            for (var p : s.plugins) {
                sb.append("\n  -- Plugin: " + p.name() + " --\n\n");
                sb.append(String.format("  %-30s %8s %8s %8s %8s\n", "task", "avgMs/w", "maxMs", "calls/w", "perCall"));
                for (var t : p.topTasks()) {
                    sb.append(String.format("  %-30s %7.2f %8.2f %7.0f %8s\n",
                        t.name(), t.avgMsPerWindow(), t.maxMs(), t.avgCallsPerWindow(), ms(t.perCallMs())));
                }
            }
        }
        return sb.toString();
    }

    public static void save(String content, Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException ignored) {}
    }

    private static String ms(double v) {
        return String.format("%.1fms", v);
    }
}
