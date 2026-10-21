package dev.csmc.client.grenade;

import dev.csmc.network.GrenadeFlashPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Server-targeted flashbang whiteout: full {@code opacity} for {@code holdTicks}, then a linear fade over
 * {@code fadeTicks}. Inputs are validated and bounded; a weaker flash never shortens a stronger one in progress.
 * Drawn on the HUD after the scope overlay, so a scoped player is blinded too. Nothing outside this class changes.
 */
final class FlashOverlay {
    static final int MAX_HOLD_TICKS = 100;
    static final int MAX_FADE_TICKS = 200;
    private static float opacity;
    private static int hold;
    private static int fade;
    private static int age;

    private FlashOverlay() {}

    static void receive(GrenadeFlashPayload payload) {
        float strength = payload.opacity();
        if (!Float.isFinite(strength) || strength <= 0) return;
        strength = Math.min(1, strength);
        int holdTicks = Mth.clamp(payload.holdTicks(), 0, MAX_HOLD_TICKS);
        int fadeTicks = Mth.clamp(payload.fadeTicks(), 1, MAX_FADE_TICKS);
        // Keep whichever flash is brighter now, or lasts longer at equal brightness.
        float current = alpha(0);
        int currentRemaining = Math.max(0, hold + fade - age);
        if (strength < current || (strength == current && holdTicks + fadeTicks < currentRemaining)) return;
        opacity = strength;
        hold = holdTicks;
        fade = fadeTicks;
        age = 0;
    }

    static void tick() {
        if (opacity <= 0) return;
        age++;
        if (age >= hold + fade) clear();
    }

    static void clear() {
        opacity = 0;
        hold = 0;
        fade = 0;
        age = 0;
    }

    private static float alpha(float partialTick) {
        if (opacity <= 0) return 0;
        float t = age + partialTick;
        if (t <= hold) return opacity;
        return opacity * Mth.clamp(1 - (t - hold) / fade, 0, 1);
    }

    static void render(GuiGraphics graphics, float partialTick) {
        float alpha = alpha(partialTick);
        if (alpha <= 0.002f) return;
        int a = Mth.clamp(Math.round(alpha * 255), 0, 255);
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), a << 24 | 0xFFFFFF);
    }
}
