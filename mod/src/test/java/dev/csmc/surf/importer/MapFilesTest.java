package dev.csmc.surf.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MapFilesTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void resolvesOnlyPlainNamesInsideTheDirectory() throws IOException {
        Path dir = temp.newFolder("surfmaps").toPath();
        Path map = Files.writeString(dir.resolve("surf_test-1.vmf"), "world { }");
        assertEquals(map.toRealPath(), MapFiles.resolve(dir, "surf_test-1"));
        Files.writeString(temp.getRoot().toPath().resolve("secret.vmf"), "world { }");
        for (String bad : List.of("../secret", "/etc/passwd", "..", "a.b", "a/b", "a\\b", "", "C:x",
                "x".repeat(65), "surf test")) {
            assertThrows(bad, SurfImportException.class, () -> MapFiles.resolve(dir, bad));
        }
        assertThrows(SurfImportException.class, () -> MapFiles.resolve(dir, "missing"));
    }

    @Test
    public void rejectsSymlinksLeavingTheDirectoryAndNonFiles() throws IOException {
        Path dir = temp.newFolder("surfmaps").toPath();
        Path outside = Files.writeString(temp.getRoot().toPath().resolve("outside.vmf"), "world { }");
        Files.createSymbolicLink(dir.resolve("escape.vmf"), outside);
        assertThrows(SurfImportException.class, () -> MapFiles.resolve(dir, "escape"));
        Files.createDirectory(dir.resolve("folder.vmf"));
        assertThrows(SurfImportException.class, () -> MapFiles.resolve(dir, "folder"));
    }
}
