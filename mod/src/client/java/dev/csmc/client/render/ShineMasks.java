package dev.csmc.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import dev.csmc.skin.ShineParams;
import dev.csmc.skin.SkinTextureBudget;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Render-thread LRU of per-body shine masks ({@link SkinTextureBudget#SHINE_MASKS} slots of at most
 * {@link SkinTextureBudget#SHINE_MASK_EDGE} px). Decoding runs on the skin loader; uploads happen between frames.
 */
final class ShineMasks {
    private final Executor executor;
    private final Map<String, Map<String, Slot>> slots = new HashMap<>();
    private final List<Slot> retired = new ArrayList<>();
    private long clock;
    private int size;

    ShineMasks(Executor executor) {
        this.executor = executor;
    }

    ResourceLocation get(String weapon, String meshSet) {
        if (meshSet == null) return null;
        Map<String, Slot> bodies = slots.get(weapon);
        if (bodies == null) slots.put(weapon, bodies = new HashMap<>());
        Slot slot = bodies.get(meshSet);
        if (slot == null) {
            int index = size >= SkinTextureBudget.SHINE_MASKS ? retireOldest() : size;
            bodies.put(meshSet, slot = new Slot(weapon, meshSet, index));
            size++;
            Path file = CachePaths.root().resolve("skins").resolve(weapon).resolve(ShineParams.DEFAULT_KIT)
                    .resolve("shine_mask_" + meshSet + ".png");
            Slot loading = slot;
            loading.pending = CompletableFuture.supplyAsync(() -> read(file, loading), executor);
        }
        slot.touched = ++clock;
        return slot.location;
    }

    private static NativeImage read(Path file, Slot slot) {
        if (slot.abandoned || !Files.isRegularFile(file)) return null;
        try (InputStream input = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(input);
            int edge = SkinTextureBudget.SHINE_MASK_EDGE;
            if (image.getWidth() > edge || image.getHeight() > edge) {
                CsmcMod.LOGGER.warn("[csmc skins] {} exceeds {} px; finish stays matte", file, edge);
                image.close();
                return null;
            }
            return image;
        } catch (Exception e) {
            CsmcMod.LOGGER.warn("[csmc skins] Cannot read {}; finish stays matte", file, e);
            return null;
        }
    }

    void tick() {
        for (Slot slot : retired) slot.close();
        retired.clear();
        for (Map<String, Slot> bodies : slots.values()) for (Slot slot : bodies.values()) {
            if (slot.promote()) return; // one upload per tick
        }
    }

    void clear() {
        slots.values().forEach(bodies -> bodies.values().forEach(Slot::close));
        retired.forEach(Slot::close);
        slots.clear();
        retired.clear();
        size = 0;
    }

    private int retireOldest() {
        Slot oldest = null;
        for (Map<String, Slot> bodies : slots.values()) for (Slot slot : bodies.values()) {
            if (oldest == null || slot.touched < oldest.touched) oldest = slot;
        }
        if (oldest == null) throw new IllegalStateException("Shine mask cache is unexpectedly empty");
        slots.get(oldest.weapon).remove(oldest.meshSet);
        oldest.abandoned = true;
        retired.add(oldest); // release between frames, not while earlier buffered draws still reference it
        size--;
        return oldest.index;
    }

    private static final class Slot {
        final String weapon;
        final String meshSet;
        final int index;
        volatile boolean abandoned;
        long touched;
        CompletableFuture<NativeImage> pending;
        ResourceLocation location;

        Slot(String weapon, String meshSet, int index) {
            this.weapon = weapon;
            this.meshSet = meshSet;
            this.index = index;
        }

        boolean promote() {
            if (pending == null || !pending.isDone()) return false;
            NativeImage image = pending.join();
            pending = null;
            if (image == null) return false;
            try {
                ResourceLocation id = CsmcMod.id("cached_shine_mask/" + index);
                AbstractTexture texture = new Uploaded(image);
                texture.setFilter(true, false);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                location = id;
            } finally {
                image.close();
            }
            return true;
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
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, false, false, false);
        }

        @Override
        public void load(ResourceManager manager) { }
    }
}
