package dev.csmc.client.grenade;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.grenade.GrenadeProjectile;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Quaternionf;

/**
 * Thrown grenade: the synced grenade ItemStack (cached CS2 glTF through the item pipeline, else the vanilla icon)
 * drawn with a physical world-space orientation that rolls with its observed motion and settles on its side, rather
 * than a camera-facing billboard.
 */
final class GrenadeProjectileRenderer extends EntityRenderer<GrenadeProjectile> {
    /** Like vanilla thrown items: skip the first ticks right in front of the thrower's camera. */
    private static final double NEAR_CAMERA_SQR = 12.25;
    private final ItemRenderer items;
    private final Quaternionf orientation = new Quaternionf();

    GrenadeProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
        items = context.getItemRenderer();
        shadowRadius = 0.12f;
    }

    @Override
    public void render(GrenadeProjectile projectile, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        if (projectile.tickCount < 2 && entityRenderDispatcher.camera.getEntity().distanceToSqr(projectile) < NEAR_CAMERA_SQR) return;
        GrenadeEntities.Tumble tumble = GrenadeEntities.tumbleOf(projectile);
        if (tumble != null) orientation.set(tumble.previous).slerp(tumble.current, partialTick);
        else orientation.identity();
        poses.pushPose();
        poses.translate(0, projectile.getBbHeight() * 0.5f, 0);
        poses.mulPose(orientation);
        items.renderStatic(projectile.getItem(), ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY, poses, buffers,
                projectile.level(), projectile.getId());
        poses.popPose();
        super.render(projectile, yaw, partialTick, poses, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(GrenadeProjectile projectile) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
