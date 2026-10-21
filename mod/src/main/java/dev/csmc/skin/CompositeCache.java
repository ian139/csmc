package dev.csmc.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Derived on-disk cache of composited skin textures under {@code skins/.composited/v<VERSION>/}. Entries are keyed by
 * weapon, kit, wear bucket, seed, edge, {@link #VERSION} and the size/mtime of {@code params.json} and every input
 * it names, so changed inputs or compositor changes miss instead of serving stale pixels. {@link #prune} bounds it by
 * size (least recently used first) and drops other versions. Deleting the directory is always safe; the exporter
 * never writes there.
 */
public final class CompositeCache {
    /** Bump whenever {@link SkinCompositor} output changes for the same inputs. */
    public static final int VERSION = 2;

    private CompositeCache() { }

    public static Path directory(Path skinsRoot) {
        return skinsRoot.resolve(".composited").resolve("v" + VERSION);
    }

    /** Null when the kit directory has no readable params.json (the compositor then reports the real error). */
    public static Path file(Path skinsRoot, String weapon, Path kitDirectory, SkinData skin, int bucket, int edge) {
        String hash;
        try {
            hash = inputHash(kitDirectory, edge);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return directory(skinsRoot).resolve(weapon)
                .resolve(skin.paintKit() + "-" + bucket + "-" + skin.seed() + "-" + edge + "-" + hash + ".png");
    }

    /** Hash of the compositor version, edge and the size/mtime of params.json plus every input it lists. */
    static String inputHash(Path kitDirectory, int edge) throws IOException {
        Path root = kitDirectory.toAbsolutePath().normalize();
        Path params = root.resolve("params.json");
        JsonObject document = JsonParser.parseString(Files.readString(params)).getAsJsonObject();
        Map<String, Path> files = new TreeMap<>();
        files.put("params.json", params);
        if (document.has("inputs") && document.get("inputs").isJsonObject()) {
            for (var entry : document.getAsJsonObject("inputs").entrySet()) {
                files.put("input:" + entry.getKey(), root.resolve(entry.getValue().getAsString()).normalize());
            }
        }
        List<String> parts = new ArrayList<>();
        parts.add("v" + VERSION);
        parts.add("edge" + edge);
        for (var entry : files.entrySet()) {
            BasicFileAttributes attributes = Files.readAttributes(entry.getValue(), BasicFileAttributes.class);
            parts.add(entry.getKey() + "=" + attributes.size() + "@" + attributes.lastModifiedTime().toMillis());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null on a miss or an unreadable entry. A hit refreshes the entry's mtime, the recency {@link #prune} keeps. */
    public static BufferedImage read(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null) Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            return image;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Atomic write; failures only cost a future recomposite. */
    public static void write(Path file, BufferedImage image) {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".tmp-", ".png");
            try {
                if (!ImageIO.write(image, "PNG", temporary.toFile())) throw new IOException("No PNG writer");
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException ignored) {
            // Read-only or full disk: the in-memory composite is still used.
        }
    }

    /**
     * Deletes every other version directory under {@code .composited}, then the least recently used entries of the
     * current version until it holds at most {@code maxBytes}. Returns the bytes kept. Errors leave files in place.
     * Symbolic links are never followed: a symlinked {@code .composited} or current version is left alone entirely,
     * and a link anywhere below is removed as a link (never its target) or skipped.
     */
    public static long prune(Path skinsRoot, long maxBytes) {
        Path base = skinsRoot.resolve(".composited");
        Path current = directory(skinsRoot);
        if (!realDirectory(base)) return 0;
        try (Stream<Path> versions = Files.list(base)) {
            for (Path version : versions.toList()) {
                if (!version.equals(current)) deleteTree(version);
            }
        } catch (IOException | RuntimeException ignored) { }
        if (!realDirectory(current)) return 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(current)) {
            files = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        Map<Path, long[]> stats = new HashMap<>(); // {size, mtime}
        long total = 0;
        for (Path file : files) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                stats.put(file, new long[]{attributes.size(), attributes.lastModifiedTime().toMillis()});
                total += attributes.size();
            } catch (IOException ignored) { }
        }
        files.removeIf(file -> !stats.containsKey(file));
        files.sort(Comparator.comparingLong(file -> stats.get(file)[1]));
        for (Path file : files) {
            if (total <= maxBytes) break;
            try {
                Files.deleteIfExists(file);
                total -= stats.get(file)[0];
            } catch (IOException ignored) { }
        }
        return total;
    }

    /** A directory that is not itself a symbolic link. */
    private static boolean realDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** Deletes a real directory tree without following links; a link (or file) is deleted itself. */
    private static void deleteTree(Path root) throws IOException {
        if (!realDirectory(root)) {
            Files.deleteIfExists(root);
            return;
        }
        // Files.walk does not follow links below a real root; links are visited and deleted as links.
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
