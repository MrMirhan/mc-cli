package dev.mccli.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import dev.mccli.mixin.AbstractContainerScreenAccessor;
import dev.mccli.util.ClientCompat;
import dev.mccli.util.ItemJson;
import dev.mccli.util.MainThreadExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The open screen as data, and actions on it.
 *
 * Actions:
 * - read (default): screen class, title, GUI size and scale, widgets (text, position, active), and for a container its
 *   slots with each item's name, lore and the tooltip this client draws, plus the hovered slot. {include_empty: false}
 *   leaves empty slots out.
 * - hover: {slot} or {x, y}; the tooltip shows, read it with read or capture it with screenshot layer tooltip
 * - click / right_click / shift_click: {slot} or {x, y}, as a real mouse click on the screen
 * - button: {text}; clicks the first active widget whose text contains it (case-insensitive)
 * - close: closes the screen like Escape
 */
public class GuiCommand implements Command {
    private final InputCommand input = new InputCommand();

    @Override
    public String getName() {
        return "gui";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        String action = params.has("action") ? params.get("action").getAsString() : "read";
        return switch (action) {
            case "read" -> MainThreadExecutor.submit(() -> read(params));
            case "hover" -> input.execute(withAction(params, "mouse_move"));
            case "click" -> input.execute(withAction(params, "mouse_click"));
            case "right_click" -> {
                JsonObject p = withAction(params, "mouse_click");
                p.addProperty("button", 1);
                yield input.execute(p);
            }
            case "shift_click" -> {
                JsonObject p = withAction(params, "mouse_click");
                p.addProperty("modifiers", 1);
                yield input.execute(p);
            }
            case "button" -> MainThreadExecutor.submit(() -> button(params)).thenCompose(input::execute);
            case "close" -> MainThreadExecutor.submit(GuiCommand::close);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                "Unknown gui action '" + action + "'. Use read, hover, click, right_click, shift_click, button or close"));
        };
    }

    private static JsonObject withAction(JsonObject params, String action) {
        JsonObject copy = params.deepCopy();
        copy.addProperty("action", action);
        return copy;
    }

    static JsonObject read(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        Screen screen = ClientCompat.screen(client);
        Window window = client.getWindow();
        JsonObject out = new JsonObject();
        out.addProperty("open", screen != null);
        out.addProperty("gui_width", window.getGuiScaledWidth());
        out.addProperty("gui_height", window.getGuiScaledHeight());
        out.addProperty("gui_scale", window.getGuiScale());
        if (screen == null) {
            return out;
        }
        out.addProperty("screen", screen.getClass().getSimpleName());
        out.addProperty("title", screen.getTitle().getString());

        JsonArray widgets = new JsonArray();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget w && w.visible) {
                JsonObject entry = new JsonObject();
                entry.addProperty("type", w.getClass().getSimpleName());
                entry.addProperty("text", w.getMessage().getString());
                entry.addProperty("x", w.getX());
                entry.addProperty("y", w.getY());
                entry.addProperty("width", w.getWidth());
                entry.addProperty("height", w.getHeight());
                entry.addProperty("active", w.isActive());
                widgets.add(entry);
            }
        }
        out.add("widgets", widgets);

        if (screen instanceof AbstractContainerScreen<?> container) {
            boolean includeEmpty = !params.has("include_empty") || params.get("include_empty").getAsBoolean();
            AbstractContainerScreenAccessor at = (AbstractContainerScreenAccessor) container;
            AbstractContainerMenu menu = container.getMenu();
            JsonObject box = new JsonObject();
            box.addProperty("left", at.mccli$getLeftPos());
            box.addProperty("top", at.mccli$getTopPos());
            box.addProperty("width", at.mccli$getImageWidth());
            box.addProperty("height", at.mccli$getImageHeight());
            box.addProperty("sync_id", menu.containerId);
            box.addProperty("slots", menu.slots.size());
            out.add("container", box);
            JsonArray slots = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (!includeEmpty && !slot.hasItem()) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("index", i);
                entry.addProperty("center_x", at.mccli$getLeftPos() + slot.x + 8);
                entry.addProperty("center_y", at.mccli$getTopPos() + slot.y + 8);
                entry.addProperty("player_inventory", client.player != null && slot.container == client.player.getInventory());
                entry.add("item", ItemJson.withTooltip(slot.getItem()));
                slots.add(entry);
            }
            out.add("slots", slots);
            Slot hovered = at.mccli$getHoveredSlot();
            if (hovered != null) {
                out.addProperty("hovered_slot", menu.slots.indexOf(hovered));
            }
            out.add("cursor", ItemJson.fromStack(menu.getCarried(), false));
        }
        return out;
    }

    private static JsonObject button(JsonObject params) {
        if (!params.has("text")) {
            throw new IllegalArgumentException("text is required: part of the button's label");
        }
        String wanted = params.get("text").getAsString().toLowerCase(Locale.ROOT);
        Screen screen = ClientCompat.screen(Minecraft.getInstance());
        if (screen == null) {
            throw new IllegalStateException("No screen is open");
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget w && w.visible && w.isActive()
                && w.getMessage().getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                JsonObject click = new JsonObject();
                click.addProperty("action", "mouse_click");
                click.addProperty("x", w.getX() + w.getWidth() / 2.0);
                click.addProperty("y", w.getY() + w.getHeight() / 2.0);
                return click;
            }
        }
        throw new IllegalArgumentException("No active button with text containing '" + params.get("text").getAsString() + "'");
    }

    private static JsonObject close() {
        Minecraft client = Minecraft.getInstance();
        Screen screen = ClientCompat.screen(client);
        JsonObject out = new JsonObject();
        out.addProperty("closed", screen != null ? screen.getClass().getSimpleName() : null);
        if (screen != null) {
            screen.onClose();
        }
        return out;
    }
}
