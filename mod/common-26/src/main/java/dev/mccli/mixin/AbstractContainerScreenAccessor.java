package dev.mccli.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where a container screen sits in GUI space, to turn a slot index into a point to hover or click.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int mccli$getLeftPos();

    @Accessor("topPos")
    int mccli$getTopPos();

    @Accessor("imageWidth")
    int mccli$getImageWidth();

    @Accessor("imageHeight")
    int mccli$getImageHeight();
}
