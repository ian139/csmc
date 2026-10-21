package dev.csmc.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Albedo-only interpretation of CS2 paint inputs, not Valve's unpublished compositing shader.
 * UV-authored custom/gunsmith artwork is preserved. Projected finishes use seeded UV projection;
 * metallic response, patina chemistry, pearlescence and graph-specific effects cannot be baked into albedo.
 */
public final class SkinCompositor {
    private SkinCompositor() { }

    /** Edge at and above which inputs are always decoded at full resolution (the in-hand texture). */
    static final int FULL_EDGE = 1024;

    /**
     * Decoded inputs keyed by file identity (inode where available), size, mtime and decode subsampling. The exporter
     * hardlinks shared grunge/wear maps and per-weapon color/cavity/masks across kits, so a reel decodes each of those
     * once per resolution, not per variant. Bounded to an eighth of the heap, at most 192 MiB (a 2048² input is 16 MiB).
     */
    private static final WeightedLru<Object, Pixels> INPUTS = new WeightedLru<>(
            Math.min(192L << 20, Runtime.getRuntime().maxMemory() / 8), pixels -> 4L * pixels.pixels.length);

    /**
     * Bytes of decode scratch (ImageIO raster plus int[] copy, ~8 bytes per decoded pixel) that may be in flight at
     * once across all loader threads: a quarter of the heap, at most 256 MiB, in KiB permits. A single larger decode
     * takes the whole budget and runs alone.
     */
    private static final int DECODE_BUDGET_KIB = (int) Math.min(256L << 10, Runtime.getRuntime().maxMemory() / 4 / 1024);
    private static final Semaphore DECODE_BUDGET = new Semaphore(DECODE_BUDGET_KIB, true);

    /** Releases decoded inputs (resource reload). */
    public static void clearInputCache() { INPUTS.clear(); }

    /**
     * Source subsampling for an input decoded for a {@code targetEdge} composite: full resolution for in-hand
     * textures; for smaller targets, the largest integer step that still leaves at least twice the target's texels
     * along the longer edge (headroom for bilinear sampling and pattern scale).
     */
    static int subsample(int width, int height, int targetEdge) {
        if (targetEdge >= FULL_EDGE) return 1;
        return Math.max(1, Math.max(width, height) / (2 * Math.max(1, targetEdge)));
    }

    /** Decoded-input cache key; distinct per subsampling step so thumbnails never evict or alias full decodes. */
    static List<Object> inputKey(Object identity, long size, long modifiedMillis, int subsample) {
        return List.of(identity, size, modifiedMillis, subsample);
    }

    private static Pixels decode(Path path, int targetEdge) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        Object identity = attributes.fileKey() != null ? attributes.fileKey() : path;
        try (ImageInputStream stream = ImageIO.createImageInputStream(path.toFile())) {
            if (stream == null) throw new IOException("Cannot open skin input " + path);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Cannot decode skin input " + path);
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                int step = subsample(width, height, targetEdge);
                var key = inputKey(identity, attributes.size(), attributes.lastModifiedTime().toMillis(), step);
                return INPUTS.get(key, () -> {
                    long decoded = (long) ((width + step - 1) / step) * ((height + step - 1) / step);
                    int permits = (int) Math.max(1, Math.min(DECODE_BUDGET_KIB, decoded * 8 / 1024));
                    DECODE_BUDGET.acquireUninterruptibly(permits);
                    try {
                        ImageReadParam param = reader.getDefaultReadParam();
                        if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                        BufferedImage image = reader.read(0, param);
                        Pixels pixels = new Pixels(image);
                        image.flush();
                        return pixels;
                    } finally {
                        DECODE_BUDGET.release(permits);
                    }
                });
            } finally {
                reader.dispose();
            }
        }
    }

    /** All filesystem reads and pixel work run on the loader, never the render thread. */
    public static BufferedImage compose(Path directory, SkinData skin, int maxEdge) throws IOException {
        JsonObject document = JsonParser.parseString(Files.readString(directory.resolve("params.json"))).getAsJsonObject();
        JsonObject params = object(document, "params");
        JsonObject inputs = object(document, "inputs");
        Map<String, Pixels> images = new HashMap<>();
        Path root = directory.toAbsolutePath().normalize();
        for (var entry : inputs.entrySet()) {
            Path path = root.resolve(entry.getValue().getAsString()).normalize();
            if (!path.startsWith(root)) throw new IOException("Skin texture escapes its pair directory");
            images.put(entry.getKey(), decode(path, maxEdge));
        }
        Pixels base = images.get("color");
        if (base == null) throw new IOException("Skin has no weapon base-color input");
        int limit = Math.max(1, Math.min(1024, maxEdge));
        float factor = Math.min(1f, limit / (float) Math.max(base.width, base.height));
        int width = Math.max(1, Math.round(base.width * factor));
        int height = Math.max(1, Math.round(base.height * factor));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        int style = document.has("effective_style") ? document.get("effective_style").getAsInt() : document.get("style").getAsInt();
        Pixels pattern = images.get("pattern");
        Pixels masks = images.get("masks");
        Pixels cavity = images.get("cavity");
        Pixels wear = images.get("wear");
        Pixels grunge = images.get("grunge");
        Pixels overlay = images.get("overlay");
        int[] colors = new int[4];
        for (int i = 0; i < 4; i++) colors[i] = color(params, "g_vColor" + i, i == 0 ? 0xff808080 : colors[0]);
        Random random = new Random(skin.seed()); // deterministic, intentionally NOT Valve's RNG
        boolean uvArtwork = style == 7 || style == 9;
        float scale = number(params, "g_flPatternTexCoordScale", 1);
        if (!uvArtwork && number(params, "g_bIgnoreWeaponSizeScale", 0) == 0) {
            JsonObject weapon = object(document, "weapon_params");
            scale *= number(weapon, "g_flUvScale1", 1) * number(weapon, "g_flWeaponLength1", 32) / 32f;
        }
        Transform patternTransform = new Transform(uvArtwork ? 1 : scale,
                uvArtwork ? 0 : roll(document, "g_flPatternTexCoordRotation", 0, random, 0, 360),
                uvArtwork ? 0 : roll(document, "g_vPatternTexCoordOffset", 0, random, 0, 1),
                uvArtwork ? 0 : roll(document, "g_vPatternTexCoordOffset", 1, random, 0, 1));
        Transform wearTransform = new Transform(roll(document, "g_flWearTexCoordScale", 0, random, 1.6f, 1.8f),
                roll(document, "g_flWearTexCoordRotation", 0, random, 0, 360), random.nextFloat(), random.nextFloat());
        Transform grungeTransform = new Transform(roll(document, "g_flGrungeTexCoordScale", 0, random, 1.6f, 1.8f),
                roll(document, "g_flGrungeTexCoordRotation", 0, random, 0, 360), random.nextFloat(), random.nextFloat());
        for (int y = 0, index = 0; y < height; y++) {
            float v = (y + .5f) / height;
            for (int x = 0; x < width; x++, index++) {
                float u = (x + .5f) / width;
                int original = base.sample(u, v, false);
                int mask = masks == null ? 0 : masks.sample(u, v, false);
                int art = pattern == null ? 0xffffffff : patternTransform.sample(pattern, u, v);
                int ao = cavity == null ? 0xffffffff : cavity.sample(u, v, false);
                int painted;
                if (style == 7 || style == 9) {
                    // Pattern alpha encodes finish durability/material, not transparent paint coverage.
                    painted = art | 0xff000000;
                } else if (style == 4) {
                    painted = metallic(colors[0], original);
                } else if (style == 8 || style == 10) {
                    // Closest base-color fallback: colored metal pattern. No fake metallic highlights.
                    painted = metallic(art, original);
                } else {
                    int palette = palette(colors, style == 1 ? mask : art);
                    painted = style == 5 || style == 6 ? metallic(palette, original) : palette;
                    if (style == 2 || style == 3) {
                        // Masked untouched areas retain the kit's base coat; mask channel red selects painted regions.
                        painted = blend(colors[0], painted, channel(mask, 16));
                    }
                }
                if (overlay != null) {
                    int ink = patternTransform.sample(overlay, u, v);
                    float strength = number(params, "g_fOverlayStrength", 1) * channel(ink, 24);
                    painted = blend(painted, ink, strength);
                }
                float visibility = 1;
                if (wear != null) {
                    // CS2 customweapon wear shape: scratches only bite where the weapon's sRGB cavity map marks
                    // exposed edges, and the wear float scales them by (1 + 6 * wear) against a fixed threshold.
                    float exposure = channel(wearTransform.sample(wear, u, v), 16) * LINEAR[(ao >>> 16) & 255]
                            * (1 + 6 * skin.wear());
                    // Custom paint alpha below 128 raises durability: the darker, the more durable (0 never wears).
                    if (style == 7) exposure *= Math.min(1, ((art >>> 24) & 255) / 128f);
                    visibility = 1 - smoothstep(.58f, .68f, exposure);
                }
                float shade = .55f + .45f * channel(ao, 8);
                if (grunge != null && skin.wear() > 0) {
                    float dirt = channel(grungeTransform.sample(grunge, u, v), 8);
                    shade *= 1 - .22f * skin.wear() * (1 - dirt);
                }
                painted = multiply(painted, shade);
                // Source base alpha packs material data; it is not opacity on the painted weapon atlas.
                pixels[index] = 0xff000000 | (blend(original, painted, visibility) & 0xffffff);
            }
        }
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }

    private static int palette(int[] colors, int mask) {
        int out = blend(colors[0], colors[1], channel(mask, 16));
        out = blend(out, colors[2], channel(mask, 8));
        return blend(out, colors[3], channel(mask, 0));
    }

    private static int metallic(int tint, int base) {
        // Preserve the weapon's real microdetail; metalness/roughness remain outside this albedo renderer.
        float light = .55f + .45f * Math.max(channel(base, 16), Math.max(channel(base, 8), channel(base, 0)));
        return multiply(tint, light);
    }

    private static int multiply(int rgb, float factor) {
        return 0xff000000 | (Math.min(255, Math.round(((rgb >>> 16) & 255) * factor)) << 16)
                | (Math.min(255, Math.round(((rgb >>> 8) & 255) * factor)) << 8)
                | Math.min(255, Math.round((rgb & 255) * factor));
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = Math.round(((a >>> 16) & 255) * (1 - t) + ((b >>> 16) & 255) * t);
        int g = Math.round(((a >>> 8) & 255) * (1 - t) + ((b >>> 8) & 255) * t);
        int blue = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        int alpha = Math.round(((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        return (alpha << 24) | (r << 16) | (g << 8) | blue;
    }

    private static float channel(int pixel, int shift) { return ((pixel >>> shift) & 255) / 255f; }
    /** sRGB-encoded 8-bit values to linear, as the GPU decodes an sRGB-read texture. */
    private static final float[] LINEAR = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            float srgb = i / 255f;
            LINEAR[i] = srgb <= .04045f ? srgb / 12.92f : (float) Math.pow((srgb + .055f) / 1.055f, 2.4f);
        }
    }
    private static float smoothstep(float low, float high, float x) {
        float t = Math.max(0, Math.min(1, (x - low) / (high - low)));
        return t * t * (3 - 2 * t);
    }
    private static JsonObject object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
    }
    private static float number(JsonObject object, String key, float fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean() ? 1 : 0;
        return value.getAsFloat();
    }
    private static int color(JsonObject params, String key, int fallback) {
        if (!params.has(key) || !params.get(key).isJsonArray()) return fallback;
        JsonArray array = params.getAsJsonArray(key);
        if (array.size() < 3) return fallback;
        return 0xff000000 | (Math.round(array.get(0).getAsFloat() * 255) << 16)
                | (Math.round(array.get(1).getAsFloat() * 255) << 8) | Math.round(array.get(2).getAsFloat() * 255);
    }
    private static float roll(JsonObject document, String key, int axis, Random rng, float low, float high) {
        JsonObject ranges = object(document, "bounds");
        if (ranges.has(key)) {
            JsonObject range = ranges.getAsJsonObject(key);
            JsonArray min = range.getAsJsonArray("min"), max = range.getAsJsonArray("max");
            if (axis < min.size()) { low = min.get(axis).getAsFloat(); high = max.get(axis).getAsFloat(); }
        }
        return low + (high - low) * rng.nextFloat();
    }

    private record Transform(float scale, float cosine, float sine, float offsetX, float offsetY) {
        Transform(float scale, float degrees, float x, float y) {
            this(scale, (float) Math.cos(Math.toRadians(degrees)), (float) Math.sin(Math.toRadians(degrees)), x, y);
        }
        int sample(Pixels pixels, float u, float v) {
            float x = (u - .5f) * scale, y = (v - .5f) * scale;
            return pixels.sample(x * cosine - y * sine + .5f + offsetX, x * sine + y * cosine + .5f + offsetY, true);
        }
    }

    private static final class Pixels {
        final int width, height;
        final int[] pixels;
        Pixels(BufferedImage image) {
            width = image.getWidth(); height = image.getHeight();
            pixels = image.getRGB(0, 0, width, height, null, 0, width);
        }
        int sample(float u, float v, boolean wrap) {
            if (wrap) { u -= (float) Math.floor(u); v -= (float) Math.floor(v); }
            float x = Math.max(0, Math.min(width - 1, u * width - .5f));
            float y = Math.max(0, Math.min(height - 1, v * height - .5f));
            int x0 = (int) x, y0 = (int) y;
            int x1 = Math.min(width - 1, x0 + 1), y1 = Math.min(height - 1, y0 + 1);
            return blend(blend(pixels[y0 * width + x0], pixels[y0 * width + x1], x - x0),
                    blend(pixels[y1 * width + x0], pixels[y1 * width + x1], x - x0), y - y0);
        }
    }
}
