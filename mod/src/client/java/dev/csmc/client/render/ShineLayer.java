package dev.csmc.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import dev.csmc.skin.ShineParams;
import dev.csmc.skin.ShineTypeCache;
import dev.csmc.skin.SkinTextureBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Opaque/cutout glTF layer with CS-style specular shine ({@code csmc:rendertype_csmc_shine}), plus the per-finish
 * {@code shine.json} lookup. Per-texel roughness/metalness/wear exposure come from the body's shine mask
 * ({@link SkinTextures#shineMask}, counted in the skin texture budget); the finish's paint scalars and wear are the
 * shader's {@code ShineParams} uniform, set by its render type.
 */
public final class ShineLayer {
    private static final ResourceLocation SHADER_ID = CsmcMod.id("rendertype_csmc_shine");
    /** Read once per weapon/kit; render thread only. An empty value means no shine.json (matte). */
    private static final Map<String, Int2ObjectOpenHashMap<Holder>> FINISHES = new HashMap<>();
    /**
     * Base textures with shine layers kept at once: every resident skin slot (full and thumbnail) plus room for the
     * unskinned weapons' own glTF textures.
     */
    static final int TYPE_TEXTURES = SkinTextureBudget.FULL_VARIANTS + SkinTextureBudget.THUMBNAILS + 128;
    /** Cull/finish/mask/wear combinations kept per base texture; a reused skin slot replaces its oldest. */
    static final int TYPE_VARIANTS = 8;
    /** Render thread only; bounded, and hits build no key, so steady-state frames do not allocate. */
    private static final ShineTypeCache<ResourceLocation, ShineParams, ResourceLocation, RenderType> TYPES =
            new ShineTypeCache<>(TYPE_TEXTURES, TYPE_VARIANTS, ShineLayer::create);
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "csmc-shine-loader");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });

    private static volatile ShaderInstance shader;
    private static boolean registered;
    private static final RenderStateShard.ShaderStateShard SHADER_STATE =
            new RenderStateShard.ShaderStateShard(() -> shader);

    private static final class Holder {
        volatile Optional<ShineParams> value = Optional.empty();
        volatile boolean loaded;
    }

    private ShineLayer() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        IrisCompat.register();
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(SHADER_ID, DefaultVertexFormat.NEW_ENTITY, loaded -> shader = loaded));
    }

    /**
     * Whether shine can draw: false while an Iris shader pack is active, because Iris replaces custom core shaders and
     * the weapon would vanish; callers then use the matte cutout layer.
     */
    public static boolean available() {
        return !IrisCompat.shaderPackInUse();
    }

    /**
     * Drops every cached finish so changed shine.json files are re-read, and every shine render type (reload and
     * disconnect run between frames; layers still held by a mesh stay valid).
     */
    public static void clear() {
        FINISHES.clear();
        TYPES.clear();
    }

    /**
     * Shine of {@code weapon} with {@code paintKit} (0 = unskinned), or {@code null} while loading or when the cache
     * has no shine.json for it. The first request queues a background read; later frames do not allocate.
     */
    public static ShineParams finish(String weapon, int paintKit) {
        Int2ObjectOpenHashMap<Holder> kits = FINISHES.get(weapon);
        if (kits == null) FINISHES.put(weapon, kits = new Int2ObjectOpenHashMap<>());
        Holder holder = kits.get(paintKit);
        if (holder == null) kits.put(paintKit, holder = load(weapon, paintKit));
        return holder.loaded ? holder.value.orElse(null) : null;
    }

    private static Holder load(String weapon, int paintKit) {
        Holder holder = new Holder();
        LOADER.execute(() -> {
            Path file = CachePaths.root().resolve("skins").resolve(weapon)
                    .resolve(paintKit == 0 ? ShineParams.DEFAULT_KIT : Integer.toString(paintKit))
                    .resolve(ShineParams.FILE);
            ShineParams params = null;
            try {
                if (Files.isRegularFile(file)) params = ShineParams.parse(Files.readString(file));
            } catch (IOException e) {
                CsmcMod.LOGGER.warn("[csmc skins] Cannot read {}; finish stays matte", file, e);
            }
            holder.value = Optional.ofNullable(params);
            holder.loaded = true;
        });
        return holder;
    }

    /**
     * Cached shine layer for {@code texture} with the body's {@code mask} bound as {@code Sampler3}; culls back faces
     * unless {@code doubleSided}. {@code wearStep} is the finish's wear in 1/32 steps, or -1 for the unskinned weapon,
     * whose surface comes from the mask alone.
     */
    public static RenderType get(ResourceLocation texture, boolean doubleSided, ShineParams shine, ResourceLocation mask,
                                 int wearStep) {
        return TYPES.get(texture, doubleSided, shine, mask, wearStep);
    }

    private static RenderType create(ResourceLocation texture, boolean doubleSided, ShineParams shine,
                                     ResourceLocation mask, int wearStep) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(SHADER_STATE)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(doubleSided ? RenderStateShard.NO_CULL : RenderStateShard.CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setLayeringState(new ShineUniformShard(shine, mask, wearStep))
                .createCompositeState(true);
        return RenderType.create(
                doubleSided ? "csmc_shine_no_cull" : "csmc_shine",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                true,
                false,
                state);
    }

    /** Layering slot is unused by entity layers; it carries the mask binding and per-finish uniform upload. */
    private static final class ShineUniformShard extends RenderStateShard.LayeringStateShard {
        ShineUniformShard(ShineParams shine, ResourceLocation mask, int wearStep) {
            super("csmc_shine_params", () -> {
                RenderSystem.setShaderTexture(3, mask);
                ShaderInstance current = shader;
                if (current != null) {
                    current.safeGetUniform("ShineParams").set(shine.roughness(), shine.metalness(),
                            shine.pearlescence(), wearStep < 0 ? -1f : wearStep / 32f);
                }
            }, () -> { });
        }
    }
}
