package dev.flipmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns an auction's decoded item NBT into a "comparable item" key, so that only like-for-like items
 * are priced against each other. Returns null when the item cannot be priced safely (stacks, mid-level
 * pets, multi-enchant books, no id); those listings are never sniped.
 *
 * Deliberately NOT part of the key: reforge, enchants on gear, gemstones, hot potato books. Items that
 * differ in those still share a key, so the reference price is the cheap end of the market.
 */
public final class ItemKey {
    private ItemKey() {}

    // Total XP needed to reach level 100 per pet rarity.
    private static final Map<String, Double> LVL100 = Map.of(
            "COMMON", 5_624_785d, "UNCOMMON", 8_644_220d, "RARE", 12_626_665d,
            "EPIC", 18_608_500d, "LEGENDARY", 25_353_230d, "MYTHIC", 25_353_230d);

    @SuppressWarnings("unchecked")
    public static String of(Map<String, Object> root, String tier) {
        if (root == null) return null;
        Object iObj = root.get("i");
        if (!(iObj instanceof List<?> items) || items.isEmpty() || !(items.get(0) instanceof Map<?, ?> first)) return null;

        Object count = ((Map<String, Object>) first).getOrDefault("Count", ((Map<String, Object>) first).get("count"));
        if (count instanceof Number n && n.intValue() > 1) return null;

        Object tagObj = ((Map<String, Object>) first).get("tag");
        if (!(tagObj instanceof Map<?, ?> tag)) return null;
        Object extraObj = ((Map<String, Object>) tag).get("ExtraAttributes");
        if (!(extraObj instanceof Map<?, ?>)) extraObj = ((Map<String, Object>) tag).get("custom_data");
        if (!(extraObj instanceof Map<?, ?>)) return null;
        Map<String, Object> extra = (Map<String, Object>) extraObj;

        Object idObj = extra.get("id");
        if (!(idObj instanceof String id) || id.isEmpty()) return null;
        String t = tier == null ? "?" : tier;

        if (id.equals("ENCHANTED_BOOK")) {
            Object en = extra.get("enchantments");
            if (en instanceof Map<?, ?> m && m.size() == 1) {
                Map.Entry<?, ?> e = m.entrySet().iterator().next();
                return "BOOK:" + e.getKey() + ":" + e.getValue();
            }
            return null;
        }

        if (id.equals("PET")) {
            Object pi = extra.get("petInfo");
            if (!(pi instanceof String s)) return null;
            try {
                JsonObject p = JsonParser.parseString(s).getAsJsonObject();
                String type = p.get("type").getAsString();
                String ptier = p.get("tier").getAsString();
                double exp = p.has("exp") ? p.get("exp").getAsDouble() : 0;
                String bucket;
                if (exp <= 0) bucket = "L1";
                else if (exp >= LVL100.getOrDefault(ptier, Double.MAX_VALUE)) bucket = "L100";
                else return null; // mid-level pets are priced by level; skip them
                StringBuilder sb = new StringBuilder("PET:").append(type).append(':').append(ptier).append(':').append(bucket);
                if (p.has("heldItem") && !p.get("heldItem").isJsonNull()) sb.append(":H=").append(p.get("heldItem").getAsString());
                if (p.has("skin") && !p.get("skin").isJsonNull()) sb.append(":S=").append(p.get("skin").getAsString());
                if (p.has("candyUsed") && p.get("candyUsed").getAsInt() > 0) sb.append(":candy");
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        StringBuilder sb = new StringBuilder(id).append('|').append(t);
        sb.append("|s").append(num(extra.get("upgrade_level"), num(extra.get("dungeon_item_level"), 0)));
        sb.append("|r").append(num(extra.get("rarity_upgrades"), 0));
        if (extra.get("skin") instanceof String skin) sb.append("|S=").append(skin);
        return sb.toString();
    }

    private static int num(Object o, int dflt) {
        return o instanceof Number n ? n.intValue() : dflt;
    }

    /** Unused helper kept for debugging: sorted view of a map's keys. */
    static Set<String> keys(Map<String, Object> m) {
        return new TreeMap<>(m).keySet();
    }
}
