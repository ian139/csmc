package dev.csmc.surf.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import dev.csmc.surf.SurfRamps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Regressions for the independent review of the surf-map importer (findings 1, 4, 5, 7, 10). */
public class ImportReviewFixesTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Records every write, relative to the origin, with ramp planes moved back into their cell. */
    private static final class RecordingSink implements ImportSink {
        final BlockPos origin;
        final Map<BlockPos, Object> cells = new HashMap<>();
        final List<BrushPlane> worldPlanes = new ArrayList<>();
        boolean ready = true;

        RecordingSink(BlockPos origin) {
            this.origin = origin;
        }

        @Override
        public boolean prepare(BlockPos pos) {
            return ready;
        }

        @Override
        public boolean solid(BlockPos pos, String block) {
            cells.put(pos.subtract(origin), block);
            return true;
        }

        @Override
        public boolean ramp(BlockPos pos, List<BrushPlane> planes) {
            worldPlanes.addAll(planes);
            cells.put(pos.subtract(origin), SurfRamps.toLocal(planes, pos));
            return true;
        }
    }

    private static RecordingSink place(SurfMapConverter.Converted map, BlockPos origin) {
        RecordingSink sink = new RecordingSink(origin);
        ImportJob job = new ImportJob("t", map, origin, sink);
        while (!job.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {
            // a recording sink is always ready
        }
        return sink;
    }

    @Test
    public void placementYieldsUntilTheChunkIsReady() {
        SurfMapConverter.Converted map = SurfMapConverterTest.convert("ramp_prism.vmf");
        RecordingSink sink = new RecordingSink(BlockPos.ZERO);
        sink.ready = false;
        ImportJob job = new ImportJob("waiting", map, BlockPos.ZERO, sink);
        assertTrue(!job.step(4096, Long.MAX_VALUE));
        assertEquals(0, job.placed());
        assertTrue(sink.cells.isEmpty());
        sink.ready = true;
        assertTrue(job.step(4096, Long.MAX_VALUE));
        assertEquals(map.placements().size(), job.placed());
        assertEquals(map.placements().size(), sink.cells.size());
    }

    @Test
    public void importedGeometryIsIndependentOfTheOrigin() {
        SurfMapConverter.Converted map = SurfMapConverterTest.convert("ramp_prism.vmf");
        RecordingSink near = place(map, BlockPos.ZERO);
        RecordingSink far = place(map, new BlockPos(10_000_000, 0, 0));
        assertTrue("fixture has ramp cells", !near.worldPlanes.isEmpty());
        assertEquals("cell sets and local planes", near.cells, far.cells);
        for (BrushPlane plane : far.worldPlanes) {
            // Already canonical: SurfRamps.placeWorld does not round them a second time.
            assertEquals(plane, SurfRamps.canonicalPlane(plane));
        }
        // Normals are bit-identical to the canonical map-local plane at any origin.
        for (int i = 0; i < near.worldPlanes.size(); i++) {
            BrushPlane a = near.worldPlanes.get(i);
            BrushPlane b = far.worldPlanes.get(i);
            assertEquals(a.nx(), b.nx(), 0.0);
            assertEquals(a.ny(), b.ny(), 0.0);
            assertEquals(a.nz(), b.nz(), 0.0);
        }
    }

    private static List<BrushPlane> nearCoplanarRamp() {
        List<BrushPlane> planes = new ArrayList<>(Polytope.cell(0, 0, 0));
        for (int i = 0; i <= 5; i++) planes.add(BrushPlane.of(1, 1 + i * 1e-8, 0, 1));
        return planes;
    }

    @Test
    public void nearCoplanarFacetsCountOnce() {
        double volume = Polytope.volume(nearCoplanarRamp(), new Polytope.Work(Long.MAX_VALUE));
        assertEquals(0.5, volume, 1e-6);
        assertEquals(7, Polytope.dedupe(nearCoplanarRamp()).size());
    }

    @Test
    public void nearCoplanarBrushStaysARampCell() {
        Voxelizer.Result result = Voxelizer.voxelize(
                List.of(new Voxelizer.Brush(0, nearCoplanarRamp(), true, "minecraft:stone")), 16, 1000,
                new Polytope.Work(Long.MAX_VALUE));
        assertEquals(0, result.fullCells());
        assertEquals(1, result.rampCells());
    }

    private static List<BrushPlane> unequalFacets() {
        double g = 0x1.0p-24, a = 11863283 * g;
        List<BrushPlane> planes = new ArrayList<>(Polytope.cell(0, 0, 0));
        planes.add(new BrushPlane(a, a, 0, a));
        planes.add(new BrushPlane(a - 2 * g, a + 2 * g, 0, a - g));
        return planes;
    }

    @Test
    public void unequalOverlappingFacetMembershipDoesNotInflateVolume() {
        double g = 0x1.0p-24, a = 11863283 * g;
        // P and Q meet at x = 3/4. Integrate the lower of their two lines over [0,1].
        double expected = 0.5 - 9 * g / (8 * (a + 2 * g));
        List<BrushPlane> planes = unequalFacets();
        assertEquals(expected, Polytope.volume(planes, new Polytope.Work(Long.MAX_VALUE)), 1e-12);
        for (int i = 0; i < planes.size(); i++) {
            java.util.Collections.rotate(planes, 1);
            assertEquals("rotation " + i, expected,
                    Polytope.volume(planes, new Polytope.Work(Long.MAX_VALUE)), 1e-12);
        }
        java.util.Collections.reverse(planes);
        assertEquals(expected, Polytope.volume(planes, new Polytope.Work(Long.MAX_VALUE)), 1e-12);
    }

    @Test
    public void competingLargerCellWinsDespiteUnequalFacetMembership() {
        List<BrushPlane> competitor = new ArrayList<>(Polytope.cell(0, 0, 0));
        BrushPlane winner = SurfRamps.canonicalPlane(BrushPlane.of(1, 1, 0, 1.02));
        competitor.add(winner);
        Voxelizer.Result result = Voxelizer.voxelize(List.of(
                new Voxelizer.Brush(0, unequalFacets(), true, "minecraft:stone"),
                new Voxelizer.Brush(1, competitor, true, "minecraft:stone")), 16, 1000,
                new Polytope.Work(Long.MAX_VALUE));
        assertEquals(1, result.rampCells());
        assertEquals(List.of(winner), ((Voxelizer.RampCell) result.placements().get(0)).mapPlanes());
    }

    @Test
    public void removingANegligiblePlaneRefreshesTheOverlapVolume() {
        BrushPlane diagonal = SurfRamps.canonicalPlane(BrushPlane.of(1, 1, 0, 1));
        List<BrushPlane> first = new ArrayList<>(Polytope.cell(0, 0, 0));
        first.add(diagonal);
        // Clips a ~4e-10 corner: intentionally below the existing 1e-9 redundancy threshold.
        first.add(SurfRamps.canonicalPlane(BrushPlane.of(1, 2, 0, 2 - 0.00004)));
        List<BrushPlane> second = new ArrayList<>(Polytope.cell(0, 0, 0));
        second.add(SurfRamps.canonicalPlane(BrushPlane.of(1, -1, 0, 0)));
        Voxelizer.Result result = Voxelizer.voxelize(List.of(
                new Voxelizer.Brush(0, first, true, "minecraft:stone"),
                new Voxelizer.Brush(1, second, true, "minecraft:stone")), 16, 1000,
                new Polytope.Work(Long.MAX_VALUE));
        // After the negligible plane is removed both retained solids have volume 1/2; file order must win.
        assertEquals(List.of(diagonal), ((Voxelizer.RampCell) result.placements().get(0)).mapPlanes());
    }

    @Test
    public void volumeClippingChargesTheSharedBudget() {
        List<BrushPlane> planes = unequalFacets();
        Polytope.Work enumeration = new Polytope.Work(Long.MAX_VALUE);
        List<Vec3> vertices = Polytope.vertices(planes, enumeration);
        assertThrows(SurfImportException.class,
                () -> Polytope.volume(planes, vertices, new Polytope.Work(1)));
    }

    /** A 64-sided prism (cone-like sloped sides) spanning many partially covered cells. */
    private static List<BrushPlane> manySided() {
        List<BrushPlane> planes = new ArrayList<>();
        planes.add(new BrushPlane(0, -1, 0, 0));
        planes.add(new BrushPlane(0, 1, 0, 4));
        for (int i = 0; i < 62; i++) {
            double a = 2 * Math.PI * i / 62;
            planes.add(SurfRamps.canonicalPlane(BrushPlane.of(Math.cos(a), 0.5, Math.sin(a), 6)));
        }
        return planes;
    }

    @Test
    public void voxelisationAndTrimmingChargeTheSharedBudget() {
        Polytope.Work validation = new Polytope.Work(Long.MAX_VALUE);
        Polytope.vertices(manySided(), validation);
        // The brush alone passes a budget of 4x its validation cost ...
        long budget = validation.used() * 4;
        Polytope.Work work = new Polytope.Work(budget);
        Polytope.vertices(manySided(), work);
        // ... but voxelising it (per-cell enumerations and remove-one-plane trimming) exceeds it.
        SurfImportException e = assertThrows(SurfImportException.class, () -> Voxelizer.voxelize(
                List.of(new Voxelizer.Brush(0, manySided(), true, "minecraft:stone"), new Voxelizer.Brush(1,
                        manySided(), true, "minecraft:stone")), 1_000_000, 1_000_000, work));
        assertTrue(e.getMessage(), e.getMessage().contains("work budget"));
    }

    @Test
    public void converterChargesVoxelisationToTheWorkBudget() {
        String text = SurfMapConverterTest.fixture("ramp_prism.vmf");
        assertNotNull(SurfMapConverter.convert(text, ImportLimits.DEFAULT, SurfMapConverter.WORK_BUDGET));
        // ramp_prism is one 5-sided brush: validation charges C(11,3)*11 = 1815 plane tests (5 sides + 6 guard
        // planes), plus 264 volume operations. A 2500 budget passes validation; only voxelisation can exceed it.
        SurfImportException e = assertThrows(SurfImportException.class,
                () -> SurfMapConverter.convert(text, ImportLimits.DEFAULT, 2_500));
        assertTrue(e.getMessage(), e.getMessage().contains("work budget"));
    }

    @Test
    public void readEnforcesTheByteLimitThroughOneHandle() throws IOException {
        Path dir = temp.newFolder("surfmaps").toPath();
        Files.writeString(dir.resolve("small.vmf"), "world { }");
        assertEquals("world { }", MapFiles.read(dir, "small", 9));
        SurfImportException e = assertThrows(SurfImportException.class, () -> MapFiles.read(dir, "small", 8));
        assertTrue(e.getMessage(), e.getMessage().contains("larger than 8"));
        Files.write(dir.resolve("big.vmf"), new byte[300_000]);
        assertThrows(SurfImportException.class, () -> MapFiles.read(dir, "big", 200_000));
        Path outside = Files.writeString(temp.getRoot().toPath().resolve("outside.vmf"), "world { }");
        Files.createSymbolicLink(dir.resolve("escape.vmf"), outside);
        assertThrows(SurfImportException.class, () -> MapFiles.read(dir, "escape", 1000));
        Files.createSymbolicLink(dir.resolve("internal.vmf"), dir.resolve("small.vmf"));
        assertThrows(SurfImportException.class, () -> MapFiles.read(dir, "internal", 1000));
    }

    private static TeleportZone zone(String name, double x0, double z0, double x1, double z1) {
        AABB box = new AABB(x0, 0, z0, x1, 2, z1);
        return new TeleportZone(name, List.of(ConvexBrush.box(box)), new Vec3(0, 5, 0), 0, 0, true, null);
    }

    @Test
    public void multiColumnLookupPreservesGlobalZoneOrder() {
        TeleportZone a = zone("A", 16, 1, 17, 2);
        TeleportZone b = zone("B", 15, 1, 15.9, 2);
        AABB player = new AABB(15.5, 0, 1.2, 16.1, 1.8, 1.8);
        SurfImportData data = SurfImportData.load(new CompoundTag(), null);
        SurfImportData.MapRecord record = new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(0, 0, 0), List.of(a, b), List.of());
        data.put("map", record);
        assertTrue(a.touches(player) && b.touches(player));
        assertEquals(List.of(a, b), data.zonesNear(player));
        // Reverse map insertion; map-name order, not bucket or insertion order, still governs.
        data.remove("map");
        data.put("z", new SurfImportData.MapRecord(record.origin(), List.of(b), List.of()));
        data.put("a", new SurfImportData.MapRecord(record.origin(), List.of(a), List.of()));
        assertEquals(List.of(a, b), data.zonesNear(player));
        data.remove("a");
        data.remove("z");
        data.put("a", new SurfImportData.MapRecord(record.origin(), List.of(a), List.of()));
        data.put("z", new SurfImportData.MapRecord(record.origin(), List.of(b), List.of()));
        assertEquals(List.of(a, b), data.zonesNear(player));
        data.remove("a");
        data.remove("z");
        // Mirror the columns: rebuild now inserts the west bucket before the east bucket.
        TeleportZone westA = zone("A", 15, 1, 15.9, 2);
        TeleportZone eastB = zone("B", 16, 1, 17, 2);
        data.put("map", new SurfImportData.MapRecord(record.origin(), List.of(westA, eastB), List.of()));
        assertEquals(List.of(westA, eastB), data.zonesNear(player));
    }

    @Test
    public void zonesAreIndexedByChunkColumnAndBudgeted() {
        SurfImportData data = SurfImportData.load(new CompoundTag(), null);
        TeleportZone here = zone("here", 1, 1, 3, 3);
        TeleportZone far = zone("far", 1000, 1000, 1002, 1002);
        TeleportZone wide = zone("wide", -40, 0, 40, 4);
        data.put("m", new SurfImportData.MapRecord(new SurfImportData.BlockPosRecord(0, 0, 0),
                List.of(here, far, wide), List.of()));
        assertEquals(3, data.zoneCount());
        assertEquals(List.of(here, wide), data.zonesNear(new AABB(2, 0, 2, 2.6, 1.8, 2.6)));
        assertEquals(List.of(far), data.zonesNear(new AABB(1001, 0, 1001, 1001.6, 1.8, 1001.6)));
        assertEquals(List.of(), data.zonesNear(new AABB(500, 0, 500, 500.6, 1.8, 500.6)));

        assertNull(data.budgetViolation("m", new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(0, 0, 0), List.of(here), List.of())));
        List<TeleportZone> many = new ArrayList<>();
        for (int i = 0; i < SurfImportData.MAX_ZONES; i++) many.add(zone("z" + i, i * 16, 0, i * 16 + 1, 1));
        String problem = data.budgetViolation("other", new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(0, 0, 0), many, List.of()));
        assertNotNull(problem);
        assertTrue(problem, problem.contains("teleport zones"));
        String huge = data.budgetViolation("other", new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(0, 0, 0), List.of(zone("huge", 0, 0, 16 * 600, 16 * 600)),
                List.of()));
        assertNotNull(huge);
        assertTrue(huge, huge.contains("chunk columns"));
        SurfImportData.MapRecord tooMany = new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(0, 0, 0), many, List.of());
        assertThrows(SurfImportException.class, () -> data.put("other", tooMany));
        assertEquals(3, data.zoneCount());
        assertEquals(1, data.maps().size());
        TeleportZone tooManyVolumes = new TeleportZone("volumes",
                java.util.Collections.nCopies(SurfImportData.MAX_ZONE_VOLUMES + 1, here.volumes().get(0)),
                here.destination(), 0, 0, true, null);
        String volumeProblem = data.budgetViolation("v", new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(0, 0, 0), List.of(tooManyVolumes), List.of()));
        assertTrue(volumeProblem, volumeProblem.contains("teleport volumes"));

        // Saved records cannot bypass the aggregate budget on a later restart.
        CompoundTag saved = data.save(new CompoundTag(), null);
        CompoundTag storedMap = saved.getCompound("maps").getCompound("m");
        net.minecraft.nbt.ListTag excessive = new net.minecraft.nbt.ListTag();
        CompoundTag storedZone = storedMap.getList("zones", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        for (int i = 0; i <= SurfImportData.MAX_ZONES; i++) excessive.add(storedZone);
        storedMap.put("zones", excessive);
        assertThrows(SurfImportException.class, () -> SurfImportData.load(saved, null));
    }
}
