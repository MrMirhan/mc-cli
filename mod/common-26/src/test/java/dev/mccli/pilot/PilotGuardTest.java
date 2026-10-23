package dev.mccli.pilot;

import com.google.gson.JsonParser;
import dev.mccli.control.AccessClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PilotGuardTest {
    @Test
    void singleplayerAndListedServersOnly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mccli.json"), "{\"remoteControl\":\"on\",\"pilotServers\":[\"127.0.0.1:25566\",\"test.example.net\"]}");
        Set<String> listed = PilotGuard.listed(dir);
        assertNull(PilotGuard.refusal(true, null, listed));
        assertNull(PilotGuard.refusal(false, "127.0.0.1:25566", listed));
        assertNull(PilotGuard.refusal(false, "TEST.example.net", listed));
        assertNotNull(PilotGuard.refusal(false, "play.example.net", listed));
        assertNotNull(PilotGuard.refusal(false, "127.0.0.1:25565", listed));
        assertNotNull(PilotGuard.refusal(false, null, listed));
    }

    @Test
    void noConfigListsNothing(@TempDir Path dir) {
        assertTrue(PilotGuard.listed(dir).isEmpty());
        assertNotNull(PilotGuard.refusal(false, "localhost", PilotGuard.listed(dir)));
    }

    @Test
    void runningThePilotNeedsWorldAccess() {
        assertEquals(AccessClass.WORLD, AccessClass.of("pilot", JsonParser.parseString("{\"action\":\"run\"}").getAsJsonObject()));
        assertEquals(AccessClass.OBSERVE, AccessClass.of("pilot", JsonParser.parseString("{\"action\":\"stop\"}").getAsJsonObject()));
        assertEquals(AccessClass.OBSERVE, AccessClass.of("scan", null));
    }
}
