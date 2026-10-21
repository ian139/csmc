package dev.csmc.surf;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Builds continuous surf shapes from world planes. Every cell of the box is classified against the shape's world
 * planes: cut cells become surf ramps carrying all of the shape's planes (translated exactly, so the whole shape is
 * seam-free), cells entirely below the surface get the solid filler, and cells entirely above are left untouched.
 */
public final class SurfBuilder {
    /** Largest box the builder commands accept, matching vanilla {@code /fill}'s 32768-block limit. */
    public static final int MAX_VOLUME = 32768;

    /** Placement counts of one build. */
    public record Result(int ramps, int filler) {}

    private SurfBuilder() {}

    /**
     * World plane of a straight ramp filling {@code box} that rises towards {@code upslope} at {@code angleDegrees},
     * starting at the box's bottom on the edge opposite {@code upslope}.
     */
    public static List<BrushPlane> rampPlanes(BoundingBox box, Direction upslope, double angleDegrees) {
        double k = Math.tan(Math.toRadians(angleDegrees));
        int fx = upslope.getStepX(), fz = upslope.getStepZ();
        return List.of(SurfRamps.slopePlane(fx, fz, k, lowEdge(box, fx, fz), box.minY()));
    }

    /**
     * Two-sided ridge whose crest runs along {@code axis} through the middle of {@code box}: one slope rises from each
     * long side of the box at {@code angleDegrees} until they meet (or the box top clips them).
     */
    public static List<BrushPlane> ridgePlanes(BoundingBox box, Direction.Axis axis, double angleDegrees) {
        double k = Math.tan(Math.toRadians(angleDegrees));
        Direction positive = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        Direction negative = positive.getOpposite();
        return List.of(
                SurfRamps.slopePlane(positive.getStepX(), positive.getStepZ(), k,
                        lowEdge(box, positive.getStepX(), positive.getStepZ()), box.minY()),
                SurfRamps.slopePlane(negative.getStepX(), negative.getStepZ(), k,
                        lowEdge(box, negative.getStepX(), negative.getStepZ()), box.minY()));
    }

    /** {@code f·p} on the box side opposite the horizontal unit direction {@code f}. */
    private static double lowEdge(BoundingBox box, int fx, int fz) {
        if (fx > 0) return box.minX();
        if (fx < 0) return -(box.maxX() + 1.0);
        if (fz > 0) return box.minZ();
        return -(box.maxZ() + 1.0);
    }

    /** Fills {@code box} with the solid below {@code worldPlanes}; see the class comment. */
    public static Result build(ServerLevel level, BoundingBox box, List<BrushPlane> worldPlanes, BlockState filler) {
        int ramps = 0, fills = 0;
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockPos pos = cursor.set(x, y, z).immutable();
                    List<BrushPlane> local = SurfRamps.toLocal(worldPlanes, pos);
                    RampGeometry geometry = SurfRamps.validate(local);
                    if (geometry == null) continue;
                    if (geometry.isFullCell()) {
                        if (level.setBlock(pos, filler, flags)) fills++;
                    } else if (SurfRamps.placeWorld(level, pos, worldPlanes, flags)) {
                        ramps++;
                    }
                }
            }
        }
        return new Result(ramps, fills);
    }
}
