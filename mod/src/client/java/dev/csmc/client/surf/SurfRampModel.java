package dev.csmc.client.surf;

import dev.csmc.surf.RampGeometry;
import dev.csmc.surf.SurfRamps;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Surf ramp block/item model. Emits the exact boundary polygons of the cell's solid (from the block entity's render
 * data) through the Fabric Renderer API, each polygon split into a fan of planar quads. Textures are projected with
 * {@link MutableQuadView#BAKE_LOCK_UV} onto the polygon's nominal (nearest axis) face, so sloped faces tile
 * continuously across cells like vanilla UV lock. Only polygons covering a whole cell face cull against neighbours.
 * Uses the vanilla smooth stone texture; item display transforms come from vanilla {@code block/block}.
 */
final class SurfRampModel implements UnbakedModel, BakedModel, FabricBakedModel {
    static final ResourceLocation BLOCK_MODEL = ResourceLocation.fromNamespaceAndPath("csmc", "block/surf_ramp");
    static final ResourceLocation ITEM_MODEL = ResourceLocation.fromNamespaceAndPath("csmc", "item/surf_ramp");
    private static final ResourceLocation TRANSFORM_PARENT = ResourceLocation.withDefaultNamespace("block/block");
    private static final Material TEXTURE = new Material(InventoryMenu.BLOCK_ATLAS,
            ResourceLocation.withDefaultNamespace("block/smooth_stone"));

    private UnbakedModel transformParent;
    private TextureAtlasSprite sprite;
    /** Cell-face polygons: default material (vanilla smooth lighting). */
    private RenderMaterial axisMaterial;
    /**
     * Sloped polygons: no ambient occlusion. Smooth lighting samples the cube corners of the cells a face crosses, so a
     * plane spanning many cells would show per-cell light bands; flat lighting plus diffuse shading keeps it smooth.
     */
    private RenderMaterial slopeMaterial;
    private ItemTransforms transforms = ItemTransforms.NO_TRANSFORMS;

    @Override
    public Collection<ResourceLocation> getDependencies() {
        return List.of(TRANSFORM_PARENT);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> resolver) {
        transformParent = resolver.apply(TRANSFORM_PARENT);
        transformParent.resolveParents(resolver);
    }

    @Override
    public BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> sprites, ModelState state) {
        sprite = sprites.apply(TEXTURE);
        var finder = RendererAccess.INSTANCE.getRenderer().materialFinder();
        axisMaterial = finder.clear().find();
        slopeMaterial = finder.clear().ambientOcclusion(TriState.FALSE).find();
        if (transformParent instanceof BlockModel blockModel) transforms = blockModel.getTransforms();
        return this;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos,
            Supplier<RandomSource> random, RenderContext context) {
        Object data = level.getBlockEntityRenderData(pos);
        RampGeometry geometry = data instanceof RampGeometry g ? g : SurfRamps.defaultGeometry();
        emit(geometry, context.getEmitter());
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<RandomSource> random, RenderContext context) {
        emit(SurfRamps.defaultGeometry(), context.getEmitter());
    }

    private void emit(RampGeometry geometry, QuadEmitter emitter) {
        for (RampGeometry.Polygon polygon : geometry.polygons()) {
            List<float[]> vs = polygon.vertices();
            // Fan of quads (v0, vi, vi+1, vi+2); a trailing triangle repeats its last vertex.
            for (int i = 1; i + 1 < vs.size(); i += 2) {
                float[] a = vs.get(0), b = vs.get(i), c = vs.get(i + 1);
                float[] d = i + 2 < vs.size() ? vs.get(i + 2) : c;
                emitter.pos(0, a[0], a[1], a[2]).pos(1, b[0], b[1], b[2]).pos(2, c[0], c[1], c[2])
                        .pos(3, d[0], d[1], d[2]);
                // cullFace() also overwrites the nominal face, so it must come first; otherwise sloped polygons
                // (cullFace null) lose their nominal face, BAKE_LOCK_UV is skipped and every vertex samples one texel.
                emitter.cullFace(polygon.cullFace());
                emitter.nominalFace(polygon.nominalFace());
                emitter.color(-1, -1, -1, -1);
                emitter.material(polygon.isAxisAligned() ? axisMaterial : slopeMaterial);
                emitter.spriteBake(sprite, MutableQuadView.BAKE_LOCK_UV | MutableQuadView.BAKE_NORMALIZED);
                emitter.emit();
            }
        }
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
        return List.of();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return sprite;
    }

    @Override
    public ItemTransforms getTransforms() {
        return transforms;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
