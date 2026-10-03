package dev.mccli.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mccli.mixin.HandledScreenAccessor;
import dev.mccli.mixin.KeyboardInvoker;
import dev.mccli.mixin.MouseInvoker;
import dev.mccli.util.ItemJson;
import dev.mccli.util.MainThreadExecutor;
import dev.mccli.util.McNames;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Mouse, keyboard and container slot input, sent through the callbacks GLFW itself would call.
 *
 * Actions:
 * - mouse_move: {x, y} in GUI coordinates, or {slot}; moves the cursor, so a hovered slot shows its tooltip
 * - mouse_click: {x, y} or {slot} (optional), button (0 left, 1 right, 2 middle), mode click|press|release,
 *   modifiers (GLFW bits, 1 = shift); handled by the open screen exactly like a real click
 * - key: key (GLFW code, or a name such as "tab", "f3", "escape", "e", "left.shift"), mode tap|press|release,
 *   modifiers
 * - type: text; one character event per code point, as GLFW sends while typing (no key presses, so send key
 *   enter or backspace separately); goes to the open screen's focused field: chat, sign, anvil, book
 * - slots: the open container's slots with index, GUI position, centre and item
 * - slot_click: slot, button, type (pickup, quick_move, swap, clone, throw, quick_craft, pickup_all); sends the
 *   click the way ClientPlayerInteractionManager.clickSlot does, without moving the cursor
 *
 * GUI coordinates are the ones screens use: window pixels divided by the GUI scale. To capture something that
 * only shows while a key is held (the player list), send key {mode: press}, take the screenshot, then send
 * key {mode: release}.
 */
public class InputCommand implements Command {
    @Override
    public String getName() {
        return "input";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        String action = params.has("action") ? params.get("action").getAsString() : "";
        return switch (action) {
            case "mouse_move" -> MainThreadExecutor.submit(() -> mouseMove(params));
            case "mouse_click" -> MainThreadExecutor.submit(() -> mouseClick(params));
            case "key" -> MainThreadExecutor.submit(() -> key(params));
            case "type" -> MainThreadExecutor.submit(() -> type(params));
            case "slots" -> MainThreadExecutor.submit(InputCommand::slots);
            case "slot_click" -> MainThreadExecutor.submit(() -> slotClick(params));
            default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                "Unknown input action '" + action + "'. Use mouse_move, mouse_click, key, type, slots or slot_click"));
        };
    }

    private static JsonObject mouseMove(JsonObject params) {
        MinecraftClient client = MinecraftClient.getInstance();
        double[] gui = point(client, params);
        return moveTo(client, gui[0], gui[1]);
    }

    private static JsonObject moveTo(MinecraftClient client, double guiX, double guiY) {
        Window window = client.getWindow();
        // the inverse of Mouse.getScaledX/Y (window pixels * scaled size / window size)
        double x = guiX * window.getWidth() / window.getScaledWidth();
        double y = guiY * window.getHeight() / window.getScaledHeight();
        ((MouseInvoker) client.mouse).mccli$onCursorPos(window.getHandle(), x, y);
        JsonObject response = new JsonObject();
        response.addProperty("gui_x", guiX);
        response.addProperty("gui_y", guiY);
        response.addProperty("window_x", x);
        response.addProperty("window_y", y);
        response.addProperty("screen", screenName(client.currentScreen));
        return response;
    }

    private static JsonObject mouseClick(JsonObject params) {
        MinecraftClient client = MinecraftClient.getInstance();
        JsonObject response = new JsonObject();
        if (params.has("slot") || params.has("x") || params.has("y")) {
            double[] gui = point(client, params);
            response = moveTo(client, gui[0], gui[1]);
        }
        int button = params.has("button") ? params.get("button").getAsInt() : GLFW.GLFW_MOUSE_BUTTON_LEFT;
        int modifiers = params.has("modifiers") ? params.get("modifiers").getAsInt() : 0;
        String mode = params.has("mode") ? params.get("mode").getAsString() : "click";
        MouseInput input = new MouseInput(button, modifiers);
        MouseInvoker mouse = (MouseInvoker) client.mouse;
        long handle = client.getWindow().getHandle();
        String before = screenName(client.currentScreen);
        switch (mode) {
            case "click" -> {
                mouse.mccli$onMouseButton(handle, input, GLFW.GLFW_PRESS);
                mouse.mccli$onMouseButton(handle, input, GLFW.GLFW_RELEASE);
            }
            case "press" -> mouse.mccli$onMouseButton(handle, input, GLFW.GLFW_PRESS);
            case "release" -> mouse.mccli$onMouseButton(handle, input, GLFW.GLFW_RELEASE);
            default -> throw new IllegalArgumentException("mode must be click, press or release");
        }
        response.addProperty("button", button);
        response.addProperty("mode", mode);
        response.addProperty("screen_before", before);
        response.addProperty("screen", screenName(client.currentScreen));
        return response;
    }

    private static JsonObject key(JsonObject params) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!params.has("key")) {
            throw new IllegalArgumentException("key is required: a GLFW key code or a name such as tab, f3, escape, e");
        }
        int code = keyCode(params.get("key"));
        int scancode = params.has("scancode") ? params.get("scancode").getAsInt() : 0;
        int modifiers = params.has("modifiers") ? params.get("modifiers").getAsInt() : 0;
        String mode = params.has("mode") ? params.get("mode").getAsString() : "tap";
        KeyInput input = new KeyInput(code, scancode, modifiers);
        KeyboardInvoker keyboard = (KeyboardInvoker) client.keyboard;
        long handle = client.getWindow().getHandle();
        String before = screenName(client.currentScreen);
        switch (mode) {
            case "tap" -> {
                keyboard.mccli$onKey(handle, GLFW.GLFW_PRESS, input);
                keyboard.mccli$onKey(handle, GLFW.GLFW_RELEASE, input);
            }
            case "press" -> keyboard.mccli$onKey(handle, GLFW.GLFW_PRESS, input);
            case "release" -> keyboard.mccli$onKey(handle, GLFW.GLFW_RELEASE, input);
            default -> throw new IllegalArgumentException("mode must be tap, press or release");
        }
        JsonObject response = new JsonObject();
        response.addProperty("key", code);
        response.addProperty("mode", mode);
        response.addProperty("screen_before", before);
        response.addProperty("screen", screenName(client.currentScreen));
        return response;
    }

    private static JsonObject type(JsonObject params) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!params.has("text")) {
            throw new IllegalArgumentException("text is required");
        }
        String text = params.get("text").getAsString();
        Screen screen = client.currentScreen;
        if (screen == null) {
            throw new IllegalStateException("No screen is open to type into; open chat (key t) or a sign, anvil or book first");
        }
        KeyboardInvoker keyboard = (KeyboardInvoker) client.keyboard;
        long handle = client.getWindow().getHandle();
        int[] codepoints = text.codePoints().toArray();
        for (int codepoint : codepoints) {
            keyboard.mccli$onChar(handle, new CharInput(codepoint, 0));
        }
        JsonObject response = new JsonObject();
        response.addProperty("chars", codepoints.length);
        response.addProperty("screen_before", screenName(screen));
        response.addProperty("screen", screenName(client.currentScreen));
        return response;
    }

    private static JsonObject slots() {
        MinecraftClient client = MinecraftClient.getInstance();
        HandledScreen<?> screen = container(client);
        HandledScreenAccessor at = (HandledScreenAccessor) screen;
        ScreenHandler handler = screen.getScreenHandler();
        Window window = client.getWindow();
        JsonObject response = new JsonObject();
        response.addProperty("screen", McNames.screen(screen));
        response.addProperty("title", screen.getTitle().getString());
        response.addProperty("sync_id", handler.syncId);
        response.addProperty("left", at.mccli$getX());
        response.addProperty("top", at.mccli$getY());
        response.addProperty("width", at.mccli$getBackgroundWidth());
        response.addProperty("height", at.mccli$getBackgroundHeight());
        response.addProperty("gui_width", window.getScaledWidth());
        response.addProperty("gui_height", window.getScaledHeight());
        response.addProperty("gui_scale", window.getScaleFactor());
        JsonArray slots = new JsonArray();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("index", i);
            entry.addProperty("x", at.mccli$getX() + slot.x);
            entry.addProperty("y", at.mccli$getY() + slot.y);
            entry.addProperty("center_x", at.mccli$getX() + slot.x + 8);
            entry.addProperty("center_y", at.mccli$getY() + slot.y + 8);
            entry.addProperty("player_inventory", client.player != null && slot.inventory == client.player.getInventory());
            entry.add("item", ItemJson.fromStack(slot.getStack(), false));
            slots.add(entry);
        }
        response.add("slots", slots);
        return response;
    }

    private static JsonObject slotClick(JsonObject params) {
        MinecraftClient client = MinecraftClient.getInstance();
        HandledScreen<?> screen = container(client);
        if (!params.has("slot")) {
            throw new IllegalArgumentException("slot is required (-999 clicks outside the window)");
        }
        ScreenHandler handler = screen.getScreenHandler();
        int slot = params.get("slot").getAsInt();
        if (slot != -999 && (slot < 0 || slot >= handler.slots.size())) {
            throw new IllegalArgumentException("slot " + slot + " is not in 0.." + (handler.slots.size() - 1));
        }
        int button = params.has("button") ? params.get("button").getAsInt() : 0;
        String type = params.has("type") ? params.get("type").getAsString() : "pickup";
        SlotActionType actionType;
        try {
            actionType = SlotActionType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be pickup, quick_move, swap, clone, throw, quick_craft or pickup_all");
        }
        if (client.interactionManager == null || client.player == null) {
            throw new IllegalStateException("Not in a world");
        }
        client.interactionManager.clickSlot(handler.syncId, slot, button, actionType, client.player);
        JsonObject response = new JsonObject();
        response.addProperty("slot", slot);
        response.addProperty("button", button);
        response.addProperty("type", type.toLowerCase(Locale.ROOT));
        response.addProperty("title", screen.getTitle().getString());
        response.add("cursor", ItemJson.fromStack(handler.getCursorStack(), false));
        return response;
    }

    private static HandledScreen<?> container(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?> screen) {
            return screen;
        }
        throw new IllegalStateException("No container screen is open"
            + (client.currentScreen != null ? " (open: " + McNames.screen(client.currentScreen) + ")" : ""));
    }

    private static double[] point(MinecraftClient client, JsonObject params) {
        if (params.has("slot")) {
            HandledScreen<?> screen = container(client);
            HandledScreenAccessor at = (HandledScreenAccessor) screen;
            ScreenHandler handler = screen.getScreenHandler();
            int index = params.get("slot").getAsInt();
            if (index < 0 || index >= handler.slots.size()) {
                throw new IllegalArgumentException("slot " + index + " is not in 0.." + (handler.slots.size() - 1));
            }
            Slot slot = handler.slots.get(index);
            return new double[] {at.mccli$getX() + slot.x + 8, at.mccli$getY() + slot.y + 8};
        }
        if (!params.has("x") || !params.has("y")) {
            throw new IllegalArgumentException("give x and y in GUI coordinates, or slot");
        }
        return new double[] {params.get("x").getAsDouble(), params.get("y").getAsDouble()};
    }

    private static int keyCode(JsonElement key) {
        if (key.isJsonPrimitive() && key.getAsJsonPrimitive().isNumber()) {
            return key.getAsInt();
        }
        String name = key.getAsString().trim().toLowerCase(Locale.ROOT);
        String translation = name.startsWith("key.") ? name : "key.keyboard." + name;
        int code;
        try {
            code = InputUtil.fromTranslationKey(translation).getCode();
        } catch (IllegalArgumentException e) {
            code = GLFW.GLFW_KEY_UNKNOWN;
        }
        if (code == GLFW.GLFW_KEY_UNKNOWN) {
            throw new IllegalArgumentException("Unknown key '" + name + "' (" + translation + ")");
        }
        return code;
    }

    private static String screenName(Screen screen) {
        return screen != null ? McNames.screen(screen) : null;
    }
}
