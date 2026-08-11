package yeow.task;
import yeow.paper.TextUtil;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ScoreboardTasks {
    static final Map<String, Scoreboard> boards = new ConcurrentHashMap<>();

    static Scoreboard sb(JsonObject p) {
        if (p.has("board") && !p.get("board").isJsonNull()) {
            var b = boards.get(p.get("board").getAsString());
            if (b != null) return b;
        }
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    public static String createBoard(JsonObject p) {
        var id = p.get("id").getAsString();
        var b = Bukkit.getScoreboardManager().getNewScoreboard();
        boards.put(id, b);
        return id;
    }

    public static Object deleteBoard(JsonObject p) {
        boards.remove(p.get("id").getAsString());
        return true;
    }

    public static Object createObjective(JsonObject p) {
        var sb = sb(p);
        var name = p.get("name").getAsString();
        var criteria = p.get("criteria").getAsString();
        var displayName = TextUtil.parse(p.get("displayName").getAsString());
        try {
            var obj = sb.registerNewObjective(name, criteria, displayName);
            return Map.of("name", obj.getName(), "displayName", p.get("displayName").getAsString(), "criteria", obj.getCriteria());
        } catch (IllegalArgumentException e) {
            // Objective already exists (e.g., persisted in world or from previous plugin load)
            var existing = sb.getObjective(name);
            if (existing != null) {
                existing.setDisplayName(TextUtil.toLegacy(displayName));
                return Map.of("name", existing.getName(), "displayName", p.get("displayName").getAsString(), "criteria", existing.getCriteria());
            }
            throw e;
        }
    }

    public static Object deleteObjective(JsonObject p) {
        var obj = sb(p).getObjective(p.get("name").getAsString());
        if (obj != null) obj.unregister();
        return true;
    }

    public static Object getObjectives(JsonObject p) {
        return sb(p).getObjectives().stream().map(o -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", o.getName());
            m.put("criteria", o.getCriteria());
            m.put("displaySlot", o.getDisplaySlot() != null ? o.getDisplaySlot().name() : null);
            return m;
        }).toList();
    }

    public static Object setObjectiveDisplay(JsonObject p) {
        var obj = sb(p).getObjective(p.get("name").getAsString());
        if (obj == null) return false;
        if (p.has("slot") && !p.get("slot").isJsonNull()) {
            obj.setDisplaySlot(DisplaySlot.valueOf(p.get("slot").getAsString()));
        } else {
            obj.setDisplaySlot(null);
        }
        return true;
    }

    public static Object getScore(JsonObject p) {
        var obj = sb(p).getObjective(p.get("objective").getAsString());
        if (obj == null) return null;
        try {
            return obj.getScore(p.get("entry").getAsString()).getScore();
        } catch (Exception e) {
            return null; // entry 无分数记录：明确返回 null（而非抛错/丢键）
        }
    }

    public static Object setScore(JsonObject p) {
        var obj = sb(p).getObjective(p.get("objective").getAsString());
        if (obj == null) return false;
        obj.getScore(p.get("entry").getAsString()).setScore(p.get("value").getAsInt());
        return true;
    }

    public static Object resetScore(JsonObject p) {
        var obj = sb(p).getObjective(p.get("objective").getAsString());
        if (obj != null) sb(p).resetScores(p.get("entry").getAsString());
        return true;
    }

    // ── Teams ──
    public static Object createTeam(JsonObject p) {
        var team = sb(p).registerNewTeam(p.get("name").getAsString());
        return Map.of("name", team.getName());
    }

    public static Object deleteTeam(JsonObject p) {
        var team = sb(p).getTeam(p.get("name").getAsString());
        if (team != null) team.unregister();
        return true;
    }

    public static Object getTeam(JsonObject p) { return serializeTeam(sb(p).getTeam(p.get("name").getAsString())); }
    public static Object getTeams(JsonObject p) { return sb(p).getTeams().stream().map(ScoreboardTasks::serializeTeam).filter(Objects::nonNull).toList(); }

    public static Object setTeamDisplayName(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.setDisplayName(TextUtil.toLegacy(TextUtil.parse(p.get("displayName").getAsString())));
        return true;
    }

    public static Object setTeamPrefix(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.setPrefix(TextUtil.toLegacy(TextUtil.parse(p.get("prefix").getAsString())));
        return true;
    }

    public static Object setTeamSuffix(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.setSuffix(TextUtil.toLegacy(TextUtil.parse(p.get("suffix").getAsString())));
        return true;
    }

    public static Object setTeamColor(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.setColor(org.bukkit.ChatColor.valueOf(p.get("color").getAsString().toUpperCase()));
        return true;
    }

    public static Object setTeamFriendlyFire(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.setAllowFriendlyFire(p.get("allow").getAsBoolean());
        return true;
    }

    public static Object setTeamSeeInvisible(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.setCanSeeFriendlyInvisibles(p.get("canSee").getAsBoolean());
        return true;
    }

    public static Object setTeamOption(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        var option = Team.Option.valueOf(p.get("option").getAsString().toUpperCase());
        var status = Team.OptionStatus.valueOf(p.get("value").getAsString().toUpperCase());
        t.setOption(option, status);
        return true;
    }

    public static Object teamAddEntry(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.addEntry(p.get("entry").getAsString());
        return true;
    }

    public static Object teamRemoveEntry(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return false;
        t.removeEntry(p.get("entry").getAsString());
        return true;
    }

    public static Object teamGetEntries(JsonObject p) {
        var t = sb(p).getTeam(p.get("name").getAsString());
        if (t == null) return List.of();
        return new ArrayList<>(t.getEntries());
    }

    public static Object setPlayerBoard(JsonObject p) {
        var pl = Bukkit.getPlayer(java.util.UUID.fromString(p.get("uuid").getAsString()));
        if (pl == null) return false;
        if (p.has("board") && !p.get("board").isJsonNull()) {
            var b = boards.get(p.get("board").getAsString());
            if (b != null) { pl.setScoreboard(b); return true; }
            return false;
        }
        pl.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        return true;
    }

    static Object serializeTeam(Team t) {
        if (t == null) return null;
        var m = new LinkedHashMap<String, Object>();
        m.put("name", t.getName());
        m.put("displayName", t.getDisplayName());
        m.put("prefix", t.getPrefix());
        m.put("suffix", t.getSuffix());
        m.put("color", t.getColor().name());
        m.put("allowFriendlyFire", t.allowFriendlyFire());
        m.put("canSeeFriendlyInvisibles", t.canSeeFriendlyInvisibles());
        m.put("entries", new ArrayList<>(t.getEntries()));
        var opts = new LinkedHashMap<String, String>();
        opts.put("nameTagVisibility", t.getOption(Team.Option.NAME_TAG_VISIBILITY).name());
        opts.put("deathMessageVisibility", t.getOption(Team.Option.DEATH_MESSAGE_VISIBILITY).name());
        opts.put("collisionRule", t.getOption(Team.Option.COLLISION_RULE).name());
        m.put("options", opts);
        return m;
    }
}
