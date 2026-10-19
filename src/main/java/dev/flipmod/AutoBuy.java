package dev.flipmod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-snipe state machine:
 *   /viewauction <uuid>  ->  "BIN Auction View" -> click "Buy Item Right Now"
 *                        ->  "Confirm Purchase" -> click "Confirm"
 * Slots are found by item name, not by index, so layout tweaks on the server do not break it.
 */
public class AutoBuy {
    private static final Pattern PURCHASED = Pattern.compile("You purchased (.+) for ([\\d,]+) coins!");
    private static final int TIMEOUT_TICKS = 60; // 3 seconds per stage

    private enum State { IDLE, WAIT_VIEW, WAIT_CONFIRM, FINISHING }

    private State state = State.IDLE;
    private int timer;
    private int clickDelay = -1;
    private Flip current;

    private final Deque<Long> buyTimes = new ArrayDeque<>();

    /** Purchases in the last rolling hour. */
    public int buysLastHour() {
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        while (!buyTimes.isEmpty() && buyTimes.peekFirst() < cutoff) buyTimes.pollFirst();
        return buyTimes.size();
    }

    public long bought;
    public long spent;
    public long expectedProfit;

    public boolean active() {
        return state != State.IDLE;
    }

    public String describe() {
        return state == State.IDLE ? "idle" : state + " (" + (current != null ? current.name() : "?") + ")";
    }

    public void reset() {
        state = State.IDLE;
        current = null;
        clickDelay = -1;
        timer = 0;
    }

    public void tick() {
        switch (state) {
            case IDLE -> startNext();
            case WAIT_VIEW -> {
                if (++timer > TIMEOUT_TICKS) { fail("timed out opening auction"); return; }
                AbstractContainerScreen<?> scr = Gui.container();
                if (scr == null || !Gui.titleHas(scr, "Auction View")) return;
                int slot = Gui.findSlot(scr, "Buy Item Right Now");
                if (slot < 0) return; // contents not loaded yet (or auction already sold; timeout handles it)
                if (clickDelay < 0) clickDelay = randomDelay();
                if (clickDelay > 0) { clickDelay--; return; }
                Gui.click(scr, slot);
                state = State.WAIT_CONFIRM;
                timer = 0;
            }
            case WAIT_CONFIRM -> {
                if (++timer > TIMEOUT_TICKS) { fail("timed out waiting for confirm screen"); return; }
                AbstractContainerScreen<?> scr = Gui.container();
                if (scr == null || !Gui.titleHas(scr, "Confirm Purchase")) return;
                int slot = Gui.findSlot(scr, "Confirm");
                if (slot < 0) return;
                Gui.click(scr, slot);
                state = State.FINISHING;
                timer = 0;
            }
            case FINISHING -> {
                // Normally onChat() sees the purchase message first; this is the fallback.
                if (++timer > 20) { Gui.closeScreen(); reset(); }
            }
        }
    }

    private void startNext() {
        FlipConfig cfg = FlipMod.config;
        Flip f;
        while ((f = FlipMod.queue.poll()) != null) {
            if (f.stale(cfg.staleFlipMs)) continue;
            if (f.cost() > cfg.maxPrice) continue;
            if (f.profit() >= 0 && f.profit() < cfg.minProfit) continue;
            if (buysLastHour() >= cfg.maxBuysPerHour) { f = null; break; }
            break;
        }
        if (f == null) return;

        current = f;
        FlipMod.markAttempted(f.uuid());
        clickDelay = -1;
        timer = 0;
        state = State.WAIT_VIEW;
        Gui.command("viewauction " + f.uuid());
    }

    private int randomDelay() {
        FlipConfig cfg = FlipMod.config;
        int lo = Math.max(0, Math.min(cfg.minDelayTicks, cfg.maxDelayTicks));
        int hi = Math.max(cfg.minDelayTicks, cfg.maxDelayTicks);
        return lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private void fail(String why) {
        FlipMod.log("buy failed: " + why);
        Gui.closeScreen();
        reset();
    }

    /** Called for every chat line. */
    public void onChat(String msg) {
        Matcher m = PURCHASED.matcher(msg);
        if (m.find()) {
            long price = Long.parseLong(m.group(2).replace(",", ""));
            bought++;
            spent += price;
            buyTimes.addLast(System.currentTimeMillis());
            if (current != null && current.profit() > 0) expectedProfit += current.profit();
            FlipMod.claim.notePurchase();
            if (active()) { Gui.closeScreen(); reset(); }
            return;
        }
        if (!active()) return;
        if (msg.contains("This auction wasn't found")
                || msg.contains("You cannot view this auction")
                || msg.contains("You don't have enough coins")
                || msg.contains("Your inventory is full")
                || msg.contains("There was an error with the auction house")
                || msg.contains("You didn't participate in this auction")) {
            fail("server said: " + msg);
        }
    }
}
