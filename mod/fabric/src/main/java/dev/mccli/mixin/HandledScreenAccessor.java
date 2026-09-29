package dev.mccli.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where a container screen sits in GUI space, to turn a slot index into a point to hover or click.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int mccli$getX();

    @Accessor("y")
    int mccli$getY();

    @Accessor("backgroundWidth")
    int mccli$getBackgroundWidth();

    @Accessor("backgroundHeight")
    int mccli$getBackgroundHeight();
}
