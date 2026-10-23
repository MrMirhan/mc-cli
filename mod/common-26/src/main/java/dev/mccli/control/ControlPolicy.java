package dev.mccli.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a connected tool may run a command right now.
 *
 * The player decides in config/mccli.json: {@code off} refuses everything, {@code ask} shows a consent screen on the
 * first request of a game session, {@code on} allows it. Only the allowed {@link AccessClass}es pass, and the pause
 * key stops every command until it is pressed again.
 */
public final class ControlPolicy {
    public enum Mode { OFF, ASK, ON }

    public enum Decision { ALLOWED, OFF, CLASS_DENIED, PAUSED, CONSENT_REQUIRED, CONSENT_DENIED }

    public enum Consent { NONE, GRANTED, DENIED }

    private final Mode mode;
    private final EnumSet<AccessClass> allowed;
    private volatile boolean paused;
    private volatile Consent consent = Consent.NONE;

    public ControlPolicy(Mode mode, Set<AccessClass> allowed) {
        this.mode = mode;
        this.allowed = allowed.isEmpty() ? EnumSet.noneOf(AccessClass.class) : EnumSet.copyOf(allowed);
    }

    public Decision check(String command, JsonObject params) {
        if ("control".equals(command)) {
            return Decision.ALLOWED;
        }
        if (mode == Mode.OFF) {
            return Decision.OFF;
        }
        if (mode == Mode.ASK) {
            if (consent == Consent.DENIED) {
                return Decision.CONSENT_DENIED;
            }
            if (consent == Consent.NONE) {
                return Decision.CONSENT_REQUIRED;
            }
        }
        if (paused) {
            return Decision.PAUSED;
        }
        return allowed.contains(AccessClass.of(command, params)) ? Decision.ALLOWED : Decision.CLASS_DENIED;
    }

    /** Control is in use: the badge shows and the pause key works. */
    public boolean active() {
        return mode == Mode.ON || (mode == Mode.ASK && consent == Consent.GRANTED);
    }

    public Mode mode() {
        return mode;
    }

    public Set<AccessClass> allowed() {
        return EnumSet.copyOf(allowed);
    }

    public boolean paused() {
        return paused;
    }

    public void togglePause() {
        paused = !paused;
    }

    public Consent consent() {
        return consent;
    }

    public void answer(boolean accepted) {
        consent = accepted ? Consent.GRANTED : Consent.DENIED;
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("mode", mode.name().toLowerCase(Locale.ROOT));
        JsonArray classes = new JsonArray();
        for (AccessClass c : allowed) {
            classes.add(c.id());
        }
        out.add("allow", classes);
        out.addProperty("paused", paused);
        out.addProperty("consent", consent.name().toLowerCase(Locale.ROOT));
        return out;
    }
}
