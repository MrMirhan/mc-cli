package dev.mccli.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.mccli.mixin.AbstractContainerScreenAccessor;
import dev.mccli.mixin.KeyboardHandlerInvoker;
import dev.mccli.mixin.MouseHandlerInvoker;
import dev.mccli.platform.Platform;
import dev.mccli.util.ClientCompat;
import dev.mccli.util.ItemJson;
import dev.mccli.util.MainThreadExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Mouse, keyboard and container slot input, sent through the callbacks the window itself would call.
 *
 * Actions:
 * - mouse_move: {x, y} in GUI coordinates, or {slot}; moves the cursor, so a hovered slot shows its tooltip
 * - mouse_click: {x, y} or {slot} (optional), button (0 left, 1 right, 2 middle), mode click|press|release,
 *   modifiers (1 = shift); handled by the open screen exactly like a real click
 * - key: key (key code, or a name such as "tab", "f3", "escape", "e", "left.shift"), mode tap|press|release,
 *   modifiers
 * - type: text; one character event per code point, as a keyboard sends while typing (no key presses, so
 *   send key enter or backspace separately); goes to the open screen's focused field: chat, sign, anvil, book
 * - slots: the open container's slots with index, GUI position, centre and item
 * - slot_click: slot, button, type (pickup, quick_move, swap, clone, throw, quick_craft, pickup_all); sends the
 *   click the way MultiPlayerGameMode.handleContainerInput does, without moving the cursor
 *
 * GUI coordinates are the ones screens use: window pixels divided by the GUI scale. To capture something that
 * only shows while a key is held (the player list), send key {mode: press}, take the screenshot, then send
 * key {mode: release}.
 */
public class InputCommand implements Command {
    private static final int LEFT_BUTTON = 0;

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
        Minecraft client = Minecraft.getInstance();
        double[] gui = point(client, params);
        return moveTo(client, gui[0], gui[1]);
    }

    private static JsonObject moveTo(Minecraft client, double guiX, double guiY) {
        Window window = client.getWindow();
        // the inverse of MouseHandler.getScaledXPos/YPos (window pixels * GUI size / window size)
        double x = guiX * window.getWidth() / window.getGuiScaledWidth();
        double y = guiY * window.getHeight() / window.getGuiScaledHeight();
        Platform.moveCursor(client, ClientCompat.windowHandle(client), x, y);
        JsonObject response = new JsonObject();
        response.addProperty("gui_x", guiX);
        response.addProperty("gui_y", guiY);
        response.addProperty("window_x", x);
        response.addProperty("window_y", y);
        response.addProperty("screen", screenName(ClientCompat.screen(client)));
        return response;
    }

    private static JsonObject mouseClick(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        JsonObject response = new JsonObject();
        if (params.has("slot") || params.has("x") || params.has("y")) {
            double[] gui = point(client, params);
            response = moveTo(client, gui[0], gui[1]);
        }
        int button = params.has("button") ? params.get("button").getAsInt() : LEFT_BUTTON;
        int modifiers = params.has("modifiers") ? params.get("modifiers").getAsInt() : 0;
        String mode = params.has("mode") ? params.get("mode").getAsString() : "click";
        MouseButtonInfo info = new MouseButtonInfo(button, modifiers);
        MouseHandlerInvoker mouse = (MouseHandlerInvoker) client.mouseHandler;
        long handle = ClientCompat.windowHandle(client);
        String before = screenName(ClientCompat.screen(client));
        switch (mode) {
            case "click" -> {
                mouse.mccli$onButton(handle, info, InputConstants.PRESS);
                mouse.mccli$onButton(handle, info, InputConstants.RELEASE);
            }
            case "press" -> mouse.mccli$onButton(handle, info, InputConstants.PRESS);
            case "release" -> mouse.mccli$onButton(handle, info, InputConstants.RELEASE);
            default -> throw new IllegalArgumentException("mode must be click, press or release");
        }
        response.addProperty("button", button);
        response.addProperty("mode", mode);
        response.addProperty("screen_before", before);
        response.addProperty("screen", screenName(ClientCompat.screen(client)));
        return response;
    }

    private static JsonObject key(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        if (!params.has("key")) {
            throw new IllegalArgumentException("key is required: a key code or a name such as tab, f3, escape, e");
        }
        int code = keyCode(params.get("key"));
        int scancode = params.has("scancode") ? params.get("scancode").getAsInt() : 0;
        int modifiers = params.has("modifiers") ? params.get("modifiers").getAsInt() : 0;
        String mode = params.has("mode") ? params.get("mode").getAsString() : "tap";
        KeyEvent event = new KeyEvent(code, scancode, modifiers);
        KeyboardHandlerInvoker keyboard = (KeyboardHandlerInvoker) client.keyboardHandler;
        long handle = ClientCompat.windowHandle(client);
        String before = screenName(ClientCompat.screen(client));
        switch (mode) {
            case "tap" -> {
                keyboard.mccli$keyPress(handle, InputConstants.PRESS, event);
                keyboard.mccli$keyPress(handle, InputConstants.RELEASE, event);
            }
            case "press" -> keyboard.mccli$keyPress(handle, InputConstants.PRESS, event);
            case "release" -> keyboard.mccli$keyPress(handle, InputConstants.RELEASE, event);
            default -> throw new IllegalArgumentException("mode must be tap, press or release");
        }
        JsonObject response = new JsonObject();
        response.addProperty("key", code);
        response.addProperty("mode", mode);
        response.addProperty("screen_before", before);
        response.addProperty("screen", screenName(ClientCompat.screen(client)));
        return response;
    }

    private static JsonObject type(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        if (!params.has("text")) {
            throw new IllegalArgumentException("text is required");
        }
        String text = params.get("text").getAsString();
        Screen screen = ClientCompat.screen(client);
        if (screen == null) {
            throw new IllegalStateException("No screen is open to type into; open chat (key t) or a sign, anvil or book first");
        }
        KeyboardHandlerInvoker keyboard = (KeyboardHandlerInvoker) client.keyboardHandler;
        long handle = ClientCompat.windowHandle(client);
        int[] codepoints = text.codePoints().toArray();
        for (int codepoint : codepoints) {
            keyboard.mccli$charTyped(handle, new CharacterEvent(codepoint));
        }
        JsonObject response = new JsonObject();
        response.addProperty("chars", codepoints.length);
        response.addProperty("screen_before", screenName(screen));
        response.addProperty("screen", screenName(ClientCompat.screen(client)));
        return response;
    }

    private static JsonObject slots() {
        Minecraft client = Minecraft.getInstance();
        AbstractContainerScreen<?> screen = container(client);
        AbstractContainerScreenAccessor at = (AbstractContainerScreenAccessor) screen;
        AbstractContainerMenu menu = screen.getMenu();
        Window window = client.getWindow();
        JsonObject response = new JsonObject();
        response.addProperty("screen", screenName(screen));
        response.addProperty("title", screen.getTitle().getString());
        response.addProperty("sync_id", menu.containerId);
        response.addProperty("left", at.mccli$getLeftPos());
        response.addProperty("top", at.mccli$getTopPos());
        response.addProperty("width", at.mccli$getImageWidth());
        response.addProperty("height", at.mccli$getImageHeight());
        response.addProperty("gui_width", window.getGuiScaledWidth());
        response.addProperty("gui_height", window.getGuiScaledHeight());
        response.addProperty("gui_scale", window.getGuiScale());
        JsonArray slots = new JsonArray();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("index", i);
            entry.addProperty("x", at.mccli$getLeftPos() + slot.x);
            entry.addProperty("y", at.mccli$getTopPos() + slot.y);
            entry.addProperty("center_x", at.mccli$getLeftPos() + slot.x + 8);
            entry.addProperty("center_y", at.mccli$getTopPos() + slot.y + 8);
            entry.addProperty("player_inventory", client.player != null && slot.container == client.player.getInventory());
            entry.add("item", ItemJson.fromStack(slot.getItem(), false));
            slots.add(entry);
        }
        response.add("slots", slots);
        return response;
    }

    private static JsonObject slotClick(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        AbstractContainerScreen<?> screen = container(client);
        if (!params.has("slot")) {
            throw new IllegalArgumentException("slot is required (-999 clicks outside the window)");
        }
        AbstractContainerMenu menu = screen.getMenu();
        int slot = params.get("slot").getAsInt();
        if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) {
            throw new IllegalArgumentException("slot " + slot + " is not in 0.." + (menu.slots.size() - 1));
        }
        int button = params.has("button") ? params.get("button").getAsInt() : 0;
        String type = params.has("type") ? params.get("type").getAsString() : "pickup";
        ContainerInput input;
        try {
            input = ContainerInput.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be pickup, quick_move, swap, clone, throw, quick_craft or pickup_all");
        }
        if (client.gameMode == null || client.player == null) {
            throw new IllegalStateException("Not in a world");
        }
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, client.player);
        JsonObject response = new JsonObject();
        response.addProperty("slot", slot);
        response.addProperty("button", button);
        response.addProperty("type", type.toLowerCase(Locale.ROOT));
        response.addProperty("title", screen.getTitle().getString());
        response.add("cursor", ItemJson.fromStack(menu.getCarried(), false));
        return response;
    }

    private static AbstractContainerScreen<?> container(Minecraft client) {
        Screen screen = ClientCompat.screen(client);
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            return containerScreen;
        }
        throw new IllegalStateException("No container screen is open"
            + (screen != null ? " (open: " + screenName(screen) + ")" : ""));
    }

    private static double[] point(Minecraft client, JsonObject params) {
        if (params.has("slot")) {
            AbstractContainerScreen<?> screen = container(client);
            AbstractContainerScreenAccessor at = (AbstractContainerScreenAccessor) screen;
            AbstractContainerMenu menu = screen.getMenu();
            int index = params.get("slot").getAsInt();
            if (index < 0 || index >= menu.slots.size()) {
                throw new IllegalArgumentException("slot " + index + " is not in 0.." + (menu.slots.size() - 1));
            }
            Slot slot = menu.slots.get(index);
            return new double[] {at.mccli$getLeftPos() + slot.x + 8, at.mccli$getTopPos() + slot.y + 8};
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
            code = InputConstants.getKey(translation).getValue();
        } catch (IllegalArgumentException e) {
            code = InputConstants.UNKNOWN.getValue();
        }
        if (code == InputConstants.UNKNOWN.getValue()) {
            throw new IllegalArgumentException("Unknown key '" + name + "' (" + translation + ")");
        }
        return code;
    }

    private static String screenName(Screen screen) {
        return screen != null ? screen.getClass().getSimpleName() : null;
    }
}
