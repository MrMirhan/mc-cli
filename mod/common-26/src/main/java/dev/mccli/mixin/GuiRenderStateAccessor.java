package dev.mccli.mixin;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
    /** GuiRenderState$Node is package-private, so the strata are plain objects here. */
    @Accessor("strata")
    List<Object> mccli$strata();
}
