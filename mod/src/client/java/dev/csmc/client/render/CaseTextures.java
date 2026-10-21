package dev.csmc.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.WeaponCaseItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Case item art, per case item {@code csmc:<name>}: the CS2 econ icon exported by {@code tools/cs2_case.py} into
 * {@code <cache>/case/<name>.png}, or the authored {@code csmc:textures/item/<name>.png} placeholder when that export
 * is absent or unreadable. Either image is extruded like a vanilla generated item; the cache is read only on resource
 * reload, never per frame.
 */
public final class CaseTextures implements ClientModInitializer {
    /** Cached icons are cropped to their silhouette and box-filtered to this square pixel-art size. */
    private static final int CACHED_SIZE = 64;
    private static final float FRONT = 8.5f / 16, BACK = 7.5f / 16;
    /** Render-thread scratch for transformed positions and normals, so drawing allocates nothing. */
    private static final Vector3f SCRATCH = new Vector3f();

    /** One case item's art; {@code name} is the item path, cache file stem and placeholder texture name. */
    private static final class Icon {
        final String name;
        final ResourceLocation placeholder;
        final ResourceLocation texture;
        /** Per vertex: x, y, z, u, v, nx, ny, nz; four vertices per quad. */
        float[] mesh;
        RenderType renderType;

        Icon(String name) {
            this.name = name;
            this.placeholder = CsmcMod.id("textures/item/" + name + ".png");
            this.texture = CsmcMod.id("case/" + name);
        }
    }

    @Override
    public void onInitializeClient() {
        List<Icon> icons = new ArrayList<>();
        for (WeaponCaseItem item : List.of(SkinGameplay.WEAPON_CASE, SkinGameplay.INVENTORY_WEAPON_CASE)) {
            Icon icon = new Icon(BuiltInRegistries.ITEM.getKey(item).getPath());
            icons.add(icon);
            BuiltinItemRendererRegistry.INSTANCE.register(item, (BuiltinItemRendererRegistry.DynamicItemRenderer)
                    (stack, context, poses, buffers, light, overlay) -> render(icon, poses, buffers, light, overlay));
        }
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return CsmcMod.id("weapon_case_texture"); }
            @Override public void onResourceManagerReload(ResourceManager manager) {
                for (Icon icon : icons) load(icon, manager);
            }
        });
    }

    private static void load(Icon icon, ResourceManager manager) {
        NativeImage image = cached(icon);
        if (image == null) image = placeholder(icon, manager);
        if (image == null) {
            release(icon);
            return;
        }
        try {
            icon.mesh = extrude(image);
        } catch (RuntimeException e) {
            image.close();
            throw e;
        }
        // Registering under the same id closes the previous texture.
        Minecraft.getInstance().getTextureManager().register(icon.texture, new DynamicTexture(image));
        icon.renderType = RenderType.itemEntityTranslucentCull(icon.texture);
    }

    private static void release(Icon icon) {
        icon.mesh = null;
        icon.renderType = null;
        Minecraft.getInstance().getTextureManager().release(icon.texture);
    }

    private static NativeImage cached(Icon icon) {
        Path file = CachePaths.root().resolve("case").resolve(icon.name + ".png");
        if (!Files.isRegularFile(file)) {
            CsmcMod.LOGGER.info("[csmc case] No exported case icon at {}; using the authored placeholder", file);
            return null;
        }
        try (InputStream input = Files.newInputStream(file); NativeImage source = NativeImage.read(input)) {
            NativeImage fitted = fit(source, CACHED_SIZE);
            CsmcMod.LOGGER.info("[csmc case] Loaded exported case icon {} ({}x{})", file, source.getWidth(), source.getHeight());
            return fitted;
        } catch (IOException | RuntimeException e) {
            CsmcMod.LOGGER.warn("[csmc case] Cannot read {}; using the authored placeholder", file, e);
            return null;
        }
    }

    private static NativeImage placeholder(Icon icon, ResourceManager manager) {
        try (InputStream input = manager.open(icon.placeholder)) {
            NativeImage image = NativeImage.read(input);
            binarizeAlpha(image);
            return image;
        } catch (IOException e) {
            CsmcMod.LOGGER.error("[csmc case] Missing placeholder {}", icon.placeholder, e);
            return null;
        }
    }

    /**
     * Crops to the opaque silhouette, centres it in a square and area-averages premultiplied colour into
     * {@code size}x{@code size}; alpha is then binarized so the extruded edges match the visible pixels.
     */
    private static NativeImage fit(NativeImage source, int size) {
        int width = source.getWidth(), height = source.getHeight();
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if ((source.getPixelRGBA(x, y) >>> 24) > 16) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) throw new IllegalArgumentException("Case icon is fully transparent");
        int side = Math.max(maxX - minX + 1, maxY - minY + 1);
        int originX = minX - (side - (maxX - minX + 1)) / 2, originY = minY - (side - (maxY - minY + 1)) / 2;
        NativeImage out = new NativeImage(size, size, true);
        for (int dy = 0; dy < size; dy++) for (int dx = 0; dx < size; dx++) {
            int x0 = originX + dx * side / size, x1 = originX + (dx + 1) * side / size;
            int y0 = originY + dy * side / size, y1 = originY + (dy + 1) * side / size;
            long r = 0, g = 0, b = 0, a = 0;
            int count = 0;
            for (int y = y0; y < Math.max(y1, y0 + 1); y++) for (int x = x0; x < Math.max(x1, x0 + 1); x++, count++) {
                if (x < 0 || y < 0 || x >= width || y >= height) continue;
                int abgr = source.getPixelRGBA(x, y);
                int alpha = abgr >>> 24;
                a += alpha;
                r += (abgr & 255) * alpha;
                g += ((abgr >>> 8) & 255) * alpha;
                b += ((abgr >>> 16) & 255) * alpha;
            }
            int alpha = (int) (a / count);
            if (alpha < 128 || a == 0) { out.setPixelRGBA(dx, dy, 0); continue; }
            out.setPixelRGBA(dx, dy, 0xff000000 | (int) (b / a) << 16 | (int) (g / a) << 8 | (int) (r / a));
        }
        return out;
    }

    private static void binarizeAlpha(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int abgr = image.getPixelRGBA(x, y);
            image.setPixelRGBA(x, y, (abgr >>> 24) < 128 ? 0 : abgr | 0xff000000);
        }
    }

    /** Front and back faces plus one side strip per opaque-to-transparent pixel edge, in item model space. */
    private static float[] extrude(NativeImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int edges = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!opaque(image, x, y)) continue;
            if (!opaque(image, x, y - 1)) edges++;
            if (!opaque(image, x, y + 1)) edges++;
            if (!opaque(image, x - 1, y)) edges++;
            if (!opaque(image, x + 1, y)) edges++;
        }
        float[] out = new float[(2 + edges) * 32];
        int at = 0;
        at = quad(out, at, 0, 0, 1,
                0, 1, FRONT, 0, 0, 0, 0, FRONT, 0, 1, 1, 0, FRONT, 1, 1, 1, 1, FRONT, 1, 0);
        at = quad(out, at, 0, 0, -1,
                1, 1, BACK, 1, 0, 1, 0, BACK, 1, 1, 0, 0, BACK, 0, 1, 0, 1, BACK, 0, 0);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!opaque(image, x, y)) continue;
            float x0 = (float) x / width, x1 = (float) (x + 1) / width;
            float top = 1 - (float) y / height, bottom = 1 - (float) (y + 1) / height;
            // Sides sample their own pixel's centre, as vanilla's generated item edges do.
            float u = (x + 0.5f) / width, v = (y + 0.5f) / height;
            if (!opaque(image, x, y - 1)) at = quad(out, at, 0, 1, 0,
                    x0, top, BACK, u, v, x0, top, FRONT, u, v, x1, top, FRONT, u, v, x1, top, BACK, u, v);
            if (!opaque(image, x, y + 1)) at = quad(out, at, 0, -1, 0,
                    x0, bottom, FRONT, u, v, x0, bottom, BACK, u, v, x1, bottom, BACK, u, v, x1, bottom, FRONT, u, v);
            if (!opaque(image, x + 1, y)) at = quad(out, at, 1, 0, 0,
                    x1, top, FRONT, u, v, x1, bottom, FRONT, u, v, x1, bottom, BACK, u, v, x1, top, BACK, u, v);
            if (!opaque(image, x - 1, y)) at = quad(out, at, -1, 0, 0,
                    x0, top, BACK, u, v, x0, bottom, BACK, u, v, x0, bottom, FRONT, u, v, x0, top, FRONT, u, v);
        }
        return out;
    }

    private static boolean opaque(NativeImage image, int x, int y) {
        return x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight() && (image.getPixelRGBA(x, y) >>> 24) != 0;
    }

    /** Vertices are counter-clockwise seen from outside, matching the face normal. */
    private static int quad(float[] out, int at, float nx, float ny, float nz, float... vertices) {
        for (int i = 0; i < 4; i++) {
            System.arraycopy(vertices, i * 5, out, at, 5);
            out[at + 5] = nx; out[at + 6] = ny; out[at + 7] = nz;
            at += 8;
        }
        return at;
    }

    private static void render(Icon icon, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        float[] vertices = icon.mesh;
        if (vertices == null) return;
        VertexConsumer output = buffers.getBuffer(icon.renderType);
        PoseStack.Pose pose = poses.last();
        Matrix4f matrix = pose.pose();
        Vector3f scratch = SCRATCH;
        for (int i = 0; i < vertices.length; i += 8) {
            matrix.transformPosition(vertices[i], vertices[i + 1], vertices[i + 2], scratch);
            output.addVertex(scratch.x, scratch.y, scratch.z).setColor(-1)
                    .setUv(vertices[i + 3], vertices[i + 4]).setOverlay(overlay).setLight(light);
            pose.transformNormal(vertices[i + 5], vertices[i + 6], vertices[i + 7], scratch);
            output.setNormal(scratch.x, scratch.y, scratch.z);
        }
    }
}
