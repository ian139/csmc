package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.surf.SurfRamps;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TreeMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * VMF text to placements, teleport zones and spawns, all in map-local block space (Source origin at block 0,0,0).
 * Pure: touches no world, so it runs off the server thread and in unit tests. Callers translate by the chosen
 * origin, which is an integer block offset and therefore leaves every cell-local plane unchanged.
 */
public final class SurfMapConverter {
    /** Spawn classes stored as teleport targets (CS:GO / Source spawn point entities). */
    public static final List<String> SPAWN_CLASSES = List.of("info_player_counterterrorist",
            "info_player_terrorist", "info_player_start", "info_player_deathmatch");
    /** {@code SF_TRIGGER_ALLOW_CLIENTS} and {@code SF_TRIGGER_ALLOW_ALL} (Source SDK 2013 server/triggers.h). */
    static final int SF_ALLOW_CLIENTS = 0x01;
    static final int SF_ALLOW_ALL = 0x40;
    /** {@code SF_TELEPORT_PRESERVE_ANGLES} (server/triggers.cpp). */
    static final int SF_PRESERVE_ANGLES = 0x20;
    /**
     * Work units allowed per import across every vertex enumeration and volume computation (brush validation,
     * voxelisation and plane trimming share one {@link Polytope.Work} counter; volume charges face processing too).
     */
    static final long WORK_BUDGET = 2_000_000_000L;

    private SurfMapConverter() {}

    public record Spawn(String classname, Vec3 position, float yaw, float pitch) {
        public Spawn translated(double dx, double dy, double dz) {
            return new Spawn(classname, position.add(dx, dy, dz), yaw, pitch);
        }
    }

    /**
     * Import statistics. {@code skipped} counts brushes (or zones) per reason, sorted by reason; {@code minY}/
     * {@code maxY} are the lowest and highest occupied map-local block rows (0/-1 when nothing is placed).
     */
    public record Summary(int entities, int solids, int visibleBrushes, int invisibleBrushes,
                          Map<String, Integer> skipped, int hiddenBlocksSkipped, int fullCells, int rampCells,
                          int thinBrushesDropped, int trimmedCells, int overlapsResolved, int sliversSkipped,
                          int zones, int spawns, int minY, int maxY) {
        public Summary {
            skipped = Map.copyOf(new TreeMap<>(skipped));
        }

        public int skippedTotal() {
            int total = 0;
            for (int count : skipped.values()) total += count;
            return total;
        }
    }

    public record Converted(List<Voxelizer.Placement> placements, List<TeleportZone> zones, List<Spawn> spawns,
                            Summary summary) {
        public Converted {
            placements = List.copyOf(placements);
            zones = List.copyOf(zones);
            spawns = List.copyOf(spawns);
        }
    }

    public static Converted convert(String text, ImportLimits limits) {
        return convert(text, limits, WORK_BUDGET);
    }

    static Converted convert(String text, ImportLimits limits, long workBudget) {
        VmfMap map = VmfMap.read(KeyValues.parse(text, limits), limits);
        SourceTransform transform = new SourceTransform(0, 0, 0);
        double bound = limits.maxCoordinate() / SourceTransform.UNITS_PER_BLOCK;
        Map<String, Integer> skipped = new TreeMap<>();
        List<Voxelizer.Brush> brushes = new ArrayList<>();
        int solids = 0;
        Polytope.Work work = new Polytope.Work(workBudget);
        int visible = 0;
        int invisible = 0;
        Map<String, VmfMap.Entity> named = new HashMap<>();
        for (VmfMap.Entity entity : map.entities()) {
            String name = entity.key("targetname");
            if (name != null && !name.isEmpty() && entity.origin() != null) {
                named.putIfAbsent(name.toLowerCase(Locale.ROOT), entity);
            }
        }
        List<TeleportZone> zones = new ArrayList<>();
        List<Spawn> spawns = new ArrayList<>();
        for (VmfMap.Entity entity : map.entities()) {
            solids += entity.solids().size();
            if (SPAWN_CLASSES.contains(entity.classname()) && entity.origin() != null) {
                double[] angles = angles(entity);
                spawns.add(new Spawn(entity.classname(), transform.point(entity.origin()),
                        SourceTransform.yaw(angles[1]), SourceTransform.pitch(angles[0])));
                continue;
            }
            if (entity.solids().isEmpty()) continue;
            if (entity.classname().equals("trigger_teleport")) {
                TeleportZone zone = zone(entity, named, transform, bound, skipped, work);
                if (zone != null) zones.add(zone);
                continue;
            }
            String rejection = BrushClassifier.entityRejection(entity);
            if (rejection != null) {
                skipped.merge(rejection, entity.solids().size(), Integer::sum);
                continue;
            }
            for (VmfMap.Solid solid : entity.solids()) {
                BrushClassifier.Result result = BrushClassifier.classify(solid);
                if (result.kind() == BrushClassifier.Kind.IGNORED) {
                    skipped.merge(result.reason(), 1, Integer::sum);
                    continue;
                }
                List<BrushPlane> planes = brushPlanes(solid, transform, bound, skipped, work);
                if (planes == null) continue;
                if (result.reason() != null) skipped.merge(result.reason() + " (imported as invisible solid)", 1,
                        Integer::sum);
                if (result.kind() == BrushClassifier.Kind.VISIBLE) visible++;
                else invisible++;
                brushes.add(new Voxelizer.Brush(brushes.size(), planes,
                        result.kind() == BrushClassifier.Kind.VISIBLE, result.material()));
            }
        }
        Voxelizer.Result voxels = Voxelizer.voxelize(brushes, limits.maxCells(), (long) limits.maxCells() * 64, work);
        int minY = 0;
        int maxY = -1;
        if (!voxels.placements().isEmpty()) {
            minY = voxels.placements().get(0).pos().getY();
            maxY = voxels.placements().get(voxels.placements().size() - 1).pos().getY();
        }
        Summary summary = new Summary(map.entities().size(), solids, visible, invisible, skipped,
                map.hiddenBlocksSkipped(), voxels.fullCells(), voxels.rampCells(), voxels.thinBrushesDropped(),
                voxels.trimmedCells(), voxels.overlapsResolved(), voxels.sliversSkipped(), zones.size(),
                spawns.size(), minY, maxY);
        return new Converted(voxels.placements(), zones, spawns, summary);
    }

    /**
     * The brush's half-spaces in block space, or null (counted in {@code skipped}) when the sides do not enclose a
     * finite volume. Throws when a vertex lies beyond the coordinate limit.
     */
    static List<BrushPlane> brushPlanes(VmfMap.Solid solid, SourceTransform transform, double bound,
                                        Map<String, Integer> skipped, Polytope.Work work) {
        List<BrushPlane> planes = new ArrayList<>();
        for (VmfMap.Side side : solid.sides()) {
            BrushPlane plane = transform.plane(side.p0(), side.p1(), side.p2());
            if (plane == null) {
                skipped.merge("degenerate brush side", 1, Integer::sum);
                return null;
            }
            // Canonical once, in map-local space, before voxelisation: placement only translates these coefficients
            // (SurfRamps.translateCanonical), so the imported solid is independent of the import origin.
            planes.add(SurfRamps.canonicalPlane(plane));
        }
        planes = Polytope.dedupe(planes);
        // Guard box at twice the limit: a vertex on it means the sides leave the brush open (unbounded).
        double guard = 2 * bound;
        List<BrushPlane> guarded = new ArrayList<>(planes);
        guarded.addAll(List.of(new BrushPlane(1, 0, 0, guard), new BrushPlane(-1, 0, 0, guard),
                new BrushPlane(0, 1, 0, guard), new BrushPlane(0, -1, 0, guard),
                new BrushPlane(0, 0, 1, guard), new BrushPlane(0, 0, -1, guard)));
        // Vertex enumeration costs ~C(n,3)*n plane tests, charged to the shared budget.
        List<Vec3> vertices = Polytope.vertices(guarded, work);
        if (planes.size() < 4 || vertices.size() < 4 || Polytope.volume(guarded, vertices, work) <= 0) {
            skipped.merge("empty or open brush", 1, Integer::sum);
            return null;
        }
        AABB box = Polytope.bounds(vertices);
        if (box.maxX >= guard - 1e-6 || box.maxY >= guard - 1e-6 || box.maxZ >= guard - 1e-6
                || box.minX <= -guard + 1e-6 || box.minY <= -guard + 1e-6 || box.minZ <= -guard + 1e-6) {
            skipped.merge("empty or open brush", 1, Integer::sum);
            return null;
        }
        if (box.maxX > bound || box.maxY > bound || box.maxZ > bound
                || box.minX < -bound || box.minY < -bound || box.minZ < -bound) {
            throw new SurfImportException("solid " + solid.id() + " extends beyond +/-"
                    + (bound * SourceTransform.UNITS_PER_BLOCK) + " Source units");
        }
        return planes;
    }

    private static TeleportZone zone(VmfMap.Entity entity, Map<String, VmfMap.Entity> named,
                                     SourceTransform transform, double bound, Map<String, Integer> skipped,
                                     Polytope.Work work) {
        int count = entity.solids().size();
        if (entity.intKey("startdisabled", 0) != 0) {
            skipped.merge("trigger_teleport StartDisabled (entity I/O unsupported)", count, Integer::sum);
            return null;
        }
        int flags = entity.intKey("spawnflags", 0);
        if ((flags & (SF_ALLOW_CLIENTS | SF_ALLOW_ALL)) == 0) {
            skipped.merge("trigger_teleport not fired by players", count, Integer::sum);
            return null;
        }
        String filter = entity.key("filtername");
        if (filter != null && !filter.isEmpty()) {
            skipped.merge("trigger_teleport with filtername (filters unsupported)", count, Integer::sum);
            return null;
        }
        String target = entity.key("target");
        VmfMap.Entity destination = target == null ? null : named.get(target.toLowerCase(Locale.ROOT));
        if (destination == null) {
            skipped.merge("trigger_teleport without a resolvable target", count, Integer::sum);
            return null;
        }
        String landmarkName = entity.key("landmark");
        VmfMap.Entity landmark = landmarkName == null || landmarkName.isEmpty()
                ? null : named.get(landmarkName.toLowerCase(Locale.ROOT));
        List<ConvexBrush> volumes = new ArrayList<>();
        for (VmfMap.Solid solid : entity.solids()) {
            List<BrushPlane> planes = brushPlanes(solid, transform, bound, skipped, work);
            if (planes == null) continue;
            volumes.add(new ConvexBrush(planes, Polytope.bounds(Polytope.vertices(planes, work))));
        }
        if (volumes.isEmpty()) return null;
        double[] angles = angles(destination);
        boolean setAngles = landmark == null && (flags & SF_PRESERVE_ANGLES) == 0;
        String name = entity.key("targetname");
        return new TeleportZone(name == null || name.isEmpty() ? "trigger_teleport#" + entity.index() : name,
                volumes, transform.point(destination.origin()), SourceTransform.yaw(angles[1]),
                SourceTransform.pitch(angles[0]), setAngles,
                landmark == null ? null : transform.point(landmark.origin()));
    }

    /** {@code angles} "pitch yaw roll"; a lone {@code angle} key is the yaw (Source keyvalue convention). */
    private static double[] angles(VmfMap.Entity entity) {
        String text = entity.key("angles");
        if (text != null && !text.isBlank()) {
            VmfMap.Point p = VmfMap.parseVector(text, "angles of " + entity.classname());
            return new double[] {p.x(), p.y(), p.z()};
        }
        String yaw = entity.key("angle");
        if (yaw != null && !yaw.isBlank()) {
            double value;
            try {
                value = Double.parseDouble(yaw.trim());
            } catch (NumberFormatException e) {
                throw new SurfImportException("angle of " + entity.classname() + " is not a number: " + yaw);
            }
            if (!Double.isFinite(value)) throw new SurfImportException("angle of " + entity.classname() + " not finite");
            return new double[] {0, value, 0};
        }
        return new double[] {0, 0, 0};
    }
}
