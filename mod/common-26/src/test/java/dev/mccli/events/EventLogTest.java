package dev.mccli.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {
    @BeforeEach
    void clean() {
        EventLog.reset();
    }

    @Test
    void readFollowsTheCursorAndFiltersTypes() {
        EventLog.add("chat", "text", "hi");
        EventLog.add("screen", "open", "ContainerScreen");
        EventLog.add("chat", "text", "bye");
        JsonObject all = EventLog.read(0, null, 100);
        assertEquals(3, all.getAsJsonArray("events").size());
        assertEquals(3, all.get("next").getAsLong());

        JsonObject chat = EventLog.read(1, Set.of("chat"), 100);
        assertEquals(1, chat.getAsJsonArray("events").size());
        assertEquals("bye", chat.getAsJsonArray("events").get(0).getAsJsonObject().get("text").getAsString());

        assertEquals(0, EventLog.read(3, null, 100).getAsJsonArray("events").size());
        assertEquals(3, EventLog.read(3, null, 100).get("next").getAsLong());
    }

    @Test
    void packetsAreOffThenCountedThenListed() {
        EventLog.packet("minecraft:set_time");
        EventLog.flushPacketCounts();
        assertEquals(0, EventLog.read(0, null, 10).getAsJsonArray("events").size());

        EventLog.packets(EventLog.Packets.SUMMARY);
        EventLog.packet("minecraft:set_time");
        EventLog.packet("minecraft:set_time");
        EventLog.packet("minecraft:system_chat");
        EventLog.flushPacketCounts();
        JsonObject e = EventLog.read(0, null, 10).getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals("packets", e.get("type").getAsString());
        assertEquals(2, e.getAsJsonObject("counts").get("minecraft:set_time").getAsInt());

        EventLog.packets(EventLog.Packets.ALL);
        EventLog.packet("minecraft:open_screen");
        assertEquals(1, EventLog.read(1, Set.of("packet"), 10).getAsJsonArray("events").size());
    }

    @Test
    void sidecarLinesCarryVideoTimeAndFrame(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("clip.mp4.events.jsonl");
        EventLog.openSidecar(file, System.nanoTime() - 2_000_000_000L, 30);
        EventLog.add("chat", "text", "go");
        EventLog.closeSidecar();
        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        JsonObject line = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertTrue(line.get("t_ms").getAsLong() >= 2000);
        assertTrue(line.get("frame").getAsLong() >= 60);
        assertFalse(EventLog.sidecarOpen());
    }
}
