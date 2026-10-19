package dev.flipmod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory BIN price database. Each refresh gets every active BIN listing; new listings are compared
 * with the PREVIOUS snapshot of their item key. Reference price = second-lowest BIN (so a single
 * mispriced or manipulated listing cannot set the price).
 */
public final class PriceIndex {

    public record Listing(String uuid, String name, String key, long price) {}

    /** Snipe rules, copied from the config so this class has no Minecraft dependency. */
    public record Rules(int minSamples, long minProfit, double undervaluePct, long maxPrice, List<String> ignored) {}

    private volatile Map<String, long[]> prices = Map.of();
    private final Set<String> seen = new HashSet<>();
    private boolean baselined;

    public volatile int binCount;
    public volatile int keyCount;
    public volatile long updatedAtMs;

    /** Auction House tax approximation: listing fee by price tier + 1% claim fee over 1m (capped at 1m). */
    public static long netProfit(long cost, long sell) {
        double listFee = sell < 10_000_000 ? 0.01 : sell < 100_000_000 ? 0.02 : 0.025;
        double claim = sell >= 1_000_000 ? Math.min(0.01 * sell, 1_000_000) : 0;
        return (long) (sell - sell * listFee - claim - cost);
    }

    public boolean ready() {
        return baselined;
    }

    /** Reference price for a key, or -1. */
    public long reference(String key) {
        long[] a = prices.get(key);
        return (a == null || a.length < 2) ? -1 : a[1];
    }

    /**
     * Feed a full snapshot of active BIN listings. Returns snipe candidates among listings that were not
     * in the previous snapshot (empty on the first snapshot, which only builds the baseline).
     */
    public synchronized List<Flip> update(List<Listing> current, Rules rules, long nowMs) {
        List<Flip> out = new ArrayList<>();
        Map<String, long[]> old = prices;

        if (baselined) {
            for (Listing l : current) {
                if (l.key() == null || seen.contains(l.uuid())) continue;
                Flip f = evaluate(l, old, rules, nowMs);
                if (f != null) out.add(f);
            }
        }

        // Build the new snapshot.
        Map<String, List<Long>> tmp = new HashMap<>();
        seen.clear();
        for (Listing l : current) {
            seen.add(l.uuid());
            if (l.key() != null) tmp.computeIfAbsent(l.key(), k -> new ArrayList<>()).add(l.price());
        }
        Map<String, long[]> next = new HashMap<>(tmp.size() * 2);
        for (Map.Entry<String, List<Long>> e : tmp.entrySet()) {
            long[] a = e.getValue().stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(a);
            next.put(e.getKey(), a);
        }
        prices = next;
        binCount = current.size();
        keyCount = next.size();
        updatedAtMs = nowMs;
        baselined = true;

        out.sort((a, b) -> Long.compare(b.profit(), a.profit()));
        return out;
    }

    private static Flip evaluate(Listing l, Map<String, long[]> old, Rules r, long nowMs) {
        if (l.price() > r.maxPrice()) return null;
        String lname = l.name().toLowerCase(Locale.ROOT);
        for (String ig : r.ignored()) {
            if (!ig.isBlank() && (lname.contains(ig.toLowerCase(Locale.ROOT)) || l.key().toLowerCase(Locale.ROOT).contains(ig.toLowerCase(Locale.ROOT)))) return null;
        }
        long[] arr = old.get(l.key());
        if (arr == null || arr.length < Math.max(2, r.minSamples())) return null;
        long ref = arr[1];
        if (l.price() > ref * (1.0 - r.undervaluePct() / 100.0)) return null;
        long profit = netProfit(l.price(), ref);
        if (profit < r.minProfit()) return null;
        String text = "§6[Snipe] §f" + l.name() + " §7cost §e" + Fmt.coins(l.price())
                + " §7ref §b" + Fmt.coins(ref) + " §7profit §a" + Fmt.coins(profit)
                + " §8(" + arr.length + " listed)";
        return new Flip(l.uuid(), l.name(), l.price(), ref, text, nowMs);
    }
}
