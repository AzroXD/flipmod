package dev.flipmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

public final class Commands {
    private Commands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d, CommandBuildContext ctx) {
        // /cofl and /cl forward everything to Coflnet, like the official mod (e.g. /cofl s minProfit 1m)
        for (String root : new String[] {"cofl", "cl"}) {
            LiteralArgumentBuilder<FabricClientCommandSource> b = ClientCommandManager.literal(root);
            b.then(ClientCommandManager.argument("args", StringArgumentType.greedyString()).executes(c -> {
                FlipMod.socket.sendCommand(StringArgumentType.getString(c, "args"));
                return 1;
            }));
            d.register(b);
        }

        LiteralArgumentBuilder<FabricClientCommandSource> f = ClientCommandManager.literal("flipmod");
        f.executes(c -> { FlipMod.openGui = true; return 1; });
        f.then(ClientCommandManager.literal("gui").executes(c -> { FlipMod.openGui = true; return 1; }));
        f.then(ClientCommandManager.literal("toggle").executes(c -> {
            FlipMod.config.enabled = !FlipMod.config.enabled;
            FlipMod.config.save();
            FlipMod.applySources();
            reply(c.getSource(), "Mod " + onOff(FlipMod.config.enabled));
            return 1;
        }));
        f.then(ClientCommandManager.literal("snipe").executes(c -> {
            FlipMod.config.autoBuy = !FlipMod.config.autoBuy;
            FlipMod.config.save();
            reply(c.getSource(), "Auto-snipe " + onOff(FlipMod.config.autoBuy));
            return 1;
        }));
        f.then(ClientCommandManager.literal("claim").executes(c -> {
            FlipMod.config.autoClaim = !FlipMod.config.autoClaim;
            FlipMod.config.save();
            reply(c.getSource(), "Auto-claim " + onOff(FlipMod.config.autoClaim));
            return 1;
        }));
        f.then(ClientCommandManager.literal("claimnow").executes(c -> {
            FlipMod.claim.claimNow();
            reply(c.getSource(), "Claiming now");
            return 1;
        }));
        f.then(ClientCommandManager.literal("stop").executes(c -> {
            FlipMod.queue.clear();
            FlipMod.buy.reset();
            FlipMod.claim.reset();
            reply(c.getSource(), "Cleared queue and stopped current action");
            return 1;
        }));
        f.then(ClientCommandManager.literal("maxprice")
                .then(ClientCommandManager.argument("coins", StringArgumentType.word()).executes(c -> {
                    long v = parseCoins(StringArgumentType.getString(c, "coins"));
                    if (v < 0) { reply(c.getSource(), "Could not read that number (try 50m, 1.5b, 250k)"); return 0; }
                    FlipMod.config.maxPrice = v;
                    FlipMod.config.save();
                    reply(c.getSource(), "Max price set to " + fmt(v));
                    return 1;
                })));
        f.then(ClientCommandManager.literal("minprofit")
                .then(ClientCommandManager.argument("coins", StringArgumentType.word()).executes(c -> {
                    long v = parseCoins(StringArgumentType.getString(c, "coins"));
                    if (v < 0) { reply(c.getSource(), "Could not read that number"); return 0; }
                    FlipMod.config.minProfit = v;
                    FlipMod.config.save();
                    reply(c.getSource(), "Min profit set to " + fmt(v));
                    return 1;
                })));
        f.then(ClientCommandManager.literal("delay")
                .then(ClientCommandManager.argument("range", StringArgumentType.greedyString()).executes(c -> {
                    String[] p = StringArgumentType.getString(c, "range").trim().split("\\s+");
                    try {
                        int lo = Integer.parseInt(p[0]);
                        int hi = p.length > 1 ? Integer.parseInt(p[1]) : lo;
                        FlipMod.config.minDelayTicks = Math.max(0, Math.min(lo, hi));
                        FlipMod.config.maxDelayTicks = Math.max(lo, hi);
                        FlipMod.config.save();
                        reply(c.getSource(), "Click delay " + FlipMod.config.minDelayTicks + "-" + FlipMod.config.maxDelayTicks + " ticks");
                        return 1;
                    } catch (NumberFormatException e) {
                        reply(c.getSource(), "Usage: /flipmod delay <minTicks> [maxTicks]");
                        return 0;
                    }
                })));
        f.then(ClientCommandManager.literal("debug").executes(c -> {
            FlipMod.socket.debug = !FlipMod.socket.debug;
            reply(c.getSource(), "Raw socket debug " + onOff(FlipMod.socket.debug));
            return 1;
        }));
        f.then(ClientCommandManager.literal("reconnect").executes(c -> {
            FlipMod.socket.close();
            FlipMod.socket.connect();
            reply(c.getSource(), "Reconnecting to Coflnet");
            return 1;
        }));
        f.then(ClientCommandManager.literal("status").executes(c -> {
            FlipConfig cfg = FlipMod.config;
            reply(c.getSource(),
                    "Socket: " + (FlipMod.socket.isConnected() ? "connected" : "disconnected")
                            + " | mod " + onOff(cfg.enabled) + ", snipe " + onOff(cfg.autoBuy) + ", claim " + onOff(cfg.autoClaim));
            reply(c.getSource(),
                    "Index: " + FlipMod.feed.status + ", " + FlipMod.feed.index.binCount + " BINs / " + FlipMod.feed.index.keyCount + " items"
                            + " | Buy: " + FlipMod.buy.describe() + " | Claim: " + FlipMod.claim.describe()
                            + " | queue " + FlipMod.queue.size() + " | unclaimed " + FlipMod.claim.pending());
            reply(c.getSource(),
                    "Session: bought " + FlipMod.buy.bought + ", spent " + fmt(FlipMod.buy.spent)
                            + ", expected profit " + fmt(FlipMod.buy.expectedProfit)
                            + " | max price " + fmt(cfg.maxPrice) + ", min profit " + fmt(cfg.minProfit));
            return 1;
        }));
        d.register(f);
    }

    private static void reply(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal("§b[FlipMod] §f" + msg));
    }

    private static String onOff(boolean b) {
        return b ? "§aON§f" : "§cOFF§f";
    }

    /** Accepts 1500, 250k, 50m, 1.5b. Returns -1 if unreadable. */
    static long parseCoins(String s) {
        try {
            s = s.trim().toLowerCase().replace(",", "");
            double mult = 1;
            if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
            return (long) (Double.parseDouble(s) * mult);
        } catch (Exception e) {
            return -1;
        }
    }

    static String fmt(long v) {
        return Fmt.coins(v);
    }
}
