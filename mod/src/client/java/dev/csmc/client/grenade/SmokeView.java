package dev.csmc.client.grenade;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * What the camera sees of smoke: dense fog plus a grey overlay while inside a cloud, and name-tag occlusion through
 * clouds. Only per-frame render state (shader fog uniforms, clear colour) is touched; options and render distance
 * are never changed, so leaving the smoke or the world restores vanilla rendering on the next frame.
 */
public final class SmokeView {
    /** Fog end (blocks) deep inside a cloud. */
    private static final float INSIDE_FOG_END = 1.6f;
    /** Overlay opacity at full immersion; fog does most of the hiding, the overlay hides near-plane puff gaps. */
    private static final float OVERLAY_ALPHA = 0.72f;
    /** Name tags hide once a line of sight crosses this many block-equivalents of dense smoke. */
    private static final float NAME_TAG_LIMIT = 1.2f;
    private static float immersion;
    private static float brightness = 1;

    private SmokeView() {}

    static void clear() {
        immersion = 0;
        brightness = 1;
    }

    /** Smoothed 0..1 camera immersion; sampled at the start of each frame's fog setup. */
    private static float immersion(Camera camera) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || GrenadeEntities.CLOUDS.isEmpty()) {
            immersion = 0;
            return 0;
        }
        float partial = client.getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 eye = camera.getPosition();
        float density = GrenadeEntities.smokeAt(client.level, eye.x, eye.y, eye.z, partial);
        // Even the soft rim of a cloud should already be murky.
        immersion = GrenadeEntities.smoothstep(0.02f, 0.55f, density);
        BlockPos pos = BlockPos.containing(camera.getPosition());
        brightness = Math.max(0.12f, client.level.getMaxLocalRawBrightness(pos) / 15f);
        return immersion;
    }

    /** FogRenderer.setupFog tail: pull fog in to a couple of blocks while inside smoke. */
    public static void applyFog(Camera camera) {
        float amount = immersion(camera);
        if (amount <= 0) return;
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        if (!Float.isFinite(start) || start > end) start = end;
        RenderSystem.setShaderFogStart(Mth.lerp(amount, start, -1.0f));
        RenderSystem.setShaderFogEnd(Mth.lerp(amount, end, INSIDE_FOG_END));
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }

    /** Blend factor and target grey for FogRenderer's fog/clear colour while inside smoke. */
    public static float fogBlend() {
        return immersion;
    }

    public static float fogGrey() {
        return 0.62f * brightness;
    }

    static void renderOverlay(GuiGraphics graphics) {
        if (immersion <= 0.001f) return;
        int grey = Mth.clamp(Math.round(fogGrey() * 255), 0, 255);
        int alpha = Mth.clamp(Math.round(immersion * OVERLAY_ALPHA * 255), 0, 255);
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | grey << 16 | grey << 8 | grey);
    }

    /** True when the camera-to-name-tag line crosses dense smoke (vanilla name tags otherwise show through it). */
    public static boolean hidesNameTag(Entity entity, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || GrenadeEntities.CLOUDS.isEmpty()) return false;
        Vec3 camera = client.gameRenderer.getMainCamera().getPosition();
        Vec3 tag = entity.getPosition(partialTick).add(0, entity.getBbHeight() + 0.5, 0);
        return GrenadeEntities.smokeAlong(client.level, camera, tag, partialTick, NAME_TAG_LIMIT) > NAME_TAG_LIMIT;
    }
}
