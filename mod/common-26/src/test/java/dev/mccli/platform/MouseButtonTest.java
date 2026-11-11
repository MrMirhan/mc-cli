package dev.mccli.platform;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The API keeps GLFW numbering (0 left, 1 right, 2 middle) on every Minecraft version; 26.3 numbers buttons
 * the SDL way (1 left, 2 middle, 3 right), so a left click sent as 0 never pressed a widget there.
 */
class MouseButtonTest {
    @Test
    void apiButtonsMapToTheGameButtons() {
        assertEquals(InputConstants.MOUSE_BUTTON_LEFT, Platform.mouseButton(0));
        assertEquals(InputConstants.MOUSE_BUTTON_RIGHT, Platform.mouseButton(1));
        assertEquals(InputConstants.MOUSE_BUTTON_MIDDLE, Platform.mouseButton(2));
    }
}
