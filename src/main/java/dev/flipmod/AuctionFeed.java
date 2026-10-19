package dev.flipmod;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps the PriceIndex fresh from Hypixel's public auction API (no key needed).
 * Polls page 0 cheaply; when its lastUpdated changes, downloads all pages and rebuilds the snapshot.
 * Hypixel refreshes this endpoint about once a minute, so this is slower than Coflnet's feed.
 */
public final class AuctionFeed {
    private static final String URL = "https://api.hypixel.net/v2/skyblock/auctions?page=";
    private static final String NO_KEY = "\u0000";

    public final PriceIndex index = new PriceIndex();
    public volatile String status = "not started";
    public volatile long lastApiUpdate;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "flipmod-feed"));
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> daemon(r, "flipmod-fetch"));
    private final Map<String, String> keyCache = new ConcurrentHashMap<>();
    private final Supplier<PriceIndex.Rules> rules;
    private final Consumer<List<Flip>> sink;
    private volatile boolean running;
    private long backoffUntil;

    public AuctionFeed(Supplier<PriceIndex.Rules> rules, Consumer<List<Flip>> sink) {
        this.rules = rules;
        this.sink = sink;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void start() {
        if (running) return;
        running = true;
        status = "starting";
        timer.scheduleWithFixedDelay(this::safePoll, 0, 10, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        status = "stopped";
    }

    private void safePoll() {
        if (!running) return;
        try {
            poll();
        } catch (Exception e) {
            status = "error: " + e.getMessage();
            FlipMod.log("feed error: " + e);
        }
    }

    private void poll() throws Exception {
        if (System.currentTimeMillis() < backoffUntil) return;

        List<PriceIndex.Listing> all = new ArrayList<>();
        PageInfo first = fetchPage(0, all);
        if (first == null) return;
        if (first.lastUpdated == lastApiUpdate && index.ready()) {
            return; // nothing new yet
        }

        List<Callable<List<PriceIndex.Listing>>> jobs = new ArrayList<>();
        for (int p = 1; p < first.totalPages; p++) {
            final int page = p;
            jobs.add(() -> {
                List<PriceIndex.Listing> l = new ArrayList<>();
                PageInfo pi = fetchPage(page, l);
                return pi == null ? null : l;
            });
        }
        for (Future<List<PriceIndex.Listing>> f : pool.invokeAll(jobs)) {
            List<PriceIndex.Listing> l = f.get();
            if (l == null) { status = "incomplete fetch, retrying"; return; }
            all.addAll(l);
        }

        // Drop cache entries for auctions that no longer exist.
        Set<String> live = new HashSet<>();
        for (PriceIndex.Listing l : all) live.add(l.uuid());
        keyCache.keySet().retainAll(live);

        lastApiUpdate = first.lastUpdated;
        long now = System.currentTimeMillis();
        List<Flip> flips = index.update(all, rules.get(), now);
        status = "ok";
        if (!flips.isEmpty()) sink.accept(flips);
    }

    public record PageInfo(int totalPages, long lastUpdated) {}

    private PageInfo fetchPage(int page, List<PriceIndex.Listing> out) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL + page))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "flipmod/0.2")
                .GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() == 429) {
                backoffUntil = System.currentTimeMillis() + 60_000;
                status = "rate limited, waiting 60s";
                return null;
            }
            if (res.statusCode() != 200) {
                status = "http " + res.statusCode();
                return null;
            }
            return parsePage(in, out, System.currentTimeMillis(), keyCache);
        }
    }

    /** Parses one page of the auctions API. Static and Minecraft-free so it can be unit tested. */
    public static PageInfo parsePage(InputStream in, List<PriceIndex.Listing> out, long nowMs, Map<String, String> keyCache) throws IOException {
        int totalPages = 1;
        long lastUpdated = 0;
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                switch (name) {
                    case "totalPages" -> totalPages = r.nextInt();
                    case "lastUpdated" -> lastUpdated = r.nextLong();
                    case "auctions" -> {
                        r.beginArray();
                        while (r.hasNext()) readAuction(r, out, nowMs, keyCache);
                        r.endArray();
                    }
                    default -> r.skipValue();
                }
            }
        }
        return new PageInfo(totalPages, lastUpdated);
    }

    private static void readAuction(JsonReader r, List<PriceIndex.Listing> out, long nowMs, Map<String, String> keyCache) throws IOException {
        String uuid = null, itemName = "?", tier = null, bytes = null;
        long bid = 0, end = 0;
        boolean bin = false, claimed = false;
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue; }
            switch (n) {
                case "uuid" -> uuid = r.nextString();
                case "item_name" -> itemName = r.nextString();
                case "tier" -> tier = r.nextString();
                case "starting_bid" -> bid = r.nextLong();
                case "bin" -> bin = r.nextBoolean();
                case "claimed" -> claimed = r.nextBoolean();
                case "end" -> end = r.nextLong();
                case "item_bytes" -> bytes = r.peek() == JsonToken.STRING ? r.nextString() : skipAndNull(r);
                default -> r.skipValue();
            }
        }
        r.endObject();

        if (uuid == null || !bin || claimed || end <= nowMs || bid <= 0) return;
        String key = keyCache.get(uuid);
        if (key == null) {
            key = NO_KEY;
            if (bytes != null) {
                try {
                    String k = ItemKey.of(Nbt.readGzip(Base64.getDecoder().decode(bytes)), tier);
                    if (k != null) key = k;
                } catch (Exception ignored) {
                    // undecodable item: leave unpriced
                }
            }
            keyCache.put(uuid, key);
        }
        String display = itemName;
        out.add(new PriceIndex.Listing(uuid, display, key.equals(NO_KEY) ? null : key, bid));
    }

    private static String skipAndNull(JsonReader r) throws IOException {
        r.skipValue();
        return null;
    }
}
