package dev.mccli.control;

import dev.mccli.util.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/** Asks the player once per game session whether a tool on this computer may control the game. */
public final class ConsentScreen extends Screen {
    private final @Nullable Screen previous;
    private final Consumer<Boolean> answer;
    private boolean answered;

    public ConsentScreen(@Nullable Screen previous, Consumer<Boolean> answer) {
        super(Component.translatable("mccli.consent.title"));
        this.previous = previous;
        this.answer = answer;
    }

    @Override
    protected void init() {
        int y = height / 2 + 30;
        addRenderableWidget(Button.builder(Component.translatable("mccli.consent.accept"), b -> close(true))
            .bounds(width / 2 - 154, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("mccli.consent.refuse"), b -> close(false))
            .bounds(width / 2 + 4, y, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, height / 2 - 50, 0xFFFFFFFF);
        g.textWithWordWrap(font, Component.translatable("mccli.consent.body", ControlRuntime.policy().allowed().stream()
            .map(c -> Component.translatable("mccli.class." + c.id()).getString()).toList().toString()),
            width / 2 - 150, height / 2 - 30, 300, 0xFFD0D0D0);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void close(boolean accepted) {
        if (answered) {
            return;
        }
        answered = true;
        answer.accept(accepted);
        ClientCompat.setScreen(Minecraft.getInstance(), previous);
    }
}
