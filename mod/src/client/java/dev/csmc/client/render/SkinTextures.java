package dev.csmc.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import dev.csmc.skin.CompositeCache;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinCompositor;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinNames;
import dev.csmc.skin.SkinResolution;
import dev.csmc.skin.SkinTextureBudget;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Render-thread slots in two independent pools: 1024 px in-hand textures and 256 px GUI thumbnails, so reel and
 * picker tiles never evict a held gun's texture. Disk reads, compositing and native pixel conversion run on low
 * priority loader threads; GPU uploads happen between frames on the render thread, a bounded number per tick.
 * Thumbnails are also persisted in {@link CompositeCache}, so repeat reels load them without compositing.
 */
final class SkinTextures {
    /** Full-resolution variants held at once ({@link SkinTextureBudget#FULL_VARIANTS}). */
    static final int MAX_VARIANTS = SkinTextureBudget.FULL_VARIANTS;
    /** Thumbnail variants held at once ({@link SkinTextureBudget#THUMBNAILS}). */
    static final int MAX_THUMBNAILS = SkinTextureBudget.THUMBNAILS;
    /** Persisted thumbnails kept on disk (~100-200 KiB each); older entries are pruned once per session. */
    private static final long DISK_CACHE_BYTES = 256L << 20;
    /** Baked glove finish albedo in {@code skins/<glove_id>/<kit>/} (exporter's {@code glove_textures.color}). */
    private static final String GLOVE_COLOR = "color.png";
    private static final Pool FULL = new Pool(SkinResolution.FULL, MAX_VARIANTS, "cached_skin/", 2, false,
            loader("csmc-skin-loader", 2));
    // Two workers: decode scratch is also bounded inside SkinCompositor, so a 512 MiB client cannot run out of heap.
    private static final Pool THUMBNAILS = new Pool(SkinResolution.THUMBNAIL, MAX_THUMBNAILS, "cached_skin_thumb/", 16,
            true, loader("csmc-skin-thumbnail", Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1))));
    private static final ShineMasks MASKS = new ShineMasks(FULL.executor);

    static {
        THUMBNAILS.executor.execute(() -> CompositeCache.prune(CachePaths.root().resolve("skins"), DISK_CACHE_BYTES));
    }

    private static ExecutorService loader(String name, int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        });
    }

    /** Whether a finish needs the gun's legacy body; knives have a single body that takes every finish. */
    static boolean legacy(String weapon, SkinData skin) {
        if (skin == null || skin.paintKit() == 0 || dev.csmc.CsmcMod.KNIVES.containsKey(weapon)) return false;
        var kit = SkinCatalog.renderKit(skin.paintKit());
        return kit != null && kit.supports(weapon) && kit.legacyModel();
    }

    /** Null means default albedo while the new variant is pending or unavailable. No per-frame I/O. */
    static ResourceLocation texture(String weapon, SkinData skin, SkinResolution resolution) {
        return (resolution == SkinResolution.THUMBNAIL ? THUMBNAILS : FULL).texture(weapon, skin);
    }

    static void tick() {
        FULL.tick();
        THUMBNAILS.tick();
        MASKS.tick();
    }

    /**
     * Shine mask of one weapon body ({@code skins/<weapon>/_default/shine_mask_<meshSet>.png}), or null while it
     * loads or when the cache has none. Counted in {@link SkinTextureBudget}; no per-frame I/O or allocation.
     */
    static ResourceLocation shineMask(String weapon, String meshSet) {
        return MASKS.get(weapon, meshSet);
    }

    static void clear() {
        FULL.clear();
        THUMBNAILS.clear();
        MASKS.clear();
        SkinCompositor.clearInputCache();
    }

    private static final class Pool {
        final SkinResolution resolution;
        final int capacity;
        final String idPrefix;
        final int uploadsPerTick;
        final boolean persist;
        final ExecutorService executor;
        final Map<String, List<Slot>> slots = new HashMap<>();
        final List<Slot> retired = new ArrayList<>();
        long clock;
        int size;

        Pool(SkinResolution resolution, int capacity, String idPrefix, int uploadsPerTick, boolean persist,
             ExecutorService executor) {
            this.resolution = resolution; this.capacity = capacity; this.idPrefix = idPrefix;
            this.uploadsPerTick = uploadsPerTick; this.persist = persist; this.executor = executor;
        }

        ResourceLocation texture(String weapon, SkinData skin) {
            if (skin == null || skin.paintKit() == 0) return null;
            int bucket = Math.round(skin.wear() * 255);
            List<Slot> weaponSlots = slots.get(weapon);
            if (weaponSlots != null) {
                // The common case is one variant. This avoids allocating tuple/boxed keys on every render.
                for (int i = 0; i < weaponSlots.size(); i++) {
                    Slot slot = weaponSlots.get(i);
                    if (slot.kit == skin.paintKit() && slot.bucket == bucket && slot.seed == skin.seed()) {
                        slot.touched = ++clock;
                        return slot.location;
                    }
                }
            }
            int textureIndex = size >= capacity ? retireOldest() : size;
            Slot slot = new Slot(this, weapon, skin.paintKit(), bucket, skin.seed(), textureIndex);
            slots.computeIfAbsent(weapon, key -> new ArrayList<>()).add(slot);
            size++;
            var kit = SkinCatalog.renderKit(skin.paintKit());
            if (kit == null || !kit.availableFor(weapon)) {
                if (resolution == SkinResolution.FULL) {
                    CsmcMod.LOGGER.warn("[csmc skins] No complete local inputs for {} kit {}; retaining base texture", weapon, skin.paintKit());
                }
                return null;
            }
            Path skins = CachePaths.root().resolve("skins");
            Path directory = skins.resolve(weapon).resolve(Integer.toString(skin.paintKit()));
            if (resolution == SkinResolution.FULL) {
                CsmcMod.LOGGER.info("[csmc skins] Loading {} | {} style {} (albedo/wear/seed shader approximation{})",
                        weapon, SkinNames.displayName(kit), kit.style(), kit.style() == 8 || kit.style() == 10 ? "; tinted-metal fallback" : "");
            }
            SkinData quantized = new SkinData(skin.paintKit(), bucket / 255f, skin.seed());
            slot.pending = CompletableFuture.supplyAsync(() -> {
                if (slot.abandoned) return null;
                NativeImage image = prepare(skins, weapon, directory, quantized, bucket);
                if (slot.abandoned) { image.close(); return null; }
                return image;
            }, executor);
            return null;
        }

        void tick() {
            for (Slot slot : retired) slot.close();
            retired.clear();
            int uploads = 0;
            for (List<Slot> list : slots.values()) for (Slot slot : list) {
                if (uploads >= uploadsPerTick) return;
                if (slot.promote()) uploads++;
            }
        }

        void clear() {
            slots.values().forEach(list -> list.forEach(Slot::close));
            retired.forEach(Slot::close);
            slots.clear();
            retired.clear();
            size = 0;
        }

        private int retireOldest() {
            Slot oldest = null;
            for (List<Slot> list : slots.values()) for (Slot slot : list) {
                if (oldest == null || slot.touched < oldest.touched) oldest = slot;
            }
            if (oldest == null) throw new IllegalStateException("Skin cache is unexpectedly empty");
            slots.get(oldest.weapon).remove(oldest);
            oldest.abandoned = true;
            retired.add(oldest); // release between frames, not while earlier buffered draws still reference it
            size--;
            return oldest.textureIndex;
        }

        private NativeImage prepare(Path skins, String weapon, Path directory, SkinData skin, int bucket) {
            try {
                boolean glove = dev.csmc.CsmcMod.GLOVES.containsKey(weapon);
                Path cached = persist && !glove ? CompositeCache.file(skins, weapon, directory, skin, bucket, resolution.edge) : null;
                BufferedImage image = glove ? gloveFinish(directory, resolution.edge) : CompositeCache.read(cached);
                if (image == null) {
                    image = SkinCompositor.compose(directory, skin, resolution.edge);
                    CompositeCache.write(cached, image);
                }
                int width = image.getWidth(), height = image.getHeight();
                int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
                image.flush();
                NativeImage nativeImage = new NativeImage(width, height, false);
                try {
                    for (int y = 0, i = 0; y < height; y++) for (int x = 0; x < width; x++, i++) {
                        int argb = pixels[i];
                        nativeImage.setPixelRGBA(x, y, (argb & 0xff00ff00) | ((argb & 255) << 16) | ((argb >>> 16) & 255));
                    }
                    return nativeImage;
                } catch (RuntimeException e) { nativeImage.close(); throw e; }
            } catch (Exception e) { throw new CompletionException(e); }
        }

        /**
         * CS2 glove finishes are precompiled materials: the exporter bakes their final albedo to {@code color.png},
         * so wear and seed do not change it. Downscaled to the pool's edge.
         */
        private static BufferedImage gloveFinish(Path directory, int edge) throws java.io.IOException {
            BufferedImage source = javax.imageio.ImageIO.read(directory.resolve(GLOVE_COLOR).toFile());
            if (source == null) throw new java.io.IOException("Unreadable glove finish " + directory.resolve(GLOVE_COLOR));
            int longest = Math.max(source.getWidth(), source.getHeight());
            if (longest <= edge && source.getType() == BufferedImage.TYPE_INT_ARGB) return source;
            float scale = Math.min(1f, (float) edge / longest);
            int width = Math.max(1, Math.round(source.getWidth() * scale));
            int height = Math.max(1, Math.round(source.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
                source.flush();
            }
            return scaled;
        }
    }

    private static final class Slot {
        final Pool pool;
        final String weapon;
        final int kit, bucket, seed;
        final int textureIndex;
        volatile boolean abandoned;
        long touched;
        CompletableFuture<NativeImage> pending;
        ResourceLocation location;
        Slot(Pool pool, String weapon, int kit, int bucket, int seed, int textureIndex) {
            this.pool = pool; this.weapon = weapon; this.kit = kit; this.bucket = bucket; this.seed = seed;
            this.textureIndex = textureIndex;
            this.touched = ++pool.clock;
        }
        /** True when this call uploaded a texture. */
        boolean promote() {
            if (pending == null || !pending.isDone()) return false;
            var future = pending;
            pending = null;
            try {
                NativeImage image = future.join();
                if (image == null) return false;
                // Stable per-pool indices also bound vanilla's memoized RenderType keys across evictions.
                ResourceLocation id = CsmcMod.id(pool.idPrefix + textureIndex);
                try {
                    AbstractTexture texture = new Uploaded(image);
                    texture.setFilter(true, false);
                    Minecraft.getInstance().getTextureManager().register(id, texture);
                    location = id;
                } finally { image.close(); }
                return true;
            } catch (RuntimeException e) {
                CsmcMod.LOGGER.warn("[csmc skins] Cannot composite {} kit {}; retaining base texture", weapon, kit, e);
                return false;
            }
        }
        void close() {
            abandoned = true;
            if (pending != null) pending.thenAccept(image -> { if (image != null) image.close(); });
            pending = null;
            if (location != null) Minecraft.getInstance().getTextureManager().release(location);
            location = null;
        }
    }

    private static final class Uploaded extends AbstractTexture {
        Uploaded(NativeImage image) {
            TextureUtil.prepareImage(getId(), image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, false, false, true);
        }
        @Override public void load(ResourceManager manager) { }
    }
}
