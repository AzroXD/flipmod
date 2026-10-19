package dev.flipmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class FlipConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("flipmod.json");

    /** Master switch. */
    public boolean enabled = true;
    /** Automatically buy flips. OFF until you turn it on. */
    public boolean autoBuy = false;
    /** Automatically collect purchased items from the auction house. */
    public boolean autoClaim = true;

    // ---- flip sources ----
    /** Flips from the Coflnet socket. */
    public boolean useCofl = true;
    /** Flips from the local price database built from Hypixel's auction API. */
    public boolean useIndex = true;

    // ---- sniper rules (local database) ----
    /** Buy only if price is at least this % below the reference (2nd-lowest BIN). */
    public int undervaluePct = 22;
    /** Need at least this many listings of the same item before trusting the reference price. */
    public int minSamples = 6;
    /** Items whose name or key contains any of these are never bought. */
    public java.util.List<String> ignored = new java.util.ArrayList<>();

    // ---- buying limits ----
    /** Never buy anything above this price (coins). */
    public long maxPrice = 20_000_000L;
    /** Skip flips whose known profit is below this (coins). Ignored when profit is unknown. */
    public long minProfit = 500_000L;
    /** Hard cap on purchases per rolling hour. */
    public int maxBuysPerHour = 30;

    /** Random click delay in client ticks (1 tick = 50 ms) between min and max. */
    public int minDelayTicks = 2;
    public int maxDelayTicks = 4;

    // ---- alerts / display ----
    /** Print flips in chat. */
    public boolean chatFlips = true;
    /** Only print flips with at least this profit. */
    public long alertMinProfit = 250_000L;
    /** Show Coflnet's own chat messages. */
    public boolean showCoflChat = true;

    /** Wait this long after a purchase before opening /ah to claim. */
    public int claimDelaySeconds = 4;
    /** Drop flips older than this when they reach the front of the queue. */
    public int staleFlipMs = 8000;
    /** Max flips kept in the queue. */
    public int queueMax = 20;

    /** Coflnet session id. Keep it private; it links to your Coflnet settings. */
    public String sid = UUID.randomUUID().toString().replace("-", "");

    public static FlipConfig load() {
        try {
            if (Files.exists(PATH)) {
                FlipConfig c = GSON.fromJson(Files.readString(PATH), FlipConfig.class);
                if (c != null) {
                    if (c.sid == null || c.sid.isBlank()) c.sid = UUID.randomUUID().toString().replace("-", "");
                    return c;
                }
            }
        } catch (Exception e) {
            System.err.println("[flipmod] could not read config: " + e);
        }
        FlipConfig c = new FlipConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[flipmod] could not save config: " + e);
        }
    }
}
