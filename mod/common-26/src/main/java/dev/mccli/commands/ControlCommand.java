package dev.mccli.commands;

import com.google.gson.JsonObject;
import dev.mccli.control.ControlRuntime;

import java.util.concurrent.CompletableFuture;

/**
 * What this game lets a tool do: {mode, allow, paused, consent}. Answers in every mode, so a tool can tell the
 * person why its commands are refused.
 */
public class ControlCommand implements Command {
    @Override
    public String getName() {
        return "control";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        return CompletableFuture.completedFuture(ControlRuntime.policy().toJson());
    }
}
