package dev.flipmod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for the Coflnet "modsocket". Protocol (as used by the official mod): JSON envelopes
 * {"type": "...", "data": "..."} where data is usually itself a JSON string.
 * The exact field names below are written from the public protocol and should be checked against
 * live traffic (use /flipmod debug) if flips do not show up.
 */
public class CoflSocket implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final Pattern VIEW_AUCTION = Pattern.compile("/viewauction\\s+([0-9a-fA-F-]{32,36})");
    private static final String VERSION = "flipmod-0.1.0";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "flipmod-socket");
        t.setDaemon(true);
        return t;
    });
    private final StringBuilder buffer = new StringBuilder();
    private volatile WebSocket ws;
    private volatile boolean wantConnected;
    public volatile boolean debug;

    public boolean isConnected() {
        return ws != null && !ws.isOutputClosed();
    }

    public void connect() {
        wantConnected = true;
        open();
    }

    public void close() {
        wantConnected = false;
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        ws = null;
    }

    private void open() {
        Minecraft mc = Minecraft.getInstance();
        String player = mc.getUser().getName();
        String url = "wss://sky.coflnet.com/modsocket"
                + "?version=" + enc(VERSION)
                + "&player=" + enc(player)
                + "&SId=" + enc(FlipMod.config.sid);
        http.newWebSocketBuilder().buildAsync(URI.create(url), this).whenComplete((w, err) -> {
            if (err != null) {
                FlipMod.log("socket connect failed: " + err.getMessage());
                scheduleReconnect();
            } else {
                ws = w;
            }
        });
    }

    private void scheduleReconnect() {
        if (!wantConnected) return;
        timer.schedule(() -> {
            if (wantConnected) open();
        }, 5, TimeUnit.SECONDS);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Send a "/cofl <args>" style command: first word is the type, the rest is the data string. */
    public void sendCommand(String args) {
        String[] parts = args.trim().split("\\s+", 2);
        String type = parts[0];
        String rest = parts.length > 1 ? parts[1] : "";
        send(type, GSON.toJson(rest));
    }

    public void send(String type, String dataJsonString) {
        WebSocket w = ws;
        if (w == null) {
            FlipMod.runOnMain(() -> Gui.say("§c[FlipMod] Not connected to Coflnet yet."));
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("data", dataJsonString);
        w.sendText(o.toString(), true);
    }

    // ---- WebSocket.Listener ----

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
        FlipMod.log("connected to Coflnet");
        FlipMod.runOnMain(() -> Gui.say("§a[FlipMod] Connected to Coflnet."));
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String msg = buffer.toString();
            buffer.setLength(0);
            try {
                handle(msg);
            } catch (Exception e) {
                FlipMod.log("bad message: " + e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        ws = null;
        FlipMod.log("socket closed: " + statusCode + " " + reason);
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        ws = null;
        FlipMod.log("socket error: " + error);
        scheduleReconnect();
    }

    // ---- message handling ----

    private void handle(String raw) {
        if (debug) FlipMod.runOnMain(() -> Gui.say("§8[raw] " + (raw.length() > 300 ? raw.substring(0, 300) + "..." : raw)));
        JsonObject env = JsonParser.parseString(raw).getAsJsonObject();
        String type = env.has("type") ? env.get("type").getAsString() : "";
        JsonElement data = unwrap(env.get("data"));

        switch (type) {
            case "flip" -> {
                Flip f = parseFlip(data);
                if (f != null) FlipMod.runOnMain(() -> FlipMod.onFlip(f));
            }
            case "chatMessage", "writeToChat" -> {
                String text = joinText(data);
                if (!text.isEmpty() && FlipMod.config.showCoflChat) FlipMod.runOnMain(() -> Gui.say(text));
            }
            case "execute" -> {
                // The server may ask the client to run a command. Only allow harmless auction ones.
                String cmd = data != null && data.isJsonPrimitive() ? data.getAsString() : "";
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.startsWith("viewauction ") || cmd.equals("ah")) {
                    String c = cmd;
                    FlipMod.runOnMain(() -> Gui.command(c));
                }
            }
            default -> { /* ignore other message types */ }
        }
    }

    /** Data is often a JSON string that itself contains JSON. */
    private static JsonElement unwrap(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            try {
                return JsonParser.parseString(s);
            } catch (Exception ex) {
                return e;
            }
        }
        return e;
    }

    private static String joinText(JsonElement data) {
        if (data == null) return "";
        if (data.isJsonPrimitive()) return data.getAsString();
        StringBuilder sb = new StringBuilder();
        if (data.isJsonArray()) {
            for (JsonElement el : data.getAsJsonArray()) {
                if (el.isJsonObject() && el.getAsJsonObject().has("text")) sb.append(el.getAsJsonObject().get("text").getAsString());
                else if (el.isJsonPrimitive()) sb.append(el.getAsString());
            }
        } else if (data.isJsonObject()) {
            JsonObject o = data.getAsJsonObject();
            if (o.has("text")) sb.append(o.get("text").getAsString());
        }
        return sb.toString();
    }

    private static Flip parseFlip(JsonElement data) {
        if (data == null || !data.isJsonObject()) return null;
        JsonObject o = data.getAsJsonObject();

        String uuid = null;
        String name = "?";
        long cost = -1;
        long target = -1;

        if (o.has("id") && o.get("id").isJsonPrimitive()) uuid = o.get("id").getAsString();
        if (o.has("target") && o.get("target").isJsonPrimitive()) target = o.get("target").getAsLong();

        if (o.has("auction") && o.get("auction").isJsonObject()) {
            JsonObject a = o.getAsJsonObject("auction");
            if (a.has("uuid")) uuid = a.get("uuid").getAsString();
            if (a.has("itemName")) name = a.get("itemName").getAsString();
            if (a.has("startingBid")) cost = a.get("startingBid").getAsLong();
            if (target < 0 && a.has("target")) target = a.get("target").getAsLong();
        }

        String text = "";
        if (o.has("messages") && o.get("messages").isJsonArray()) {
            JsonArray msgs = o.getAsJsonArray("messages");
            StringBuilder sb = new StringBuilder();
            for (JsonElement m : msgs) {
                if (!m.isJsonObject()) continue;
                JsonObject mo = m.getAsJsonObject();
                if (mo.has("text")) sb.append(mo.get("text").getAsString());
                // Fallback for finding the auction id from the click action
                if (uuid == null && mo.has("onClick")) {
                    Matcher mt = VIEW_AUCTION.matcher(mo.get("onClick").getAsString());
                    if (mt.find()) uuid = mt.group(1);
                }
            }
            text = sb.toString();
        }

        if (uuid == null) return null;
        return new Flip(uuid.replace("-", ""), name, cost, target, text, System.currentTimeMillis());
    }
}
