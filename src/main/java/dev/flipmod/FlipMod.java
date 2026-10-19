package dev.flipmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlipMod implements ClientModInitializer {
    public static FlipConfig config;
    public static CoflSocket socket;
    public static AuctionFeed feed;
    public static final Deque<Flip> queue = new ArrayDeque<>();
    public static final AutoBuy buy = new AutoBuy();
    public static final AutoClaim claim = new AutoClaim();

    /** Set from commands; the screen is opened on the next tick (after the chat screen closes). */
    static volatile boolean openGui;
    private static volatile boolean onHypixel;
    /** Auctions we already tried recently, so two sources do not make us buy twice. */
    private static final Map<String, Long> attempted = new HashMap<>();

    @Override
    public void onInitializeClient() {
        config = FlipConfig.load();
        socket = new CoflSocket();
        feed = new AuctionFeed(FlipMod::rules, flips -> runOnMain(() -> flips.forEach(FlipMod::onFlip)));

        ClientTickEvents.END_CLIENT_TICK.register(FlipMod::tick);

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            buy.onChat(message.getString());
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onHypixel = Gui.onHypixel();
            applySources();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            onHypixel = false;
            socket.close();
            feed.stop();
            queue.clear();
            buy.reset();
            claim.reset();
        });

        ClientCommandRegistrationCallback.EVENT.register(Commands::register);
        log("loaded");
    }

    /** Start or stop the two flip sources to match the config. */
    public static void applySources() {
        if (!onHypixel || !config.enabled) {
            socket.close();
            feed.stop();
            return;
        }
        if (config.useCofl) { if (!socket.isConnected()) socket.connect(); } else socket.close();
        if (config.useIndex) feed.start(); else feed.stop();
    }

    static PriceIndex.Rules rules() {
        return new PriceIndex.Rules(config.minSamples, config.minProfit, config.undervaluePct, config.maxPrice, List.copyOf(config.ignored));
    }

    /** Called on the main thread for each flip from either source. */
    static void onFlip(Flip f) {
        if (!config.enabled) return;
        if (isIgnored(f.name())) return;

        boolean show = config.chatFlips && (f.profit() < 0 || f.profit() >= config.alertMinProfit);
        if (show && !f.text().isEmpty()) Gui.say(f.text());

        if (!config.autoBuy) return;
        if (f.cost() > config.maxPrice) return;
        if (f.profit() >= 0 && f.profit() < config.minProfit) return;
        long now = System.currentTimeMillis();
        attempted.values().removeIf(t -> now - t > 300_000);
        if (attempted.containsKey(f.uuid())) return;
        for (Flip q : queue) if (q.uuid().equals(f.uuid())) return;
        if (buy.buysLastHour() >= config.maxBuysPerHour) return;
        if (queue.size() >= config.queueMax) queue.pollFirst();
        queue.addLast(f);
    }

    static void markAttempted(String uuid) {
        attempted.put(uuid, System.currentTimeMillis());
    }

    static boolean isIgnored(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        for (String ig : config.ignored) if (!ig.isBlank() && n.contains(ig.toLowerCase())) return true;
        return false;
    }

    private static void tick(Minecraft mc) {
        if (openGui) {
            openGui = false;
            mc.setScreen(new SettingsScreen());
            return;
        }
        if (mc.player == null || mc.level == null) return;
        if (!config.enabled || !onHypixel) return;

        // Do not hijack the screen while the player is in chat, the settings screen, the pause menu, etc.
        boolean userScreenOpen = mc.screen != null && !(mc.screen instanceof AbstractContainerScreen<?>);

        if (claim.active()) {
            claim.tick();
        } else if (buy.active()) {
            buy.tick();
        } else if (!userScreenOpen) {
            if (config.autoBuy && !queue.isEmpty()) buy.tick();
            else if (config.autoClaim) claim.tick();
        }
    }

    static void runOnMain(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    static void log(String s) {
        System.out.println("[flipmod] " + s);
    }
}
