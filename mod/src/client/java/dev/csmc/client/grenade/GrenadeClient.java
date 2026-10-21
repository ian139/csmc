package dev.csmc.client.grenade;

import dev.csmc.CsmcMod;
import dev.csmc.grenade.GrenadeCloud;
import dev.csmc.grenade.GrenadeKind;
import dev.csmc.grenade.GrenadeRegistry;
import dev.csmc.network.GrenadeFlashPayload;
import dev.csmc.network.GrenadeSoundPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Client side of throwable grenades: projectile and cloud renderers, flash/smoke screen effects, grenade audio and
 * their lifecycle. Item models go through {@code CachedGunRendering}'s utility path; this class never touches gun
 * state. All state is cleared on world change, disconnect and resource reload.
 */
public final class GrenadeClient implements ClientModInitializer {
    /** Fire particles per pool per tick: bounded regardless of how many pools exist. */
    private static final int MAX_FIRE_PARTICLE_POOLS = 8;
    private static final int FLAME_RECHECK_TICKS = 10;
    private static final RandomSource RANDOM = RandomSource.create();
    private static boolean initialized;
    private static ClientLevel trackedLevel;

    @Override
    public void onInitializeClient() {
        initialize();
    }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        EntityRendererRegistry.register(GrenadeRegistry.PROJECTILE, GrenadeProjectileRenderer::new);
        EntityRendererRegistry.register(GrenadeRegistry.CLOUD, GrenadeCloudRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(GrenadeFlashPayload.TYPE, (payload, context) -> FlashOverlay.receive(payload));
        ClientPlayNetworking.registerGlobalReceiver(GrenadeSoundPayload.TYPE, (payload, context) -> GrenadeSounds.handle(payload));
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> GrenadeEntities.load(entity));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> GrenadeEntities.unload(entity));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            reset();
            // Read the six small sound manifests once per session and start decoding their buffers.
            for (GrenadeKind kind : GrenadeKind.values()) GrenadeSounds.prepare(kind);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(GrenadeClient::reset));
        ClientTickEvents.END_CLIENT_TICK.register(GrenadeClient::tick);
        GrenadeThrowClient.initialize();
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            if (Minecraft.getInstance().level == null) return;
            SmokeView.renderOverlay(graphics);
            FlashOverlay.render(graphics, delta.getGameTimeDeltaPartialTick(false));
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return CsmcMod.id("grenade_client"); }
            @Override public void onResourceManagerReload(ResourceManager manager) {
                GrenadeCloudRenderer.releaseTextures();
                GrenadeSounds.reload();
            }
        });
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level != trackedLevel) {
            // World/dimension change or disconnect: nothing of the previous world may linger.
            reset();
            trackedLevel = level;
            if (level != null) for (var entity : level.entitiesForRendering()) GrenadeEntities.load(entity);
        }
        // Finish releasing ended fire loops even between worlds; their engine bookkeeping outlives the level.
        GrenadeSounds.finishRetiring();
        if (level == null) return;
        if (!client.isPaused()) FlashOverlay.tick();
        GrenadeEntities.tick(level);
        GrenadeSounds.tick();
        revalidateFlames(level);
        if (!client.isPaused()) fireParticles(level);
    }

    /** Every {@value #FLAME_RECHECK_TICKS} ticks, retire flames whose original floor cell is no longer burnable. */
    private static void revalidateFlames(ClientLevel level) {
        if (level.getGameTime() % FLAME_RECHECK_TICKS != 0) return;
        for (GrenadeCloud cloud : GrenadeEntities.CLOUDS) {
            if (cloud.getKind() != GrenadeKind.MOLOTOV && cloud.getKind() != GrenadeKind.INCENDIARY) continue;
            GrenadeCloudRenderer.revalidateFlames(level, cloud, GrenadeEntities.state(cloud));
        }
    }

    private static void reset() {
        GrenadeEntities.clear();
        FlashOverlay.clear();
        SmokeView.clear();
        GrenadeSounds.clear();
        GrenadeThrowClient.reset();
        trackedLevel = null;
    }

    /** A little rising flame and smoke over each nearby pool; capped per tick. */
    private static void fireParticles(ClientLevel level) {
        int pools = 0;
        for (GrenadeCloud cloud : GrenadeEntities.CLOUDS) {
            if (cloud.getKind() != GrenadeKind.MOLOTOV && cloud.getKind() != GrenadeKind.INCENDIARY) continue;
            if (++pools > MAX_FIRE_PARTICLE_POOLS) return;
            GrenadeEntities.CloudState state = GrenadeEntities.state(cloud);
            float age = state.age(level.getGameTime(), 0);
            float life = state.life(age);
            if (life <= 0.05f) continue;
            float radius = state.radius(age);
            for (int i = 0; i < 2; i++) {
                float angle = RANDOM.nextFloat() * Mth.TWO_PI;
                float distance = Mth.sqrt(RANDOM.nextFloat()) * radius * 0.9f;
                double x = cloud.getX() + Mth.cos(angle) * distance;
                double z = cloud.getZ() + Mth.sin(angle) * distance;
                level.addParticle(i == 0 ? ParticleTypes.FLAME : ParticleTypes.SMOKE, x, cloud.getY() + 0.1 + RANDOM.nextFloat() * 0.6, z,
                        0, 0.02 + RANDOM.nextFloat() * 0.03, 0);
            }
        }
    }
}
