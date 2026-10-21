package dev.csmc.client.grenade;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.csmc.CsmcMod;
import dev.csmc.grenade.GrenadeCloud;
import dev.csmc.grenade.GrenadeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Smoke and fire volumes. Smoke is a layered volume of {@value GrenadeEntities#SMOKE_PUFFS} large, dense, soft
 * camera-facing sprites distributed through the synced cylinder; their translucent layers are depth-sorted by the
 * vanilla buffer and overlap many times along any line of sight through the core, so players and terrain behind or
 * inside it are hidden. Being inside is handled by fog/overlay ({@link SmokeView}). Fire pools draw a bounded set of
 * camera-yaw-facing vanilla flame sprites over the pool disk.
 *
 * <p>The single puff texture is generated once (64×64) and released on resource reload; nothing is allocated or read
 * from disk per frame. Culling uses the real volume instead of the small entity hitbox.
 */
final class GrenadeCloudRenderer extends EntityRenderer<GrenadeCloud> {
    static final ResourceLocation PUFF = CsmcMod.id("grenade/smoke_puff");
    private static final int PUFF_SIZE = 64;
    private static final float SMOKE_ALPHA = 0.92f;
    private static boolean puffUploaded;
    private static final Vector3f RIGHT = new Vector3f();
    private static final BlockPos.MutableBlockPos CELL = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos BELOW = new BlockPos.MutableBlockPos();
    private static final Vector3f UP = new Vector3f();
    private static final Quaternionf CAMERA = new Quaternionf();
    private static final int[] FLOOR_SEARCH = {0, 1, -1};

    GrenadeCloudRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    /** Frees the generated puff texture; it is regenerated lazily on the next smoke frame. */
    static void releaseTextures() {
        if (puffUploaded) Minecraft.getInstance().getTextureManager().release(PUFF);
        puffUploaded = false;
    }

    @Override
    public boolean shouldRender(GrenadeCloud cloud, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
        // The entity hitbox is tiny; cull and distance-test against the logical volume instead.
        float reach = GrenadeEntities.maxRadius(cloud) + (cloud.getKind() == GrenadeKind.SMOKE ? 2.2f : 1.0f);
        float top = GrenadeEntities.height(cloud.getKind()) + 2.0f;
        double dx = cloud.getX() - cameraX;
        double dz = cloud.getZ() - cameraZ;
        double far = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0 + reach;
        if (dx * dx + dz * dz > far * far) return false;
        return frustum.isVisible(new AABB(cloud.getX() - reach, cloud.getY() - 0.5, cloud.getZ() - reach,
                cloud.getX() + reach, cloud.getY() + top, cloud.getZ() + reach));
    }

    @Override
    public void render(GrenadeCloud cloud, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        GrenadeEntities.CloudState state = GrenadeEntities.state(cloud);
        float age = state.age(level.getGameTime(), partialTick);
        float life = state.life(age);
        if (life <= 0.001f) return;
        if (cloud.getKind() == GrenadeKind.SMOKE) renderSmoke(cloud, state, age, life, poses, buffers, light);
        else renderFire(cloud, state, age, life, partialTick, poses, buffers);
    }

    private void renderSmoke(GrenadeCloud cloud, GrenadeEntities.CloudState state, float age, float life,
                             PoseStack poses, MultiBufferSource buffers, int light) {
        ensurePuffTexture();
        float growth = state.growth(age);
        float radius = state.radius(age);
        float height = GrenadeEntities.height(GrenadeKind.SMOKE);
        float column = (height - 1.0f) * (0.3f + 0.7f * growth);
        float scale = 0.3f + 0.7f * growth;
        CAMERA.set(entityRenderDispatcher.cameraOrientation());
        CAMERA.transform(RIGHT.set(1, 0, 0));
        CAMERA.transform(UP.set(0, 1, 0));
        PoseStack.Pose pose = poses.last();
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(PUFF));
        float alpha = SMOKE_ALPHA * life;
        for (int i = 0; i < state.rho.length; i++) {
            float angle = state.theta[i] + age * state.drift[i];
            float distance = state.rho[i] * radius;
            float x = Mth.cos(angle) * distance;
            float z = Mth.sin(angle) * distance;
            float y = 0.45f + state.lift[i] * column + 0.08f * Mth.sin(age * 0.05f + state.spin[i]);
            float half = state.size[i] * scale;
            // Slight per-puff grey variation reads as volume rather than a flat sheet.
            float shade = 0.66f + 0.1f * state.lift[i];
            float spin = state.spin[i] + age * state.drift[i] * 0.5f;
            puff(consumer, pose, x, y, z, half, spin, shade, alpha, light);
        }
    }

    private static void puff(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float half,
                             float spin, float shade, float alpha, int light) {
        float cos = Mth.cos(spin) * half;
        float sin = Mth.sin(spin) * half;
        // Rotated in the camera plane: corner = c ± (cos·R + sin·U) ± (−sin·R + cos·U).
        float ax = RIGHT.x * cos + UP.x * sin, ay = RIGHT.y * cos + UP.y * sin, az = RIGHT.z * cos + UP.z * sin;
        float bx = -RIGHT.x * sin + UP.x * cos, by = -RIGHT.y * sin + UP.y * cos, bz = -RIGHT.z * sin + UP.z * cos;
        vertex(consumer, pose, x - ax - bx, y - ay - by, z - az - bz, 0, 1, shade, alpha, light);
        vertex(consumer, pose, x + ax - bx, y + ay - by, z + az - bz, 1, 1, shade, alpha, light);
        vertex(consumer, pose, x + ax + bx, y + ay + by, z + az + bz, 1, 0, shade, alpha, light);
        vertex(consumer, pose, x - ax + bx, y - ay + by, z - az + bz, 0, 0, shade, alpha, light);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               float shade, float alpha, int light) {
        consumer.addVertex(pose, x, y, z).setColor(shade, shade, shade * 1.03f, alpha).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
    }

    private void renderFire(GrenadeCloud cloud, GrenadeEntities.CloudState state, float age, float life, float partialTick,
                            PoseStack poses, MultiBufferSource buffers) {
        ClientLevel level = Minecraft.getInstance().level;
        if (state.floor == null) placeFlames(level, cloud, state);
        TextureAtlasSprite fire0 = ModelBakery.FIRE_0.sprite();
        TextureAtlasSprite fire1 = ModelBakery.FIRE_1.sprite();
        float radius = state.radius(age);
        Vec3 camera = entityRenderDispatcher.camera.getPosition();
        double cx = camera.x - cloud.getX();
        double cz = camera.z - cloud.getZ();
        PoseStack.Pose pose = poses.last();
        VertexConsumer consumer = buffers.getBuffer(Sheets.cutoutBlockSheet());
        boolean incendiary = cloud.getKind() == GrenadeKind.INCENDIARY;
        for (int i = 0; i < state.rho.length; i++) {
            if (!state.placed[i]) continue;
            float distance = state.rho[i] * radius;
            float x = Mth.cos(state.theta[i]) * distance;
            float z = Mth.sin(state.theta[i]) * distance;
            float y = state.floor[i];
            // The server clears burning cells inside smoke; hide flames there rather than drawing through the cloud.
            if (GrenadeEntities.smokeAt(level, cloud.getX() + x, cloud.getY() + y + 0.3, cloud.getZ() + z, partialTick) > 0.5f) continue;
            float flicker = 0.85f + 0.15f * Mth.sin(age * 0.7f + state.spin[i]) + 0.05f * Mth.sin(age * 1.9f + state.drift[i] * 7);
            float height = (0.9f + state.lift[i] * 0.6f) * flicker * life;
            if (height < 0.02f) continue;
            float half = state.size[i] * (0.6f + 0.4f * life);
            // Face the camera about the vertical axis, like vanilla's burning-entity flames. Right = up × toCamera,
            // so the quad's counter-clockwise winding (bottom-left, bottom-right, top-right, top-left) faces the
            // camera and survives back-face culling.
            float fx = (float) (cx - x);
            float fz = (float) (cz - z);
            float length = Mth.sqrt(fx * fx + fz * fz);
            float rx = length < 1e-4f ? 1 : fz / length;
            float rz = length < 1e-4f ? 0 : -fx / length;
            TextureAtlasSprite sprite = i % 2 == 0 ? fire0 : fire1;
            float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
            if (i % 4 >= 2) {
                float swap = u0;
                u0 = u1;
                u1 = swap;
            }
            int color = incendiary ? 0xFFFFE8D8 : 0xFFFFFFFF;
            flame(consumer, pose, x - rx * half, y, z - rz * half, u1, v1, color);
            flame(consumer, pose, x + rx * half, y, z + rz * half, u0, v1, color);
            flame(consumer, pose, x + rx * half, y + height, z + rz * half, u0, v0, color);
            flame(consumer, pose, x - rx * half, y + height, z - rz * half, u1, v0, color);
        }
    }

    /**
     * Once per pool, on its first frame: put each flame on the nearest walkable floor within one block of the pool
     * origin, and drop flames over walls, fluids or drops. This approximates the server's flood-filled burning cells,
     * which are not synced. Chosen cells never move afterwards; {@link #revalidateFlames} only retires them.
     */
    private static void placeFlames(ClientLevel level, GrenadeCloud cloud, GrenadeEntities.CloudState state) {
        int count = state.rho.length;
        state.floor = new float[count];
        state.placed = new boolean[count];
        float radius = GrenadeEntities.maxRadius(cloud);
        int baseY = Mth.floor(cloud.getY() + 1e-3);
        for (int i = 0; i < count; i++) {
            int x = Mth.floor(cloud.getX() + Mth.cos(state.theta[i]) * state.rho[i] * radius);
            int z = Mth.floor(cloud.getZ() + Mth.sin(state.theta[i]) * state.rho[i] * radius);
            for (int dy : FLOOR_SEARCH) {
                if (burnable(level, x, baseY + dy, z)) {
                    state.floor[i] = (float) (baseY + dy - cloud.getY());
                    state.placed[i] = true;
                    break;
                }
            }
        }
    }

    /**
     * Rechecks each still-placed flame's originally chosen cell and permanently retires flames whose cell stopped
     * being burnable (floor removed, cell filled or flooded). Flames are never relocated or revived. Pools that have
     * not been rendered yet are skipped (they are placed on their first frame). No allocation.
     */
    static void revalidateFlames(ClientLevel level, GrenadeCloud cloud, GrenadeEntities.CloudState state) {
        if (state.floor == null) return;
        float radius = GrenadeEntities.maxRadius(cloud);
        for (int i = 0; i < state.placed.length; i++) {
            if (!state.placed[i]) continue;
            int x = Mth.floor(cloud.getX() + Mth.cos(state.theta[i]) * state.rho[i] * radius);
            int y = Math.round((float) (cloud.getY() + state.floor[i]));
            int z = Mth.floor(cloud.getZ() + Mth.sin(state.theta[i]) * state.rho[i] * radius);
            if (!burnable(level, x, y, z)) state.placed[i] = false;
        }
    }

    /** Free, dry cell standing on a sturdy floor; uses shared mutable positions (client thread only). */
    private static boolean burnable(ClientLevel level, int x, int y, int z) {
        CELL.set(x, y, z);
        BELOW.set(x, y - 1, z);
        return level.getBlockState(CELL).getCollisionShape(level, CELL).isEmpty()
                && level.getFluidState(CELL).isEmpty()
                && level.getBlockState(BELOW).isFaceSturdy(level, BELOW, Direction.UP);
    }

    private static void flame(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int color) {
        consumer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    @Override
    public ResourceLocation getTextureLocation(GrenadeCloud cloud) {
        return PUFF;
    }

    /** One-time soft radial puff with value-noise edges; uploaded on the render thread. */
    private static void ensurePuffTexture() {
        if (puffUploaded) return;
        NativeImage image = new NativeImage(PUFF_SIZE, PUFF_SIZE, false);
        for (int y = 0; y < PUFF_SIZE; y++) {
            for (int x = 0; x < PUFF_SIZE; x++) {
                float u = (x + 0.5f) / PUFF_SIZE * 2 - 1;
                float v = (y + 0.5f) / PUFF_SIZE * 2 - 1;
                float r = Mth.sqrt(u * u + v * v);
                float noise = 0.6f * noise(u * 3, v * 3, 11) + 0.4f * noise(u * 7, v * 7, 23);
                // Solid core out to ~45% radius, billowy noisy falloff to the edge.
                float edge = r + (noise - 0.5f) * 0.35f;
                float alpha = 1 - GrenadeEntities.smoothstep(0.45f, 1.0f, edge);
                int a = Mth.clamp(Math.round(alpha * 255), 0, 255);
                int grey = Mth.clamp(Math.round((0.86f + 0.14f * noise) * 255), 0, 255);
                image.setPixelRGBA(x, y, a << 24 | grey << 16 | grey << 8 | grey);
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        Minecraft.getInstance().getTextureManager().register(PUFF, texture);
        puffUploaded = true;
    }

    /** Smooth value noise in 0..1. */
    private static float noise(float x, float y, int seed) {
        int ix = Mth.floor(x);
        int iy = Mth.floor(y);
        float fx = x - ix;
        float fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(ix, iy, seed), b = hash(ix + 1, iy, seed), c = hash(ix, iy + 1, seed), d = hash(ix + 1, iy + 1, seed);
        return Mth.lerp(fy, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 2147483629;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }
}
