package dev.csmc.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.csmc.CsmcMod;
import java.util.function.BiFunction;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Render layer for glTF {@code alphaMode: BLEND} primitives.
 *
 * <p>Vanilla {@code entityTranslucent}/{@code entityTranslucentCull} fragment shaders discard
 * fragments with alpha below 0.1, which drops legitimately faint BLEND surfaces. This layer reuses
 * the vanilla entity-translucent vertex stage with a fragment stage that applies texture, vertex
 * color, overlay, lightmap and fog without any alpha test. Depth is tested but not written, so
 * fully transparent fragments never occlude later geometry. Render state otherwise mirrors the
 * vanilla entity translucent types: NEW_ENTITY quads, translucent blending, lightmap, overlay,
 * outline-capable, sorted on upload.
 */
public final class GltfBlendLayer {
    private static final ResourceLocation SHADER_ID = CsmcMod.id("rendertype_gltf_blend");

    /** Owned by Minecraft's GameRenderer; replaced (and the old one closed) on every resource reload. */
    private static volatile ShaderInstance shader;
    private static boolean registered;

    private static final RenderStateShard.ShaderStateShard SHADER_STATE =
            new RenderStateShard.ShaderStateShard(() -> shader);

    private static final BiFunction<ResourceLocation, Boolean, RenderType> TYPES =
            Util.memoize(GltfBlendLayer::create);

    private GltfBlendLayer() {
    }

    /** Registers the core shader with Fabric. Call once during client initialization. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(SHADER_ID, DefaultVertexFormat.NEW_ENTITY, loaded -> shader = loaded));
    }

    /** Cached BLEND layer for {@code texture}; culls back faces unless {@code doubleSided}. */
    public static RenderType get(ResourceLocation texture, boolean doubleSided) {
        return TYPES.apply(texture, doubleSided);
    }

    private static RenderType create(ResourceLocation texture, Boolean doubleSided) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(SHADER_STATE)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(doubleSided ? RenderStateShard.NO_CULL : RenderStateShard.CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .createCompositeState(true);
        return RenderType.create(
                doubleSided ? "csmc_gltf_blend_no_cull" : "csmc_gltf_blend",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                true,
                true,
                state);
    }
}
