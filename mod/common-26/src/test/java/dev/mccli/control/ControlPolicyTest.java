package dev.mccli.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class ControlPolicyTest {
    private static JsonObject params(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void offRefusesEverythingButTheControlQuery() {
        ControlPolicy p = new ControlPolicy(ControlPolicy.Mode.OFF, EnumSet.allOf(AccessClass.class));
        assertEquals(ControlPolicy.Decision.OFF, p.check("status", new JsonObject()));
        assertEquals(ControlPolicy.Decision.ALLOWED, p.check("control", new JsonObject()));
    }

    @Test
    void askWaitsForConsentThenFollowsTheAnswer() {
        ControlPolicy p = new ControlPolicy(ControlPolicy.Mode.ASK, EnumSet.of(AccessClass.OBSERVE));
        assertEquals(ControlPolicy.Decision.CONSENT_REQUIRED, p.check("screenshot", new JsonObject()));
        assertFalse(p.active());
        p.answer(true);
        assertTrue(p.active());
        assertEquals(ControlPolicy.Decision.ALLOWED, p.check("screenshot", new JsonObject()));
        assertEquals(ControlPolicy.Decision.CLASS_DENIED, p.check("chat", new JsonObject()));

        ControlPolicy denied = new ControlPolicy(ControlPolicy.Mode.ASK, EnumSet.of(AccessClass.OBSERVE));
        denied.answer(false);
        assertEquals(ControlPolicy.Decision.CONSENT_DENIED, denied.check("status", new JsonObject()));
    }

    @Test
    void pauseStopsEveryCommandUntilPressedAgain() {
        ControlPolicy p = new ControlPolicy(ControlPolicy.Mode.ON, EnumSet.allOf(AccessClass.class));
        p.togglePause();
        assertEquals(ControlPolicy.Decision.PAUSED, p.check("status", new JsonObject()));
        p.togglePause();
        assertEquals(ControlPolicy.Decision.ALLOWED, p.check("status", new JsonObject()));
    }

    @Test
    void guiReadIsObservingAndGuiClickIsInput() {
        assertEquals(AccessClass.OBSERVE, AccessClass.of("gui", params("{\"action\":\"read\"}")));
        assertEquals(AccessClass.OBSERVE, AccessClass.of("gui", new JsonObject()));
        assertEquals(AccessClass.INPUT, AccessClass.of("gui", params("{\"action\":\"click\"}")));
        assertEquals(AccessClass.INPUT, AccessClass.of("camera", params("{\"yaw\":90,\"pitch\":0}")));
        assertEquals(AccessClass.OBSERVE, AccessClass.of("camera", params("{\"action\":\"orbit\"}")));
        assertEquals(AccessClass.WORLD, AccessClass.of("something-new", new JsonObject()));
        assertEquals(AccessClass.WORLD, AccessClass.of("execute", new JsonObject()));
    }

    @Test
    void windowedDefaultAsksAndAllowsObserveAndInput(@TempDir Path dir) throws Exception {
        ControlPolicy p = ControlConfig.load(dir, false);
        assertEquals(ControlPolicy.Mode.ASK, p.mode());
        assertEquals(EnumSet.of(AccessClass.OBSERVE, AccessClass.INPUT), p.allowed());
        assertTrue(Files.readString(dir.resolve(ControlConfig.FILE)).contains("\"ask\""));
    }

    @Test
    void hiddenToolClientDefaultsToOn(@TempDir Path dir) {
        assertEquals(ControlPolicy.Mode.ON, ControlConfig.load(dir, true).mode());
    }

    @Test
    void brokenConfigRefuses(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ControlConfig.FILE), "{not json");
        assertEquals(ControlPolicy.Mode.OFF, ControlConfig.load(dir, true).mode());
    }

    @Test
    void configFileIsRead() {
        ControlPolicy p = ControlConfig.fromJson(params("{\"remoteControl\":\"on\",\"allow\":[\"observe\"]}"), null);
        assertEquals(ControlPolicy.Mode.ON, p.mode());
        assertEquals(EnumSet.of(AccessClass.OBSERVE), p.allowed());
    }

    @Test
    void tokenIsCreatedOnceAndCompared(@TempDir Path dir) throws Exception {
        AccessToken first = AccessToken.load(dir);
        AccessToken second = AccessToken.load(dir);
        assertEquals(64, first.value().length());
        assertEquals(first.value(), second.value());
        assertTrue(second.matches(first.value()));
        assertFalse(second.matches(null));
        assertFalse(second.matches(first.value().substring(1)));
    }
}
