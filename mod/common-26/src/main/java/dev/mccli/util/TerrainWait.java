package dev.mccli.util;

import net.minecraft.client.Minecraft;

/** Blocks a worker thread until every visible section is built (vanilla and Sodium both report it), or the deadline. */
public final class TerrainWait {
    private TerrainWait() {}

    public static void sectionsBuilt(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Thread.sleep(150);   // let a camera move reach the renderer first
        while (System.currentTimeMillis() < deadline) {
            boolean built = MainThreadExecutor.submit(() -> {
                Minecraft mc = Minecraft.getInstance();
                return mc.level == null || mc.levelRenderer.hasRenderedAllSections();
            }).get();
            if (built) break;
            Thread.sleep(50);
        }
        Thread.sleep(100);   // the frame after the last section was built draws it
    }
}
