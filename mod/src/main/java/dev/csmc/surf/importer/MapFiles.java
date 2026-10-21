package dev.csmc.surf.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Locating map files inside the fixed surf map directory. */
public final class MapFiles {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private MapFiles() {}

    /**
     * Resolves {@code name} to {@code <dir>/<name>.vmf}. Names are 1..64 of {@code [A-Za-z0-9_-]}, so separators,
     * dots, drive letters and absolute paths cannot be expressed; the resolved path (after following symlinks) must
     * still lie inside the directory and be a regular file.
     */
    public static Path resolve(Path directory, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new SurfImportException("map name must be 1-64 letters, digits, '_' or '-'");
        }
        Path dir = directory.toAbsolutePath().normalize();
        Path file = dir.resolve(name + ".vmf").normalize();
        if (!dir.equals(file.getParent())) throw new SurfImportException("map path escapes " + dir);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new SurfImportException("no file " + file);
        try {
            Path real = file.toRealPath();
            if (!real.startsWith(dir.toRealPath())) throw new SurfImportException("map path escapes " + dir);
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new SurfImportException("not a regular file: " + file);
            }
            return real;
        } catch (IOException e) {
            throw new SurfImportException("cannot read " + file + ": " + e.getMessage());
        }
    }

    /**
     * Reads through one NOFOLLOW file handle, with a byte cap enforced during reading before constructing the string.
     * SecureDirectoryStream anchors the relative open where supported; other Java providers use a direct channel.
     * Real paths, file keys (where available), sizes and modification times are checked before and after reading.
     * These are local-admin managed files: Java's portable fallback cannot guarantee confinement against a hostile
     * same-UID writer that swaps parent directories/files and restores their metadata between checks.
     */
    public static String read(Path directory, String name, int maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("byte limit must be positive");
        Path real = resolve(directory, name);
        Path relative = Path.of(name + ".vmf");
        try {
            Path dir = directory.toRealPath();
            if (!real.equals(dir.resolve(relative))) {
                throw new SurfImportException("map symlinks are not permitted: " + name);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
                    return readPortable(directory, dir, real, maxBytes);
                }
                BasicFileAttributes root = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
                BasicFileAttributes before = secure.getFileAttributeView(relative, BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!before.isRegularFile()) throw new SurfImportException("not a regular map file: " + name);
                try (SeekableByteChannel channel = secure.newByteChannel(relative,
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    revalidate(directory, dir, real, secure, relative, root, before);
                    if (channel.size() != before.size()) throw changed(real);
                    ByteArrayOutputStream bytes = readBytes(channel, maxBytes);
                    revalidate(directory, dir, real, secure, relative, root, before);
                    if (channel.size() != before.size()) throw changed(real);
                    // Hammer structure is ASCII; lenient UTF-8 tolerates legacy bytes inside values.
                    return bytes.toString(StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new SurfImportException("cannot read " + real + ": " + e.getMessage());
        }
    }

    private static void revalidate(Path directory, Path dir, Path real, SecureDirectoryStream<Path> secure,
                                   Path relative, BasicFileAttributes root, BasicFileAttributes before)
            throws IOException {
        BasicFileAttributes now = secure.getFileAttributeView(relative, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        BasicFileAttributes namedRoot = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!directory.toRealPath().equals(dir) || !real.toRealPath().equals(real) || !real.startsWith(dir)
                || !sameFile(before, now) || !Objects.equals(root.fileKey(), namedRoot.fileKey())) {
            throw changed(real);
        }
    }

    /** Portable fallback: validate/read/revalidate the same open channel; never reopen it to read the contents. */
    private static String readPortable(Path directory, Path dir, Path real, int maxBytes) throws IOException {
        BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw changed(real);
        try (SeekableByteChannel channel = Files.newByteChannel(real,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            validatePortable(directory, dir, real, before);
            if (channel.size() != before.size()) throw changed(real);
            ByteArrayOutputStream bytes = readBytes(channel, maxBytes);
            validatePortable(directory, dir, real, before);
            if (channel.size() != before.size()) throw changed(real);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static ByteArrayOutputStream readBytes(SeekableByteChannel channel, int maxBytes) throws IOException {
        if (channel.size() > maxBytes) {
            throw new SurfImportException("file is larger than " + maxBytes + " bytes");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long total = 0;
        while (true) {
            buffer.clear();
            int count = channel.read(buffer);
            if (count < 0) break;
            total += count;
            if (total > maxBytes) throw new SurfImportException("file is larger than " + maxBytes + " bytes");
            bytes.write(buffer.array(), 0, count);
        }
        return bytes;
    }

    private static void validatePortable(Path directory, Path dir, Path real, BasicFileAttributes before)
            throws IOException {
        BasicFileAttributes now = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!directory.toRealPath().equals(dir) || !real.toRealPath().equals(real) || !real.startsWith(dir)
                || !sameFile(before, now)) throw changed(real);
    }

    private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes now) {
        return now.isRegularFile() && Objects.equals(before.fileKey(), now.fileKey()) && before.size() == now.size()
                && before.lastModifiedTime().equals(now.lastModifiedTime());
    }

    private static SurfImportException changed(Path real) {
        return new SurfImportException("map file or directory changed while it was read: " + real);
    }
}
