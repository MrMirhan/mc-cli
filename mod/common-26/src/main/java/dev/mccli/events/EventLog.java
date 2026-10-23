package dev.mccli.events;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What happened in the game, in order: chat, titles, screens opened and closed, the tool's own commands, packet
 * counts, the player's position. A ring buffer the tool reads with a cursor, and while a video records, a sidecar
 * file {@code <video>.events.jsonl} with each event's time in the video and its frame.
 */
public final class EventLog {
    public enum Packets { OFF, SUMMARY, ALL }

    private static final Gson GSON = new Gson();
    private static final int CAPACITY = 5000;
    private static final Deque<JsonObject> ring = new ArrayDeque<>();
    private static final Map<String, Integer> packetCounts = new TreeMap<>();
    private static long seq;
    private static volatile Packets packets = Packets.OFF;
    private static BufferedWriter sidecar;
    private static long sidecarStartNanos;
    private static int sidecarFps;

    private EventLog() {}

    public static synchronized void add(String type, JsonObject data) {
        JsonObject e = data == null ? new JsonObject() : data.deepCopy();
        e.addProperty("seq", ++seq);
        e.addProperty("type", type);
        e.addProperty("at", System.currentTimeMillis());
        ring.addLast(e);
        while (ring.size() > CAPACITY) {
            ring.removeFirst();
        }
        if (sidecar != null) {
            long ms = (System.nanoTime() - sidecarStartNanos) / 1_000_000;
            JsonObject line = e.deepCopy();
            line.addProperty("t_ms", ms);
            line.addProperty("frame", ms * sidecarFps / 1000);
            try {
                sidecar.write(GSON.toJson(line));
                sidecar.newLine();
            } catch (IOException ignored) {
                // the video matters more than its log; the log stops here
                closeSidecar();
            }
        }
    }

    public static void add(String type, String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        add(type, o);
    }

    /** Events after {@code since}, oldest first, optionally only {@code types}, at most {@code limit}. */
    public static synchronized JsonObject read(long since, Set<String> types, int limit) {
        JsonArray out = new JsonArray();
        for (JsonObject e : ring) {
            if (e.get("seq").getAsLong() <= since) continue;
            if (types != null && !types.isEmpty() && !types.contains(e.get("type").getAsString())) continue;
            if (out.size() >= limit) break;
            out.add(e);
        }
        JsonObject o = new JsonObject();
        o.add("events", out);
        o.addProperty("next", out.isEmpty() ? Math.max(since, 0) : out.get(out.size() - 1).getAsJsonObject().get("seq").getAsLong());
        o.addProperty("latest", seq);
        o.addProperty("packets", packets.name().toLowerCase(java.util.Locale.ROOT));
        return o;
    }

    public static void packets(Packets mode) {
        packets = mode;
    }

    public static Packets packets() {
        return packets;
    }

    /** Netty thread: one packet arrived from the server. */
    public static void packet(String id) {
        switch (packets) {
            case OFF -> { }
            case ALL -> add("packet", "id", id);
            case SUMMARY -> {
                synchronized (EventLog.class) {
                    packetCounts.merge(id, 1, Integer::sum);
                }
            }
        }
    }

    /** About once a second: the packet counts since the last flush as one event. */
    public static void flushPacketCounts() {
        JsonObject counts = new JsonObject();
        synchronized (EventLog.class) {
            if (packetCounts.isEmpty()) return;
            packetCounts.forEach(counts::addProperty);
            packetCounts.clear();
        }
        JsonObject e = new JsonObject();
        e.add("counts", counts);
        add("packets", e);
    }

    public static synchronized void openSidecar(Path file, long startNanos, int fps) throws IOException {
        closeSidecar();
        sidecar = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        sidecarStartNanos = startNanos;
        sidecarFps = fps;
    }

    public static synchronized boolean sidecarOpen() {
        return sidecar != null;
    }

    public static synchronized void closeSidecar() {
        if (sidecar == null) return;
        try {
            sidecar.close();
        } catch (IOException ignored) {
            // nothing more to write
        }
        sidecar = null;
    }

    /** Tests only. */
    static synchronized void reset() {
        closeSidecar();
        ring.clear();
        packetCounts.clear();
        seq = 0;
        packets = Packets.OFF;
    }
}
