package dev.csmc.surf.importer;

/**
 * Hard bounds for one import. Defaults follow the Source compile limits where one exists:
 * {@code MAX_MAP_BRUSHES} 8192 and {@code MAX_MAP_ENTITIES} 8192 ({@code public/bspfile.h}),
 * {@code MAX_BRUSH_SIDES} 128 ({@code utils/vbsp/vbsp.h}) and {@code MAX_COORD_INTEGER} 16384
 * ({@code public/worldsize.h}), all Source SDK 2013. The text and cell limits are this importer's own budget.
 *
 * @param maxBytes          largest accepted file in bytes (also caps parsed characters)
 * @param maxDepth          deepest block nesting
 * @param maxNodes          total blocks in the file
 * @param maxPairsPerBlock  keys in one block
 * @param maxTokenLength    longest key, value or word
 * @param maxSolids         brushes over the whole file (world + entities)
 * @param maxSidesPerSolid  sides of one brush
 * @param maxEntities       entity blocks
 * @param maxCoordinate     largest absolute Source coordinate of any plane point or brush vertex
 * @param maxCells          placed cells (solid + ramp) per import
 */
public record ImportLimits(int maxBytes, int maxDepth, int maxNodes, int maxPairsPerBlock, int maxTokenLength,
                           int maxSolids, int maxSidesPerSolid, int maxEntities, double maxCoordinate, int maxCells) {
    public static final ImportLimits DEFAULT = new ImportLimits(64 << 20, 64, 1_000_000, 4096, 8192,
            8192, 128, 8192, 16384.0, 4_000_000);

    public ImportLimits {
        if (maxBytes <= 0 || maxDepth <= 0 || maxNodes <= 0 || maxPairsPerBlock <= 0 || maxTokenLength <= 0
                || maxSolids <= 0 || maxSidesPerSolid < 4 || maxEntities <= 0 || !(maxCoordinate > 0)
                || maxCells <= 0) {
            throw new IllegalArgumentException("import limits must be positive");
        }
    }
}
