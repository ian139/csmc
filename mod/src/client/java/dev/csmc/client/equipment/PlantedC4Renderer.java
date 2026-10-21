package dev.csmc.client.equipment;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.equipment.PlantedC4Entity;
import dev.csmc.viewmodel.PlantedC4Display;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Planted C4: the cached world model in its authored planted pose ({@link CachedGunRendering#renderPlantedC4}) at the
 * entity's feet, turned to the planter's yaw, plus a camera-facing readout above it approximating the bomb display:
 * an LED that flashes on each server beep ({@link #beep}) and a M:SS countdown of the synced fuse. A defused bomb
 * shows a steady green LED and no countdown. Without a cache the model is simply absent; nothing is substituted.
 */
public final class PlantedC4Renderer extends EntityRenderer<PlantedC4Entity> {
    private static final String LED = "\u25CF";
    private static final int LED_LIT = 0xFFFF2A1A;
    private static final int LED_DARK = 0xFF3A0A08;
    private static final int LED_DEFUSED = 0xFF30FF50;
    private static final int DIGITS = 0xFFFF4030;
    /** Readout height above the feet: just over the grounded model's top. */
    private static final float READOUT_Y = 0.34F;
    private static final float READOUT_SCALE = 0.0125F;
    private static final float GAP = 3.0F;
    /** Client game time of each bomb's latest beep; weak so removed bombs drop out. */
    private static final Map<PlantedC4Entity, Long> LAST_BEEP = new WeakHashMap<>();
    private final Quaternionf rotation = new Quaternionf();

    private PlantedC4Renderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.25F;
    }

    public static void register() {
        EntityRendererRegistry.register(EquipmentRegistry.PLANTED_C4, PlantedC4Renderer::new);
    }

    /** Client thread: a {@code beep} event for {@code entityId} arrived; starts that bomb's LED flash. */
    public static void beep(int entityId) {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getEntity(entityId) instanceof PlantedC4Entity bomb) {
            LAST_BEEP.put(bomb, level.getGameTime());
        }
    }

    @Override
    public void render(PlantedC4Entity bomb, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers,
                       int light) {
        poses.pushPose();
        poses.mulPose(rotation.rotationY((float) Math.toRadians(180.0F - yaw)));
        CachedGunRendering.renderPlantedC4(poses, buffers, light);
        poses.popPose();
        renderReadout(bomb, partialTick, poses, buffers, light);
        super.render(bomb, yaw, partialTick, poses, buffers, light);
    }

    private void renderReadout(PlantedC4Entity bomb, float partialTick, PoseStack poses, MultiBufferSource buffers,
                               int light) {
        boolean defused = bomb.isDefused();
        int ledColor;
        int ledLight;
        if (defused) {
            ledColor = LED_DEFUSED;
            ledLight = LightTexture.FULL_BRIGHT;
        } else {
            Long beep = LAST_BEEP.get(bomb);
            boolean lit = beep != null
                    && PlantedC4Display.ledLit(bomb.level().getGameTime() - beep + partialTick);
            ledColor = lit ? LED_LIT : LED_DARK;
            ledLight = lit ? LightTexture.FULL_BRIGHT : light;
        }
        Font font = getFont();
        String digits = defused ? null : PlantedC4Display.countdown(bomb.getFuseTicks());
        float ledWidth = font.width(LED);
        float width = digits == null ? ledWidth : ledWidth + GAP + font.width(digits);
        float x = -width / 2.0F;
        poses.pushPose();
        poses.translate(0.0F, READOUT_Y, 0.0F);
        poses.mulPose(entityRenderDispatcher.cameraOrientation());
        poses.scale(READOUT_SCALE, -READOUT_SCALE, READOUT_SCALE);
        Matrix4f matrix = poses.last().pose();
        float y = -font.lineHeight / 2.0F;
        font.drawInBatch(LED, x, y, ledColor, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, ledLight);
        if (digits != null) {
            // No Font background: its quad lands in front of the glyphs in this camera-facing pose and dims them.
            font.drawInBatch(digits, x + ledWidth + GAP, y, DIGITS, false, matrix, buffers, Font.DisplayMode.NORMAL,
                    0, LightTexture.FULL_BRIGHT);
        }
        poses.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(PlantedC4Entity bomb) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
