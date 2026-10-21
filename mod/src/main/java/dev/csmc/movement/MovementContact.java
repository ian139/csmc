package dev.csmc.movement;

import dev.csmc.movement.geometry.ConvexBrush;
import dev.csmc.movement.geometry.MovementGeometry;
import dev.csmc.movement.physics.ConvexBrushWorld;
import dev.csmc.movement.physics.CsTrace;
import dev.csmc.movement.physics.CsUnits;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Whether an entity rests on or slides along an upward-facing {@link MovementGeometry} surface (a surf ramp or the
 * top of a movement solid), using the same hull traces as the client simulation. Used by the server fall-damage
 * rule: the server never collides analytic players with these solids, so it cannot see them "standing" or sliding
 * on a ramp from its own collision. Walls, undersides and near misses are not support.
 */
public final class MovementContact {
    /**
     * Support tolerance in blocks (2.5 units): the exact hull, swept this far straight down, must hit a movement
     * surface. CS movement keeps the hull {@code DIST_EPSILON} (0.03125 units) off a surface it slides along
     * (perpendicular gap; vertically {@code DIST_EPSILON / normal.y}, about 0.1 units on a 72° ramp); the margin
     * also covers the position reported after the tick's last substep.
     */
    public static final double CONTACT_EPSILON = 0.0625;
    /**
     * Smallest {@code normal.y} of a supporting surface: anything facing up counts (surf ramps are steeper than
     * walkable ground); vertical faces and undersides ({@code normal.y <= 0}) never do.
     */
    public static final double MIN_SUPPORT_NORMAL_Y = 0.0;

    private static final ThreadLocal<Probe> PROBES = ThreadLocal.withInitial(Probe::new);

    private MovementContact() {}

    /**
     * True when the exact box of {@code entity}, swept down by at most {@link #CONTACT_EPSILON}, lands on a movement
     * solid face with {@code normal.y > }{@link #MIN_SUPPORT_NORMAL_Y}. A box that already overlaps a solid by less
     * than the tolerance (rounding of a reported position) is swept from {@link #CONTACT_EPSILON} higher instead;
     * one still overlapping there (inside a wall) is not supported.
     */
    public static boolean supportedByGeometry(Entity entity) {
        return PROBES.get().supported(entity);
    }

    private static final class Probe {
        private final List<ConvexBrush> brushes = new ArrayList<>();
        private final CsTrace trace = new CsTrace();
        private ConvexBrushWorld world;

        boolean supported(Entity entity) {
            AABB box = entity.getBoundingBox();
            brushes.clear();
            MovementGeometry.collectAll(entity.level(), box.inflate(0.0, CONTACT_EPSILON, 0.0)
                    .expandTowards(0.0, -CONTACT_EPSILON, 0.0), brushes::add);
            if (brushes.isEmpty()) return false;
            if (world == null) {
                world = new ConvexBrushWorld(brushes);
            } else {
                world.reset(brushes);
            }
            double x = CsUnits.toUnits(entity.getX());
            double y = CsUnits.toUnits(box.minY);
            double z = CsUnits.toUnits(entity.getZ());
            double halfWidth = CsUnits.toUnits(box.getXsize() / 2.0);
            double height = CsUnits.toUnits(box.getYsize());
            double epsilon = CsUnits.toUnits(CONTACT_EPSILON);
            world.traceHull(x, y, z, x, y - epsilon, z, halfWidth, height, trace);
            if (trace.startSolid) {
                world.traceHull(x, y + epsilon, z, x, y - epsilon, z, halfWidth, height, trace);
                if (trace.startSolid) return false;
            }
            return trace.hit() && trace.normal.y > MIN_SUPPORT_NORMAL_Y;
        }
    }
}
