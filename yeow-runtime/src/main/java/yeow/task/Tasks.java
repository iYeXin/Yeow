package yeow.task;

import com.google.gson.JsonObject;
import yeow.YeowRuntime;

import java.util.Map;

public class Tasks {
    public static Object execute(String taskType, JsonObject p) throws Exception {
        return switch (taskType) {
            // Player
            case "player.get"        -> PlayerTasks.get(p);
            case "player.getAll"     -> PlayerTasks.getAll();
            case "player.getPing"    -> PlayerTasks.getPing(p);
            case "player.getGamemode"-> PlayerTasks.getGamemode(p);
            case "player.setGamemode"-> PlayerTasks.setGamemode(p);
            case "player.getHealth"  -> PlayerTasks.getHealth(p);
            case "player.setHealth"  -> PlayerTasks.setHealth(p);
            case "player.sendMessage"-> PlayerTasks.sendMessage(p);
            case "player.kick"       -> PlayerTasks.kick(p);
            case "player.getFood"    -> PlayerTasks.getFood(p);
            case "player.setFood"    -> PlayerTasks.setFood(p);
            case "player.getExp"     -> PlayerTasks.getExp(p);
            case "player.setExp"     -> PlayerTasks.setExp(p);
            case "player.getLevel"   -> PlayerTasks.getLevel(p);
            case "player.setLevel"   -> PlayerTasks.setLevel(p);
            case "player.isOp"       -> PlayerTasks.getOp(p);
            case "player.getAllowFlight"-> PlayerTasks.getAllowFlight(p);
            case "player.setAllowFlight"-> PlayerTasks.setAllowFlight(p);
            case "player.isFlying"   -> PlayerTasks.getFlying(p);
            case "player.setFlying"  -> PlayerTasks.setFlying(p);
            case "player.isSneaking" -> PlayerTasks.isSneaking(p);
            case "player.isSprinting" -> PlayerTasks.isSprinting(p);
            case "player.getBedLocation" -> PlayerTasks.getBedLocation(p);
            case "player.getWalkSpeed"-> PlayerTasks.getWalkSpeed(p);
            case "player.setWalkSpeed"-> PlayerTasks.setWalkSpeed(p);
            case "player.getFlySpeed" -> PlayerTasks.getFlySpeed(p);
            case "player.setFlySpeed" -> PlayerTasks.setFlySpeed(p);
            case "player.getWorld"     -> PlayerTasks.getWorld(p);
            case "player.getLocation"  -> PlayerTasks.getLocation(p);
            case "player.getDisplayName"-> PlayerTasks.getDisplayName(p);
            case "player.setDisplayName"-> PlayerTasks.setDisplayName(p);
            case "player.getSaturation" -> PlayerTasks.getSaturation(p);
            case "player.getTotalExperience"-> PlayerTasks.getTotalExperience(p);
            case "player.sendTitle"    -> PlayerTasks.sendTitle(p);
            case "player.playSound"    -> PlayerTasks.playSound(p);
            case "player.stopSound"   -> SoundTasks.stopSound(p);
            case "player.stopAllSounds" -> SoundTasks.stopAllSounds(p);
            case "player.giveExp"      -> PlayerTasks.giveExp(p);
            case "player.hasPermission"-> PlayerTasks.hasPermission(p);
            case "player.teleport"     -> PlayerTasks.teleport(p);
            case "player.sendActionBar" -> PlayerTasks.sendActionBar(p);
            case "player.sendResourcePack" -> PlayerTasks.sendResourcePack(p);
            case "player.isOnline"   -> PlayerTasks.isOnline(p);
            case "player.getItemInMainHand" -> PlayerTasks.getItemInMainHand(p);
            case "player.getItemInOffHand" -> PlayerTasks.getItemInOffHand(p);
            // Command
            case "command.register"     -> CommandTasks.register(p);
            case "command.dispatch"     -> CommandTasks.dispatch(p);
            case "command.unregisterAll" -> CommandTasks.unregisterAll(p.get("pluginName").getAsString());
            // Entity
            case "entity.get"          -> EntityTasks.get(p);
            case "entity.getType"      -> EntityTasks.getType(p);
            case "entity.getName"      -> EntityTasks.getName(p);
            case "entity.getCustomName"-> EntityTasks.getCustomName(p);
            case "entity.setCustomName"-> EntityTasks.setCustomName(p);
            case "entity.setCustomNameVisible"-> EntityTasks.setCustomNameVisible(p);
            case "entity.getWorld"     -> EntityTasks.getWorld(p);
            case "entity.getLocation"  -> EntityTasks.getLocation(p);
            case "entity.isGlowing"    -> EntityTasks.isGlowing(p);
            case "entity.setGlowing"   -> EntityTasks.setGlowing(p);
            case "entity.isInvulnerable"-> EntityTasks.isInvulnerable(p);
            case "entity.setInvulnerable"-> EntityTasks.setInvulnerable(p);
            case "entity.isSilent"     -> EntityTasks.isSilent(p);
            case "entity.setSilent"    -> EntityTasks.setSilent(p);
            case "entity.hasGravity"   -> EntityTasks.hasGravity(p);
            case "entity.setGravity"   -> EntityTasks.setGravity(p);
            case "entity.getPassengers"-> EntityTasks.getPassengers(p);
            case "entity.getVehicle"   -> EntityTasks.getVehicle(p);
            case "entity.getBoundingBox" -> EntityTasks.getBoundingBox(p);
            case "entity.getHealth"    -> EntityTasks.getHealth(p);
            case "entity.setHealth"    -> EntityTasks.setHealth(p);
            case "entity.getMaxHealth" -> EntityTasks.getMaxHealth(p);
            case "entity.isDead"       -> EntityTasks.isDead(p);
            case "entity.remove"       -> EntityTasks.remove(p);
            case "entity.teleport"     -> EntityTasks.teleport(p);
            // Potion
            case "entity.addPotionEffect" -> PotionTasks.addPotionEffect(p);
            case "entity.removePotionEffect" -> PotionTasks.removePotionEffect(p);
            case "entity.clearPotionEffects" -> PotionTasks.clearPotionEffects(p);
            case "entity.getActivePotionEffects" -> PotionTasks.getActivePotionEffects(p);
            // PDC
            case "pdc.get"           -> PdcTasks.get(p);
            case "pdc.set"           -> PdcTasks.set(p);
            case "pdc.has"           -> PdcTasks.has(p);
            case "pdc.remove"        -> PdcTasks.remove(p);
            case "pdc.keys"          -> PdcTasks.keys(p);
            // World
            case "world.get"           -> WorldTasks.get(p);
            case "world.getAll"        -> WorldTasks.getAll();
            case "world.getTime"       -> WorldTasks.getTime(p);
            case "world.setTime"       -> WorldTasks.setTime(p);
            case "world.getStorm"      -> WorldTasks.getStorm(p);
            case "world.setStorm"      -> WorldTasks.setStorm(p);
            case "world.getThundering" -> WorldTasks.getThundering(p);
            case "world.setThundering" -> WorldTasks.setThundering(p);
            case "world.getDifficulty" -> WorldTasks.getDifficulty(p);
            case "world.setDifficulty" -> WorldTasks.setDifficulty(p);
            case "world.getSpawnLocation"-> WorldTasks.getSpawnLocation(p);
            case "world.setSpawnLocation"-> WorldTasks.setSpawnLocation(p);
            case "world.getGameRule"   -> WorldTasks.getGameRule(p);
            case "world.setGameRule"   -> WorldTasks.setGameRule(p);
            case "world.getBiome"      -> WorldTasks.getBiome(p);
            case "world.getHighestBlockY" -> WorldTasks.getHighestBlockY(p);
            case "world.getChunkAt"      -> WorldTasks.getChunkAt(p);
            case "world.isChunkLoaded"   -> WorldTasks.isChunkLoaded(p);
            case "world.loadChunk"       -> WorldTasks.loadChunk(p);
            case "world.unloadChunk"     -> WorldTasks.unloadChunk(p);
            case "world.getBlockLightLevel" -> WorldTasks.getBlockLightLevel(p);
            case "world.getSkyLightLevel"   -> WorldTasks.getSkyLightLevel(p);
            // Chunk
            case "chunk.getSnapshot"     -> ChunkTasks.getSnapshot(p);
            case "chunk.getTopSnapshot"  -> ChunkTasks.getTopSnapshot(p);
            case "world.getBlock"      -> WorldTasks.getBlock(p);
            case "world.setBlock"      -> WorldTasks.setBlock(p);
            case "world.getEntities"   -> WorldTasks.getEntities(p);
            case "world.getPlayers"    -> WorldTasks.getPlayers(p);
            case "world.getNearbyEntities"-> WorldTasks.getNearbyEntities(p);
            case "world.dropItem"      -> WorldTasks.dropItem(p);
            case "world.strikeLightning"-> WorldTasks.strikeLightning(p);
            case "world.strikeLightningEffect"-> WorldTasks.strikeLightningEffect(p);
            case "world.createExplosion"-> WorldTasks.createExplosion(p);
            case "world.spawnEntity"   -> WorldTasks.spawnEntity(p);
            case "world.spawnItem"     -> WorldTasks.spawnItem(p);
            case "world.playSound"     -> SoundTasks.playWorldSound(p);
            case "world.spawnParticle" -> ParticleTasks.spawnParticle(p);
            // Block
            case "block.breakNaturally" -> BlockTasks.breakNaturally(p);
            // Material（材料级静态判断）
            case "material.isSolid"  -> MaterialTasks.isSolid(p);
            case "material.isLiquid" -> MaterialTasks.isLiquid(p);
            case "material.isAir"    -> MaterialTasks.isAir(p);
            // Inventory
            case "inventory.getItem"   -> InventoryTasks.getItem(p);
            case "inventory.setItem"   -> InventoryTasks.setItem(p);
            case "inventory.addItem"   -> InventoryTasks.addItem(p);
            case "inventory.removeItem"-> InventoryTasks.removeItem(p);
            case "inventory.clear"     -> InventoryTasks.clear(p);
            // Event
            case "event.subscribe"   -> { var rt = YeowRuntime.inst(); rt.getEventBridge().subscribe(p.get("pluginName").getAsString(), p.get("eventType").getAsString(), p.get("callbackId").getAsString()); yield true; }
            case "event.unsubscribe" -> { var rt = YeowRuntime.inst(); rt.getEventBridge().unsubscribe(p.get("pluginName").getAsString(), p.get("eventType").getAsString()); yield true; }
            case "event.complete"    -> { var cb = p.get("callbackId").getAsString(); var modsJson = p.has("mods") && !p.get("mods").isJsonNull() ? p.get("mods").toString() : "{}"; var mods = new com.google.gson.Gson().fromJson(modsJson, Object.class); yeow.channel.SyncCallbackHelper.complete(cb, mods); yield true; }
            case "command.tabComplete" -> { var cb = p.get("callbackId").getAsString(); var compsJson = p.has("completions") && !p.get("completions").isJsonNull() ? p.get("completions").toString() : "[]"; var c = new com.google.gson.Gson().fromJson(compsJson, Object.class); yeow.channel.SyncCallbackHelper.complete(cb, c); yield true; }
            // GUI
            case "gui.create"        -> GuiTasks.createGUI(p);
            case "gui.open"          -> GuiTasks.open(p);
            case "gui.close"         -> GuiTasks.close(p);
            case "gui.destroy"       -> GuiTasks.destroy(p);
            case "gui.setItem"       -> GuiTasks.setItem(p);
            case "gui.fill"          -> GuiTasks.fill(p);
            case "gui.clear"         -> GuiTasks.clear(p);
            // BossBar
            case "bossbar.create"    -> BossBarTasks.create(p);
            case "bossbar.destroy"   -> BossBarTasks.destroy(p);
            case "bossbar.setTitle"  -> BossBarTasks.setTitle(p);
            case "bossbar.setProgress" -> BossBarTasks.setProgress(p);
            case "bossbar.setColor"  -> BossBarTasks.setColor(p);
            case "bossbar.setStyle"  -> BossBarTasks.setStyle(p);
            case "bossbar.setVisible" -> BossBarTasks.setVisible(p);
            case "bossbar.addPlayer" -> BossBarTasks.addPlayer(p);
            case "bossbar.removePlayer" -> BossBarTasks.removePlayer(p);
            case "bossbar.removeAll" -> BossBarTasks.removeAll(p);
            case "bossbar.addFlag"   -> BossBarTasks.addFlag(p);
            case "bossbar.removeFlag" -> BossBarTasks.removeFlag(p);
            // Advancement
            case "advancement.grant" -> AdvancementTasks.grant(p);
            case "advancement.revoke" -> AdvancementTasks.revoke(p);
            case "advancement.getProgress" -> AdvancementTasks.getProgress(p);
            case "advancement.awardCriteria" -> AdvancementTasks.awardCriteria(p);
            case "advancement.revokeCriteria" -> AdvancementTasks.revokeCriteria(p);
            // Recipe
            case "recipe.add"        -> RecipeTasks.addRecipe(p);
            case "recipe.remove"     -> RecipeTasks.removeRecipe(p);
            case "recipe.getForItem" -> RecipeTasks.getRecipesFor(p);
            // Scoreboard
            case "scoreboard.createBoard" -> ScoreboardTasks.createBoard(p);
            case "scoreboard.deleteBoard" -> ScoreboardTasks.deleteBoard(p);
            case "scoreboard.createObjective" -> ScoreboardTasks.createObjective(p);
            case "scoreboard.deleteObjective" -> ScoreboardTasks.deleteObjective(p);
            case "scoreboard.getObjectives" -> ScoreboardTasks.getObjectives(p);
            case "scoreboard.setObjectiveDisplay" -> ScoreboardTasks.setObjectiveDisplay(p);
            case "scoreboard.getScore" -> ScoreboardTasks.getScore(p);
            case "scoreboard.setScore" -> ScoreboardTasks.setScore(p);
            case "scoreboard.resetScore" -> ScoreboardTasks.resetScore(p);
            case "scoreboard.createTeam" -> ScoreboardTasks.createTeam(p);
            case "scoreboard.deleteTeam" -> ScoreboardTasks.deleteTeam(p);
            case "scoreboard.getTeam" -> ScoreboardTasks.getTeam(p);
            case "scoreboard.getTeams" -> ScoreboardTasks.getTeams(p);
            case "scoreboard.setTeamDisplayName" -> ScoreboardTasks.setTeamDisplayName(p);
            case "scoreboard.setTeamPrefix" -> ScoreboardTasks.setTeamPrefix(p);
            case "scoreboard.setTeamSuffix" -> ScoreboardTasks.setTeamSuffix(p);
            case "scoreboard.setTeamColor" -> ScoreboardTasks.setTeamColor(p);
            case "scoreboard.setTeamFriendlyFire" -> ScoreboardTasks.setTeamFriendlyFire(p);
            case "scoreboard.setTeamSeeInvisible" -> ScoreboardTasks.setTeamSeeInvisible(p);
            case "scoreboard.setTeamOption" -> ScoreboardTasks.setTeamOption(p);
            case "scoreboard.teamAddEntry" -> ScoreboardTasks.teamAddEntry(p);
            case "scoreboard.teamRemoveEntry" -> ScoreboardTasks.teamRemoveEntry(p);
            case "scoreboard.teamGetEntries" -> ScoreboardTasks.teamGetEntries(p);
            case "scoreboard.setPlayerBoard" -> ScoreboardTasks.setPlayerBoard(p);
            // Server
            case "server.broadcast" -> { org.bukkit.Bukkit.broadcast(TextUtil.parse(p.get("message").getAsString())); yield true; }
            case "server.getMotd"   -> org.bukkit.Bukkit.getMotd();
            case "server.getVersion" -> org.bukkit.Bukkit.getVersion();
            // TPS 为 Paper 平台指标（Bukkit.getTPS）；其他平台运行时不保证可用，
            // 且未来 TPS 概念可能发生变化。
            case "server.getTps"    -> {
                var tps = org.bukkit.Bukkit.getTPS();
                yield Map.of("tps1m", tps[0], "tps5m", tps[1], "tps15m", tps[2]);
            }
            case "server.getMaxPlayers" -> org.bukkit.Bukkit.getMaxPlayers();
            case "server.setMotd"   -> { org.bukkit.Bukkit.getServer().setMotd(TextUtil.toLegacy(TextUtil.parse(p.get("motd").getAsString()))); yield true; }
            // Material
            case "server.getMaterials" -> MaterialTasks.getMaterials(p);
            case "server.getBlocks"    -> MaterialTasks.getBlocks(p);
            case "server.getItems"     -> MaterialTasks.getItems(p);
            default -> throw new IllegalArgumentException("Unknown: " + taskType);
        };
    }
}
