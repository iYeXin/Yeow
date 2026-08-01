package yeow.task;

import com.google.gson.JsonObject;

import java.nio.file.*;
import java.util.List;

public class FsTasks {
    public static Object execute(Path baseDir, String op, JsonObject p) throws Exception {
        return switch (op) {
            case "readFile" -> Files.readString(resolve(baseDir, p.get("path").getAsString()));
            case "writeFile" -> { Files.createDirectories(resolve(baseDir, p.get("path").getAsString()).getParent()); Files.writeString(resolve(baseDir, p.get("path").getAsString()), p.get("data").getAsString()); yield true; }
            case "appendFile" -> { Files.createDirectories(resolve(baseDir, p.get("path").getAsString()).getParent()); Files.writeString(resolve(baseDir, p.get("path").getAsString()), p.get("data").getAsString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); yield true; }
            case "exists" -> Files.exists(resolve(baseDir, p.get("path").getAsString()));
            case "isDirectory" -> Files.isDirectory(resolve(baseDir, p.get("path").getAsString()));
            case "mkdir" -> { Files.createDirectories(resolve(baseDir, p.get("path").getAsString())); yield true; }
            case "list" -> { try (var s = Files.list(resolve(baseDir, p.get("path").getAsString()))) { yield s.map(Path::toString).toList(); } }
            case "delete" -> { if (Files.isDirectory(resolve(baseDir, p.get("path").getAsString()))) { try (var s = Files.walk(resolve(baseDir, p.get("path").getAsString()))) { s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignored) {} }); } } else { Files.deleteIfExists(resolve(baseDir, p.get("path").getAsString())); } yield true; }
            default -> throw new IllegalArgumentException("Unknown fs op: " + op);
        };
    }

    private static Path resolve(Path baseDir, String path) {
        var p = Path.of(path);
        if (p.isAbsolute()) return p.normalize();
        return baseDir.resolve(p).normalize();
    }
}
