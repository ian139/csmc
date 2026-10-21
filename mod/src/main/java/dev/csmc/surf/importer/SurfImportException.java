package dev.csmc.surf.importer;

/** A map file the importer refuses: malformed syntax, an exceeded limit or geometry outside the supported range. */
public final class SurfImportException extends RuntimeException {
    public SurfImportException(String message) {
        super(message);
    }
}
