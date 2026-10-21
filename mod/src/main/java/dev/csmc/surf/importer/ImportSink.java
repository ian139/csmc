package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Where an import job writes cells (world positions). Returns whether the world changed. */
public interface ImportSink {
    /**
     * Whether {@code pos} can be written now without generating or loading its chunk synchronously. A sink that
     * returns false starts loading it asynchronously; the job yields and retries on a later step.
     */
    boolean prepare(BlockPos pos);

    /** A full block; {@code block} is a namespaced block id from {@link BrushClassifier}. */
    boolean solid(BlockPos pos, String block);

    /** A surf ramp cell from world-space planes (1..4). */
    boolean ramp(BlockPos pos, List<BrushPlane> worldPlanes);
}
