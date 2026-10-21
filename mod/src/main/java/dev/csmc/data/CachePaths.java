package dev.csmc.data;

import java.nio.file.Path;

/** Local, never-distributed exports from the user's own CS2 installation. */
public final class CachePaths {
    private CachePaths() {}

    public static Path root() {
        String configured = System.getProperty("csmc.cache");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CSMC_CACHE");
        }
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".csmc", "cache")
                : Path.of(configured);
    }
}
