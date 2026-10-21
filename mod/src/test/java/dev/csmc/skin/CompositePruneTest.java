package dev.csmc.skin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Test;

public class CompositePruneTest {
    private static Path entry(Path dir, String name, int bytes, long mtime) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
        return file;
    }

    /** An external directory the cache must never delete into: an old-version-looking child plus a sentinel. */
    private static Path external() throws Exception {
        Path outside = Files.createTempDirectory("csmc-prune-outside");
        entry(outside.resolve("v0"), "precious.png", 10, 1_000);
        entry(outside, "sentinel.txt", 10, 1_000);
        entry(outside.resolve("ak47"), "old.png", 100, 1_000);
        return outside;
    }

    private static void assertUntouched(Path outside) {
        assertTrue(Files.exists(outside.resolve("sentinel.txt")));
        assertTrue(Files.exists(outside.resolve("v0/precious.png")));
        assertTrue(Files.exists(outside.resolve("ak47/old.png")));
    }

    @Test public void symlinkedCacheBaseIsNeverPruned() throws Exception {
        Path outside = external();
        Path skins = Files.createTempDirectory("csmc-prune-link-base");
        Files.createSymbolicLink(skins.resolve(".composited"), outside);
        assertEquals(0, CompositeCache.prune(skins, 0));
        assertUntouched(outside);
    }

    @Test public void symlinkedVersionDirectoriesAreNotFollowed() throws Exception {
        Path outside = external();
        Path skins = Files.createTempDirectory("csmc-prune-link-version");
        Path base = Files.createDirectories(skins.resolve(".composited"));
        Files.createSymbolicLink(base.resolve("v0"), outside);
        Files.createSymbolicLink(base.resolve("v" + CompositeCache.VERSION), outside);
        CompositeCache.prune(skins, 0);
        assertUntouched(outside);
        assertFalse("stale version link itself is removed", Files.exists(base.resolve("v0"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test public void symlinksInsideTheCurrentVersionAreNotFollowed() throws Exception {
        Path outside = external();
        Path skins = Files.createTempDirectory("csmc-prune-link-inner");
        Path current = Files.createDirectories(CompositeCache.directory(skins));
        Files.createSymbolicLink(current.resolve("ak47"), outside.resolve("ak47"));
        Files.createSymbolicLink(current.resolve("leak.png"), outside.resolve("sentinel.txt"));
        Path own = entry(current.resolve("awp"), "a.png", 100, 1_000);
        CompositeCache.prune(skins, 0);
        assertUntouched(outside);
        assertFalse(Files.exists(own));
    }

    @Test public void dropsOtherVersionsAndOldestEntriesOverBudget() throws Exception {
        Path skins = Files.createTempDirectory("csmc-prune");
        Path current = CompositeCache.directory(skins);
        Path old = entry(skins.resolve(".composited/v0/ak47"), "x.png", 10, 1_000);
        Path oldest = entry(current.resolve("ak47"), "a.png", 100, 1_000_000);
        Path middle = entry(current.resolve("awp"), "b.png", 100, 2_000_000);
        Path newest = entry(current.resolve("ak47"), "c.png", 100, 3_000_000);
        assertEquals(200, CompositeCache.prune(skins, 250));
        assertFalse(Files.exists(old.getParent().getParent()));
        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(middle) && Files.exists(newest));
        assertTrue(Files.isDirectory(current));
    }

    @Test public void underBudgetKeepsEverything() throws Exception {
        Path skins = Files.createTempDirectory("csmc-prune-keep");
        Path current = CompositeCache.directory(skins);
        entry(current.resolve("ak47"), "a.png", 100, 1_000);
        entry(current.resolve("ak47"), "b.png", 100, 2_000);
        assertEquals(200, CompositeCache.prune(skins, 200));
        assertEquals(0, CompositeCache.prune(Files.createTempDirectory("csmc-prune-none"), 10));
    }

    @Test public void readHitRefreshesRecency() throws Exception {
        Path skins = Files.createTempDirectory("csmc-prune-touch");
        Path current = CompositeCache.directory(skins).resolve("ak47");
        Files.createDirectories(current);
        Path hit = current.resolve("hit.png");
        CompositeCache.write(hit, new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        Files.setLastModifiedTime(hit, FileTime.fromMillis(1_000));
        Path cold = entry(current, "cold.png", (int) Files.size(hit), 2_000);
        CompositeCache.read(hit);
        CompositeCache.prune(skins, Files.size(hit));
        assertTrue(Files.exists(hit));
        assertFalse(Files.exists(cold));
    }

    @Test public void subsampleKeepsFullTexturesAndTwiceThumbnailEdge() {
        assertEquals(1, SkinCompositor.subsample(4096, 4096, 1024));
        assertEquals(1, SkinCompositor.subsample(2048, 2048, SkinResolution.FULL.edge));
        assertEquals(4, SkinCompositor.subsample(2048, 2048, 256));
        assertEquals(8, SkinCompositor.subsample(4096, 2048, 256));
        assertEquals(2, SkinCompositor.subsample(1024, 1024, 256));
        assertEquals(1, SkinCompositor.subsample(256, 256, 256));
        assertEquals(1, SkinCompositor.subsample(2, 2, 2));
        for (int edge : new int[]{300, 777, 1023, 2048, 4096}) {
            int step = SkinCompositor.subsample(edge, edge, 256);
            assertTrue(edge + " keeps 2x texels", (edge + step - 1) / step >= Math.min(edge, 512));
        }
    }

    @Test public void subsampleIsPartOfTheDecodedInputKey() {
        Object inode = "inode-7";
        assertEquals(SkinCompositor.inputKey(inode, 10, 20, 4), SkinCompositor.inputKey(inode, 10, 20, 4));
        assertNotEquals(SkinCompositor.inputKey(inode, 10, 20, 1), SkinCompositor.inputKey(inode, 10, 20, 4));
        assertNotEquals(SkinCompositor.inputKey(inode, 10, 20, 4), SkinCompositor.inputKey(inode, 10, 21, 4));
    }
}
