package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.surf.SurfRamps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic conversion of convex brushes (map-local block coordinates) into whole blocks and surf ramp cells.
 *
 * <ol>
 *   <li><b>Axis faces snap outward to the block grid.</b> Every axis-aligned face moves outward to the next integer
 *   coordinate (faces within {@link #GRID_TOLERANCE} of an integer stay). Before sliver removal and overlap resolution,
 *   the union of snapped brushes contains the canonical input map (so snapping itself never opens a gap at touching
 *   brush faces), and any brush with positive extent along an axis keeps at least one layer.
 *   Approximation: an axis face moves outward by less than one block (40 units); thin Source walls (typically 8-16
 *   units) become one block thick.</li>
 *   <li><b>Whole cells.</b> A cell whose eight corners lie inside every half-space becomes a full block.</li>
 *   <li><b>Sloped cells.</b> After snapping only non-axis faces can cross a cell's interior. Such a cell becomes a
 *   surf ramp whose local planes are exactly those global planes translated to the cell, so neighbouring cells
 *   share identical global planes and the surface is continuous. Input planes are already canonical
 *   ({@link SurfRamps#canonicalPlane}, map-local); local planes are {@link SurfRamps#translateCanonical} copies, so
 *   the stored solid does not depend on where the map is imported. Every vertex enumeration and volume charges the
 *   shared {@link Polytope.Work} budget. Planes that do not change the cell's solid are
 *   dropped; if more than four remain, the four whose removal would add the most volume are kept (the stored solid
 *   is then a superset of the true piece; counted as {@code trimmedCells}). Pieces smaller than
 *   {@link #MIN_PIECE_VOLUME} are skipped as slivers.</li>
 *   <li><b>Overlaps.</b> Full beats ramp; between full cells a drawn block beats an invisible one, then the earlier
 *   brush in file order wins; between ramps the larger piece wins, ties keep the earlier brush. Keeping only one
 *   partial piece can drop geometry, even where complementary pieces jointly filled a cell before this step.</li>
 * </ol>
 * Output is sorted bottom-up (y, then z, then x) so placement order and results are reproducible.
 */
public final class Voxelizer {
    /** Smallest kept sloped piece: 1e-4 block^3 (6.4 cubic Source units). */
    public static final double MIN_PIECE_VOLUME = 1e-4;
    private static final double CUT_EPS = 1e-9;

    private Voxelizer() {}

    /** One importable brush in map-local block space. */
    public record Brush(int order, List<BrushPlane> planes, boolean visible, String block) {
        public Brush {
            planes = List.copyOf(planes);
        }
    }

    /** A cell to place (map-local position). */
    public sealed interface Placement permits SolidCell, RampCell {
        BlockPos pos();
    }

    /** A full block: {@code block} is a namespaced block id. */
    public record SolidCell(BlockPos pos, String block) implements Placement {}

    /**
     * A surf ramp: 1..4 planes in cell-local coordinates (cell = [0,1]^3) and the same planes in map-local block
     * space ({@code mapPlanes}, shared verbatim by every cell cut by the same brush face).
     */
    public record RampCell(BlockPos pos, List<BrushPlane> localPlanes, List<BrushPlane> mapPlanes) implements Placement {
        public RampCell {
            localPlanes = List.copyOf(localPlanes);
            mapPlanes = List.copyOf(mapPlanes);
        }
    }

    public record Result(List<Placement> placements, int fullCells, int rampCells, int thinBrushesDropped,
                         int trimmedCells, int overlapsResolved, int sliversSkipped) {
        public Result {
            placements = List.copyOf(placements);
        }
    }

    private static final class Cell {
        final boolean full;
        final boolean visible;
        final String block;
        final double volume;
        final List<BrushPlane> local;
        final List<BrushPlane> global;

        Cell(boolean full, boolean visible, String block, double volume, List<BrushPlane> local,
             List<BrushPlane> global) {
            this.full = full;
            this.visible = visible;
            this.block = block;
            this.volume = volume;
            this.local = local;
            this.global = global;
        }
    }

    public static Result voxelize(List<Brush> brushes, int maxCells, long visitBudget, Polytope.Work work) {
        Map<Long, Cell> cells = new HashMap<>();
        int thin = 0;
        int trimmed = 0;
        int overlaps = 0;
        int slivers = 0;
        long visits = 0;
        for (Brush brush : brushes) {
            List<BrushPlane> planes = snap(Polytope.dedupe(brush.planes()));
            List<Vec3> vertices = Polytope.vertices(planes, work);
            if (Polytope.volume(planes, vertices, work) < MIN_PIECE_VOLUME) {
                thin++;
                continue;
            }
            AABB box = Polytope.bounds(vertices);
            int x0 = (int) Math.floor(box.minX + 1e-9), x1 = (int) Math.ceil(box.maxX - 1e-9);
            int y0 = (int) Math.floor(box.minY + 1e-9), y1 = (int) Math.ceil(box.maxY - 1e-9);
            int z0 = (int) Math.floor(box.minZ + 1e-9), z1 = (int) Math.ceil(box.maxZ - 1e-9);
            visits += (long) (x1 - x0) * (y1 - y0) * (z1 - z0);
            if (visits > visitBudget) {
                throw new SurfImportException("map needs more than " + visitBudget + " cell tests");
            }
            int n = planes.size();
            double[] hi = new double[n];
            double[] lo = new double[n];
            for (int i = 0; i < n; i++) {
                BrushPlane p = planes.get(i);
                hi[i] = Math.max(p.nx(), 0) + Math.max(p.ny(), 0) + Math.max(p.nz(), 0);
                lo[i] = Math.min(p.nx(), 0) + Math.min(p.ny(), 0) + Math.min(p.nz(), 0);
            }
            List<BrushPlane> cutting = new ArrayList<>();
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    for (int x = x0; x < x1; x++) {
                        cutting.clear();
                        boolean outside = false;
                        for (int i = 0; i < n; i++) {
                            BrushPlane p = planes.get(i);
                            double base = p.nx() * x + p.ny() * y + p.nz() * z - p.distance();
                            if (base + lo[i] >= -CUT_EPS) {
                                outside = true;
                                break;
                            }
                            if (base + hi[i] > CUT_EPS) cutting.add(p);
                        }
                        if (outside) continue;
                        long key = BlockPos.asLong(x, y, z);
                        Cell candidate;
                        if (cutting.isEmpty()) {
                            candidate = new Cell(true, brush.visible(), brush.block(), 1.0, null, null);
                        } else {
                            Piece piece = piece(cutting, x, y, z, work);
                            if (piece == null) {
                                slivers++;
                                continue;
                            }
                            if (piece.trimmed) trimmed++;
                            candidate = piece.local.isEmpty()
                                    ? new Cell(true, brush.visible(), brush.block(), 1.0, null, null)
                                    : new Cell(false, brush.visible(), brush.block(), piece.volume, piece.local, piece.global);
                        }
                        Cell existing = cells.get(key);
                        if (existing == null) {
                            if (cells.size() >= maxCells) {
                                throw new SurfImportException("map produces more than " + maxCells + " cells");
                            }
                            cells.put(key, candidate);
                        } else if (wins(candidate, existing)) {
                            if (!existing.full && !candidate.full) overlaps++;
                            cells.put(key, candidate);
                        } else if (!existing.full && !candidate.full && !existing.local.equals(candidate.local)) {
                            overlaps++;
                        }
                    }
                }
            }
        }
        List<Map.Entry<Long, Cell>> sorted = new ArrayList<>(cells.entrySet());
        sorted.sort(Comparator.<Map.Entry<Long, Cell>>comparingInt(e -> BlockPos.getY(e.getKey()))
                .thenComparingInt(e -> BlockPos.getZ(e.getKey()))
                .thenComparingInt(e -> BlockPos.getX(e.getKey())));
        List<Placement> out = new ArrayList<>(sorted.size());
        int full = 0;
        int ramps = 0;
        for (Map.Entry<Long, Cell> entry : sorted) {
            BlockPos pos = BlockPos.of(entry.getKey());
            Cell cell = entry.getValue();
            if (cell.full) {
                full++;
                out.add(new SolidCell(pos, cell.block));
            } else {
                ramps++;
                out.add(new RampCell(pos, cell.local, cell.global));
            }
        }
        return new Result(out, full, ramps, thin, trimmed, overlaps, slivers);
    }

    private static boolean wins(Cell candidate, Cell existing) {
        if (existing.full) return candidate.full && candidate.visible && !existing.visible;
        if (candidate.full) return true;
        return candidate.volume > existing.volume + 1e-12;
    }

    private record Piece(List<BrushPlane> local, List<BrushPlane> global, double volume, boolean trimmed) {}

    /** The solid of a partially covered cell, or null for a sliver; an empty plane list means "effectively full". */
    private static Piece piece(List<BrushPlane> cutting, int x, int y, int z, Polytope.Work work) {
        List<BrushPlane> cell = Polytope.cell(x, y, z);
        List<BrushPlane> planes = new ArrayList<>(cutting);
        double volume = volumeWith(cell, planes, work);
        if (volume < MIN_PIECE_VOLUME) return null;
        if (volume > 1.0 - 1e-9) return new Piece(List.of(), List.of(), 1.0, false);
        // Drop planes that do not shape this cell's solid.
        for (int i = planes.size() - 1; i >= 0 && planes.size() > 1; i--) {
            List<BrushPlane> without = new ArrayList<>(planes);
            without.remove(i);
            double removedVolume = volumeWith(cell, without, work);
            if (removedVolume - volume < 1e-9) {
                planes.remove(i);
                volume = removedVolume;
            }
        }
        boolean trimmed = false;
        if (planes.size() > 4) {
            trimmed = true;
            double[] gain = new double[planes.size()];
            for (int i = 0; i < planes.size(); i++) {
                List<BrushPlane> without = new ArrayList<>(planes);
                without.remove(i);
                gain[i] = volumeWith(cell, without, work) - volume;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < planes.size(); i++) order.add(i);
            order.sort(Comparator.<Integer>comparingDouble(i -> -gain[i]).thenComparingInt(i -> i));
            List<BrushPlane> kept = new ArrayList<>();
            for (int i = 0; i < planes.size(); i++) if (order.indexOf(i) < 4) kept.add(planes.get(i));
            planes = kept;
            volume = volumeWith(cell, planes, work);
        }
        List<BrushPlane> local = new ArrayList<>(planes.size());
        for (BrushPlane plane : planes) local.add(SurfRamps.translateCanonical(plane, -x, -y, -z));
        return new Piece(local, List.copyOf(planes), volume, trimmed);
    }

    private static double volumeWith(List<BrushPlane> cell, List<BrushPlane> planes, Polytope.Work work) {
        List<BrushPlane> all = new ArrayList<>(cell.size() + planes.size());
        all.addAll(cell);
        all.addAll(planes);
        return Polytope.volume(all, work);
    }

    /** Faces closer than this (blocks) to an integer count as on it: absorbs float noise of unit/40 divisions. */
    public static final double GRID_TOLERANCE = 1e-6;

    /** Axis faces outward to the block grid (rule 1 in the class doc). Input must be de-duplicated. */
    static List<BrushPlane> snap(List<BrushPlane> planes) {
        // Faces per axis: index 0/1 = max/min face of x, 2/3 of y, 4/5 of z.
        double[] coordinate = new double[6];
        int[] index = {-1, -1, -1, -1, -1, -1};
        for (int i = 0; i < planes.size(); i++) {
            BrushPlane p = planes.get(i);
            if (!Polytope.isAxial(p)) continue;
            int axis = p.nx() != 0 ? 0 : p.ny() != 0 ? 1 : 2;
            double component = axis == 0 ? p.nx() : axis == 1 ? p.ny() : p.nz();
            int slot = axis * 2 + (component > 0 ? 0 : 1);
            index[slot] = i;
            coordinate[slot] = component > 0 ? p.distance() : -p.distance();
        }
        double[] snapped = new double[6];
        for (int s = 0; s < 6; s++) {
            snapped[s] = s % 2 == 0 ? Math.ceil(coordinate[s] - GRID_TOLERANCE)
                    : Math.floor(coordinate[s] + GRID_TOLERANCE);
        }
        List<BrushPlane> out = new ArrayList<>(planes);
        for (int s = 0; s < 6; s++) {
            if (index[s] < 0) continue;
            BrushPlane p = planes.get(index[s]);
            out.set(index[s], new BrushPlane(p.nx(), p.ny(), p.nz(), s % 2 == 0 ? snapped[s] : -snapped[s]));
        }
        return out;
    }
}
