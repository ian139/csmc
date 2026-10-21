package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.surf.SurfRamps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Places a converted map a slice at a time. Each {@link #step} writes at most {@code maxCells} cells and checks
 * {@code deadlineNanos} cooperatively (every 64 writes), so a map of millions of cells spreads over many ticks. A cell
 * whose chunk is not loaded is never generated synchronously: the sink requests it asynchronously
 * ({@link ImportSink#prepare}) and the step yields until a later tick. Placement order is the converter's (bottom-up),
 * so partially built maps grow from the floor. Ramp planes are canonical map-local planes moved by
 * {@link SurfRamps#translateCanonical}, which keeps their normals bit-identical.
 */
public final class ImportJob {
    private final String name;
    private final SurfMapConverter.Converted map;
    private final BlockPos origin;
    private final ImportSink sink;
    private int next;
    private int solidsChanged;
    private int rampsChanged;
    private int unchanged;

    public ImportJob(String name, SurfMapConverter.Converted map, BlockPos origin, ImportSink sink) {
        this.name = name;
        this.map = map;
        this.origin = origin.immutable();
        this.sink = sink;
    }

    /** Places the next slice; true once every cell has been written. Yields early while a chunk is loading. */
    public boolean step(int maxCells, long deadlineNanos) {
        List<Voxelizer.Placement> placements = map.placements();
        int end = Math.min(placements.size(), next + maxCells);
        while (next < end) {
            Voxelizer.Placement placement = placements.get(next);
            BlockPos pos = placement.pos().offset(origin);
            if (!sink.prepare(pos)) break;
            next++;
            boolean changed;
            if (placement instanceof Voxelizer.SolidCell solid) {
                changed = sink.solid(pos, solid.block());
                if (changed) solidsChanged++;
            } else {
                Voxelizer.RampCell ramp = (Voxelizer.RampCell) placement;
                List<BrushPlane> world = new ArrayList<>(ramp.mapPlanes().size());
                for (BrushPlane plane : ramp.mapPlanes()) {
                    world.add(SurfRamps.translateCanonical(plane, origin.getX(), origin.getY(), origin.getZ()));
                }
                changed = sink.ramp(pos, world);
                if (changed) rampsChanged++;
            }
            if (!changed) unchanged++;
            if ((next & 63) == 0 && System.nanoTime() - deadlineNanos > 0) break;
        }
        return next >= placements.size();
    }

    /** Zones in world coordinates. */
    public List<TeleportZone> worldZones() {
        List<TeleportZone> out = new ArrayList<>(map.zones().size());
        for (TeleportZone zone : map.zones()) out.add(zone.translated(origin.getX(), origin.getY(), origin.getZ()));
        return out;
    }

    /** Spawns in world coordinates. */
    public List<SurfMapConverter.Spawn> worldSpawns() {
        List<SurfMapConverter.Spawn> out = new ArrayList<>(map.spawns().size());
        for (SurfMapConverter.Spawn spawn : map.spawns()) {
            out.add(spawn.translated(origin.getX(), origin.getY(), origin.getZ()));
        }
        return out;
    }

    public String name() {
        return name;
    }

    public BlockPos origin() {
        return origin;
    }

    public SurfMapConverter.Summary summary() {
        return map.summary();
    }

    public int placed() {
        return next;
    }

    public int total() {
        return map.placements().size();
    }

    public int solidsChanged() {
        return solidsChanged;
    }

    public int rampsChanged() {
        return rampsChanged;
    }

    /** Cells already holding exactly the imported block or ramp (or a ramp the API refused). */
    public int unchanged() {
        return unchanged;
    }
}
