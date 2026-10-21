package dev.csmc.skin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Test;
import net.minecraft.world.item.ItemDisplayContext;

public class CompositeCacheTest {
    private static Path kit() throws Exception {
        Path skins = Files.createTempDirectory("csmc-composite");
        Path dir = Files.createDirectories(skins.resolve("ak47/7"));
        Files.writeString(dir.resolve("params.json"), "{\"style\":1,\"inputs\":{\"color\":\"color.png\"}}");
        Files.write(dir.resolve("color.png"), new byte[]{1, 2, 3});
        return dir;
    }

    @Test public void fileIsVersionedAndKeyedByVariant() throws Exception {
        Path dir = kit();
        Path skins = dir.getParent().getParent();
        Path file = CompositeCache.file(skins, "ak47", dir, new SkinData(7, .5f, 3), 128, 256);
        assertTrue(file.startsWith(skins.resolve(".composited").resolve("v" + CompositeCache.VERSION).resolve("ak47")));
        assertTrue(file.getFileName().toString().startsWith("7-128-3-256-"));
        assertNotEquals(file, CompositeCache.file(skins, "ak47", dir, new SkinData(7, .5f, 4), 128, 256));
        assertNotEquals(file, CompositeCache.file(skins, "ak47", dir, new SkinData(7, .5f, 3), 129, 256));
        assertNotEquals(file, CompositeCache.file(skins, "ak47", dir, new SkinData(7, .5f, 3), 128, 1024));
    }

    @Test public void changedInputsMiss() throws Exception {
        Path dir = kit();
        String before = CompositeCache.inputHash(dir, 256);
        assertEquals(before, CompositeCache.inputHash(dir, 256));
        Files.setLastModifiedTime(dir.resolve("color.png"), FileTime.fromMillis(1_000_000));
        String touched = CompositeCache.inputHash(dir, 256);
        assertNotEquals(before, touched);
        Files.writeString(dir.resolve("params.json"), "{\"style\":2,\"inputs\":{\"color\":\"color.png\"}}");
        assertNotEquals(touched, CompositeCache.inputHash(dir, 256));
    }

    @Test public void missingParamsHasNoCacheFile() throws Exception {
        Path empty = Files.createTempDirectory("csmc-composite-empty");
        assertNull(CompositeCache.file(empty, "ak47", empty.resolve("ak47/1"), new SkinData(1, 0, 0), 0, 256));
    }

    @Test public void roundTripsPixels() throws Exception {
        Path file = Files.createTempDirectory("csmc-composite-rt").resolve("a/b.png");
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff8000);
        image.setRGB(1, 0, 0xff102030);
        assertNull(CompositeCache.read(file));
        CompositeCache.write(file, image);
        BufferedImage read = CompositeCache.read(file);
        assertEquals(0xffff8000, read.getRGB(0, 0));
        assertEquals(0xff102030, read.getRGB(1, 0));
    }

    @Test public void guiUsesThumbnailsAndEverythingElseFullTextures() {
        assertEquals(SkinResolution.THUMBNAIL, SkinResolution.of(ItemDisplayContext.GUI));
        for (ItemDisplayContext context : ItemDisplayContext.values()) {
            if (context != ItemDisplayContext.GUI) assertEquals(context.name(), SkinResolution.FULL, SkinResolution.of(context));
        }
        assertEquals(1024, SkinResolution.FULL.edge);
        assertTrue(SkinResolution.THUMBNAIL.edge * 4 == SkinResolution.FULL.edge);
    }
}
