package dev.csmc.client.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.csmc.CsmcMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Draws the kevlar vest and helmet as authored 3D plate-carrier / combat-helmet models instead of flat armor layers.
 *
 * <p>The geometry is ordinary vanilla block-model JSON ({@code models/item/kevlar_*_worn.json}, openable in
 * Blockbench) in the model-part space of the body (vest) and head (helmet): the quads are baked by the game's
 * model loader, then drawn through the armor render type attached to {@link HumanoidModel#body} or
 * {@link HumanoidModel#head}. Anything else that wears the items (zombies, armor stands, other players, the
 * inventory preview) goes through the same {@link ArmorRenderer}.
 */
public final class KevlarArmorClient implements ClientModInitializer {
    private static final ResourceLocation VEST_MODEL = CsmcMod.id("item/kevlar_vest_worn");
    private static final ResourceLocation HELMET_MODEL = CsmcMod.id("item/kevlar_helmet_worn");
    /**
     * The JSON models are authored with block-model axes (x east, y up, front north) inside a 16-unit cell. A model
     * part's space has y pointing down and x mirrored, so the quads are turned half a revolution about z; the
     * translation puts the cell centre (8, pivot, 8) onto the part origin. The body part's origin is at the top of
     * the torso (cell y 14), the head part's at the neck (cell y 4).
     */
    private static final float BODY_PIVOT = 14.0F / 16.0F;
    private static final float HEAD_PIVOT = 4.0F / 16.0F;
    /**
     * Vanilla draws a baby humanoid's head scaled by 1.5 / 2 and lifted 16 px, and its body scaled by 1 / 2 and lifted
     * 24 px ({@code HumanoidModel} passes those values to {@code AgeableListModel}); the armor must follow, since the
     * renderer bypasses {@code AgeableListModel.renderToBuffer}.
     */
    private static final float YOUNG_HEAD_SCALE = 0.75F;
    private static final float YOUNG_HEAD_LIFT = 16.0F / 16.0F;
    private static final float YOUNG_BODY_SCALE = 0.5F;
    private static final float YOUNG_BODY_LIFT = 24.0F / 16.0F;
    private static final Direction[] SIDES = Direction.values();
    /** Only touched on the render thread; reseeded before every enumeration so quad order is deterministic. */
    private static final RandomSource RANDOM = RandomSource.create(42L);

    @Override
    public void onInitializeClient() {
        ModelLoadingPlugin.register(context -> context.addModels(VEST_MODEL, HELMET_MODEL));
        ArmorRenderer.register(KevlarArmorClient::render, item("kevlar_vest"), item("kevlar_helmet"));
    }

    private static Item item(String path) {
        ResourceLocation id = CsmcMod.id(path);
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) throw new IllegalStateException(id + " is not registered");
        return item;
    }

    private static void render(PoseStack poses, MultiBufferSource buffers, ItemStack stack, LivingEntity entity,
            EquipmentSlot slot, int light, HumanoidModel<LivingEntity> model) {
        boolean head = slot == EquipmentSlot.HEAD;
        ModelPart part = head ? model.head : model.body;
        BakedModel baked = Minecraft.getInstance().getModelManager().getModel(head ? HELMET_MODEL : VEST_MODEL);
        VertexConsumer out = ItemRenderer.getArmorFoilBuffer(buffers,
                RenderType.armorCutoutNoCull(TextureAtlas.LOCATION_BLOCKS), stack.hasFoil());
        poses.pushPose();
        if (model.young) {
            float scale = head ? YOUNG_HEAD_SCALE : YOUNG_BODY_SCALE;
            poses.scale(scale, scale, scale);
            poses.translate(0.0F, head ? YOUNG_HEAD_LIFT : YOUNG_BODY_LIFT, 0.0F);
        }
        part.translateAndRotate(poses);
        poses.translate(0.5F, head ? HEAD_PIVOT : BODY_PIVOT, -0.5F);
        poses.scale(-1.0F, -1.0F, 1.0F);
        RANDOM.setSeed(42L);
        for (Direction side : SIDES) draw(poses, out, baked, side, RANDOM, light);
        draw(poses, out, baked, null, RANDOM, light);
        poses.popPose();
    }

    private static void draw(PoseStack poses, VertexConsumer out, BakedModel baked, Direction side, RandomSource random,
            int light) {
        for (var quad : baked.getQuads(null, side, random)) {
            out.putBulkData(poses.last(), quad, 1.0F, 1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY);
        }
    }
}
