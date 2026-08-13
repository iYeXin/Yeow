package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.*;
import java.util.*;

public class RecipeTasks {

    static NamespacedKey key(String k) {
        if (k.contains(":")) { var parts = k.split(":", 2); return new NamespacedKey(parts[0], parts[1]); }
        return new NamespacedKey("yeow", k);
    }

    public static Object addRecipe(JsonObject p) {
        try {
            var rtype = p.get("type").getAsString();
            var nk = key(p.get("key").getAsString());

            return switch (rtype) {
                case "shaped" -> addShaped(nk, p);
                case "shapeless" -> addShapeless(nk, p);
                case "furnace" -> { Bukkit.getServer().addRecipe(addFurnace(nk, "furnace", p)); yield true; }
                case "blast" -> { Bukkit.getServer().addRecipe(addFurnace(nk, "blasting", p)); yield true; }
                case "smoker" -> { Bukkit.getServer().addRecipe(addFurnace(nk, "smoking", p)); yield true; }
                case "campfire" -> { Bukkit.getServer().addRecipe(addCampfire(nk, p)); yield true; }
                default -> false;
            };
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[Recipe] Failed to add recipe: " + e.getMessage());
            return Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    static boolean addShaped(NamespacedKey nk, JsonObject p) {
        var result = InventoryTasks.buildItem(p.getAsJsonObject("result"));
        var shape = new ArrayList<String>();
        for (var el : p.getAsJsonArray("shape")) shape.add(el.getAsString());
        var recipe = new ShapedRecipe(nk, result);
        recipe.shape(shape.toArray(String[]::new));
        var ings = p.getAsJsonObject("ingredients");
        for (var k : ings.keySet()) {
            var mat = Material.matchMaterial(ings.get(k).getAsString());
            if (mat != null) recipe.setIngredient(k.charAt(0), mat);
        }
        if (p.has("group")) recipe.setGroup(p.get("group").getAsString());
        Bukkit.getServer().addRecipe(recipe);
        return true;
    }

    static boolean addShapeless(NamespacedKey nk, JsonObject p) {
        var result = InventoryTasks.buildItem(p.getAsJsonObject("result"));
        var recipe = new ShapelessRecipe(nk, result);
        var ings = p.getAsJsonArray("ingredients");
        for (var el : ings) {
            if (el.isJsonPrimitive()) {
                var mat = Material.matchMaterial(el.getAsString());
                if (mat != null) recipe.addIngredient(mat);
            } else if (el.isJsonObject()) {
                var obj = el.getAsJsonObject();
                var mat = Material.matchMaterial(obj.get("type").getAsString());
                var amt = obj.has("amount") ? obj.get("amount").getAsInt() : 1;
                if (mat != null) recipe.addIngredient(amt, mat);
            }
        }
        if (p.has("group")) recipe.setGroup(p.get("group").getAsString());
        Bukkit.getServer().addRecipe(recipe);
        return true;
    }

    static Recipe addFurnace(NamespacedKey nk, String type, JsonObject p) {
        var input = Material.matchMaterial(p.get("input").getAsString());
        var result = InventoryTasks.buildItem(p.getAsJsonObject("result"));
        var exp = p.has("experience") ? (float)p.get("experience").getAsDouble() : 0.0f;
        var time = p.has("cookingTime") ? p.get("cookingTime").getAsInt() : 200;
        var recipe = new FurnaceRecipe(nk, result, input, exp, time);
        recipe.setGroup(type);
        return recipe;
    }

    static Recipe addCampfire(NamespacedKey nk, JsonObject p) {
        var input = Material.matchMaterial(p.get("input").getAsString());
        var result = InventoryTasks.buildItem(p.getAsJsonObject("result"));
        var exp = p.has("experience") ? (float)p.get("experience").getAsDouble() : 0.0f;
        var time = p.has("cookingTime") ? p.get("cookingTime").getAsInt() : 600;
        return new CampfireRecipe(nk, result, input, exp, time);
    }

    public static Object removeRecipe(JsonObject p) {
        Bukkit.removeRecipe(key(p.get("key").getAsString()));
        return true;
    }

    public static Object getRecipesFor(JsonObject p) {
        var item = InventoryTasks.buildItem(p.getAsJsonObject("item"));
        var results = new ArrayList<String>();
        var recipes = Bukkit.getRecipesFor(item);
        for (int i = 0; i < recipes.size(); i++) {
            var recipe = recipes.get(i);
            results.add(((org.bukkit.Keyed)recipe).getKey().toString());
        }
        return results;
    }
}
