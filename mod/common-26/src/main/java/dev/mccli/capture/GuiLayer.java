package dev.mccli.capture;

import java.util.Locale;

/** What a screenshot or a recording shows: the game frame, or the open screen and/or its tooltip on transparency. */
public enum GuiLayer {
    WORLD, GUI, TOOLTIP, GUI_TOOLTIP;

    public static GuiLayer parse(String value) {
        if (value == null) {
            return WORLD;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "world" -> WORLD;
            case "gui" -> GUI;
            case "tooltip" -> TOOLTIP;
            case "gui+tooltip", "gui_tooltip" -> GUI_TOOLTIP;
            default -> throw new IllegalArgumentException("layer must be world, gui, tooltip or gui+tooltip");
        };
    }

    public boolean transparent() {
        return this != WORLD;
    }

    public boolean needsScreen() {
        return this == GUI || this == GUI_TOOLTIP;
    }
}
