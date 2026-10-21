package dev.csmc.movement.geometry;

import dev.csmc.movement.physics.ConvexBrushWorld;
import dev.csmc.movement.physics.CsTrace;
import dev.csmc.movement.physics.CsUnits;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

/** Side-agnostic analytic pose-fit query; mutable trace storage is reused separately on each game thread. */
public final class AnalyticClearance {
    private static final ThreadLocal<Query> QUERY = ThreadLocal.withInitial(Query::new);

    private AnalyticClearance() {}

    /** True when the candidate player pose box overlaps no movement-geometry solid. */
    public static boolean clear(BlockGetter level, AABB box) {
        return QUERY.get().clear(level, box);
    }

    private static final class Query {
        private final List<ConvexBrush> brushes = new ArrayList<>();
        private final Consumer<ConvexBrush> sink = brushes::add;
        private final ConvexBrushWorld world = new ConvexBrushWorld(brushes);
        private final CsTrace trace = new CsTrace();

        boolean clear(BlockGetter level, AABB box) {
            brushes.clear();
            MovementGeometry.collectAll(level, box, sink);
            if (brushes.isEmpty()) return true;
            world.reset(brushes);
            double x = CsUnits.toUnits((box.minX + box.maxX) * 0.5);
            double y = CsUnits.toUnits(box.minY);
            double z = CsUnits.toUnits((box.minZ + box.maxZ) * 0.5);
            world.traceHull(x, y, z, x, y, z, CsUnits.toUnits(box.getXsize() * 0.5),
                    CsUnits.toUnits(box.getYsize()), trace);
            return !trace.startSolid;
        }
    }
}
