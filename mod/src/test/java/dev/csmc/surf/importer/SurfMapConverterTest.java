package dev.csmc.surf.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.csmc.movement.geometry.BrushPlane;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class SurfMapConverterTest {
    /** Planes are canonicalised onto the 2^-24 grid (SurfRamps.GRID): coefficients differ by at most 2^-25. */
    private static final double EPS = 1e-7;
    private static final double SQRT5 = Math.sqrt(5);

    static String fixture(String name) {
        try (InputStream in = SurfMapConverterTest.class.getResourceAsStream("/surf/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static SurfMapConverter.Converted convert(String name) {
        return SurfMapConverter.convert(fixture(name), ImportLimits.DEFAULT);
    }

    private static List<Voxelizer.RampCell> ramps(SurfMapConverter.Converted map) {
        List<Voxelizer.RampCell> out = new ArrayList<>();
        for (Voxelizer.Placement p : map.placements()) if (p instanceof Voxelizer.RampCell r) out.add(r);
        return out;
    }

    private static Set<BlockPos> solids(SurfMapConverter.Converted map) {
        Set<BlockPos> out = new HashSet<>();
        for (Voxelizer.Placement p : map.placements()) if (p instanceof Voxelizer.SolidCell s) out.add(s.pos());
        return out;
    }

    private static void assertPlane(BrushPlane expected, BrushPlane actual) {
        assertEquals(expected.nx(), actual.nx(), EPS);
        assertEquals(expected.ny(), actual.ny(), EPS);
        assertEquals(expected.nz(), actual.nz(), EPS);
        assertEquals(expected.distance(), actual.distance(), EPS);
    }

    /** Volume of everything placed: full cells count 1, ramps their exact piece. */
    private static double placedVolume(SurfMapConverter.Converted map) {
        double volume = 0;
        for (Voxelizer.Placement p : map.placements()) {
            if (p instanceof Voxelizer.SolidCell) {
                volume += 1;
            } else {
                List<BrushPlane> planes = new ArrayList<>(Polytope.cell(0, 0, 0));
                planes.addAll(((Voxelizer.RampCell) p).localPlanes());
                volume += Polytope.volume(planes, new Polytope.Work(Long.MAX_VALUE));
            }
        }
        return volume;
    }

    /** Every ramp's local planes, moved back to world space, equal the map planes it shares with its neighbours. */
    private static void assertSeamless(List<Voxelizer.RampCell> ramps) {
        for (Voxelizer.RampCell ramp : ramps) {
            assertTrue(ramp.localPlanes().size() >= 1 && ramp.localPlanes().size() <= 4);
            assertEquals(ramp.localPlanes().size(), ramp.mapPlanes().size());
            for (int i = 0; i < ramp.localPlanes().size(); i++) {
                BrushPlane local = ramp.localPlanes().get(i);
                BrushPlane world = local.translated(ramp.pos().getX(), ramp.pos().getY(), ramp.pos().getZ());
                assertPlane(ramp.mapPlanes().get(i), world);
            }
        }
    }

    /** Exact up to skipped slivers (each below {@link Voxelizer#MIN_PIECE_VOLUME}); trimming never happens here. */
    private static double volumeTolerance(SurfMapConverter.Converted map) {
        assertEquals(0, map.summary().trimmedCells());
        return map.summary().sliversSkipped() * Voxelizer.MIN_PIECE_VOLUME + 1e-6;
    }

    @Test
    public void straightRampMapsAxesScaleAndSlope() {
        SurfMapConverter.Converted map = convert("ramp_prism.vmf");
        // Source prism x 0..80, y 0..160, z 0..160 with 2x + z <= 160 -> blocks x 0..2, y 0..4, z -4..0, 2x + y <= 4.
        BrushPlane slope = new BrushPlane(2 / SQRT5, 1 / SQRT5, 0, 4 / SQRT5);
        // Per 1-block slice along z: full (0,0),(0,1); ramps (1,0),(1,1),(0,2),(0,3).
        assertEquals(8, map.summary().fullCells());
        assertEquals(16, map.summary().rampCells());
        for (Voxelizer.Placement p : map.placements()) {
            assertTrue(p.pos().getX() >= 0 && p.pos().getX() <= 1);
            assertTrue(p.pos().getY() >= 0 && p.pos().getY() <= 3);
            assertTrue(p.pos().getZ() >= -4 && p.pos().getZ() <= -1);
        }
        for (int z = -4; z <= -1; z++) {
            assertTrue(solids(map).contains(new BlockPos(0, 0, z)));
            assertTrue(solids(map).contains(new BlockPos(0, 1, z)));
        }
        List<Voxelizer.RampCell> ramps = ramps(map);
        for (Voxelizer.RampCell ramp : ramps) {
            assertEquals(1, ramp.localPlanes().size());
            assertPlane(slope, ramp.mapPlanes().get(0));
            // Surfable: the slope's up component is below Source's 0.7 ground limit.
            assertTrue(ramp.localPlanes().get(0).ny() < 0.7);
        }
        Voxelizer.RampCell cell = ramps.stream().filter(r -> r.pos().equals(new BlockPos(0, 2, -1))).findFirst()
                .orElseThrow();
        assertPlane(new BrushPlane(2 / SQRT5, 1 / SQRT5, 0, 2 / SQRT5), cell.localPlanes().get(0));
        assertSeamless(ramps);
        assertEquals(16.0, placedVolume(map), volumeTolerance(map));
        assertEquals(1, map.summary().visibleBrushes());
    }

    @Test
    public void originIsAnIntegerShiftThatKeepsLocalPlanes() {
        SourceTransform shifted = new SourceTransform(100, 64, -30);
        VmfMap.Point a = new VmfMap.Point(80, -2, 0);
        VmfMap.Point b = new VmfMap.Point(80, 0, 0);
        VmfMap.Point c = new VmfMap.Point(82, 0, -4);
        BrushPlane atZero = new SourceTransform(0, 0, 0).plane(a, b, c);
        assertPlane(atZero.translated(100, 64, -30), shifted.plane(a, b, c));
        assertEquals(new Vec3(102, 65, -35), shifted.point(80, 200, 40));
    }

    @Test
    public void ridgeCellsCarryBothSlopes() {
        SurfMapConverter.Converted map = convert("ridge.vmf");
        BrushPlane left = new BrushPlane(-2 / SQRT5, 1 / SQRT5, 0, 0);
        BrushPlane right = new BrushPlane(2 / SQRT5, 1 / SQRT5, 0, 6 / SQRT5);
        List<Voxelizer.RampCell> ramps = ramps(map);
        Voxelizer.RampCell apex = ramps.stream().filter(r -> r.pos().equals(new BlockPos(1, 2, -1))).findFirst()
                .orElseThrow();
        assertEquals(2, apex.mapPlanes().size());
        assertPlane(left, apex.mapPlanes().get(0));
        assertPlane(right, apex.mapPlanes().get(1));
        assertSeamless(ramps);
        // Triangle 3 wide, 3 tall, 4 long.
        assertEquals(18.0, placedVolume(map), volumeTolerance(map));
    }

    @Test
    public void yawRotatedRampKeepsRotatedNormalsAndVolume() {
        SurfMapConverter.Converted map = convert("yaw_ramp.vmf");
        // Source slope normal (2,0,1) turned by atan2(3,4): (8,6,5)/sqrt(125) -> Minecraft (8,5,-6)/sqrt(125).
        double s = Math.sqrt(125);
        boolean sawSlope = false;
        boolean sawVerticalOblique = false;
        List<Voxelizer.RampCell> ramps = ramps(map);
        for (Voxelizer.RampCell ramp : ramps) {
            for (BrushPlane p : ramp.mapPlanes()) {
                assertFalse("axis faces never reach ramp cells", Polytope.isAxial(p));
                if (Math.abs(p.nx() - 8 / s) < EPS) {
                    assertEquals(5 / s, p.ny(), EPS);
                    assertEquals(-6 / s, p.nz(), EPS);
                    sawSlope = true;
                }
                if (p.ny() == 0) sawVerticalOblique = true;
            }
        }
        assertTrue(sawSlope);
        assertTrue(sawVerticalOblique);
        assertSeamless(ramps);
        // Rotation about the vertical keeps the 16 block^3 prism; only the bottom is axial (and on the grid).
        assertEquals(16.0, placedVolume(map), volumeTolerance(map));
    }

    @Test
    public void conversionIsDeterministic() {
        for (String name : List.of("ramp_prism.vmf", "ridge.vmf", "yaw_ramp.vmf", "box_room.vmf",
                "teleport.vmf")) {
            SurfMapConverter.Converted first = convert(name);
            SurfMapConverter.Converted second = convert(name);
            assertEquals(name, first.placements(), second.placements());
            assertEquals(name, first.summary(), second.summary());
            assertEquals(name, first.zones(), second.zones());
        }
        // Placement order is bottom-up.
        List<Voxelizer.Placement> placements = convert("box_room.vmf").placements();
        for (int i = 1; i < placements.size(); i++) {
            assertTrue(placements.get(i - 1).pos().getY() <= placements.get(i).pos().getY());
        }
    }

    @Test
    public void boxRoomThinWallsSnapOutwardWithoutLeaks() {
        SurfMapConverter.Converted map = convert("box_room.vmf");
        // Interior Source x,y 0..320, z 0..192 -> blocks x 0..8, z -8..0, y 0..4.8; 16-unit walls -> 1 block.
        Set<BlockPos> solid = solids(map);
        assertEquals(0, map.summary().rampCells());
        // Floor y=-1 spans x -1..8 and z -9..0 (outward snap of -0.4..8.4 and -8.4..0.4).
        for (int x = -1; x <= 8; x++) {
            for (int z = -9; z <= 0; z++) assertTrue(x + "," + z, solid.contains(new BlockPos(x, -1, z)));
        }
        // Closed shell: every interior cell is empty and every face neighbour outside it is solid.
        for (int x = 0; x < 8; x++) {
            for (int z = -8; z < 0; z++) {
                for (int y = 0; y < 4; y++) assertFalse(solid.contains(new BlockPos(x, y, z)));
            }
        }
        for (int y = 0; y <= 4; y++) {
            for (int z = -8; z < 0; z++) {
                assertTrue(solid.contains(new BlockPos(-1, y, z)));
                assertTrue(solid.contains(new BlockPos(8, y, z)));
            }
        }
        // Ceiling 192..208 -> rows 4.8..5.2 -> rows 4 and 5.
        assertTrue(solid.contains(new BlockPos(3, 5, -3)));
        assertTrue(solid.contains(new BlockPos(3, 4, -3)));
        // Materials: palette is a pure function of the texture name.
        String a = BrushClassifier.paletteBlock("csmc_test/surface_a");
        String b = BrushClassifier.paletteBlock("csmc_test/surface_b");
        boolean sawB = false;
        for (Voxelizer.Placement p : map.placements()) {
            String block = ((Voxelizer.SolidCell) p).block();
            assertTrue(block.equals(a) || block.equals(b));
            if (block.equals(b)) sawB = true;
        }
        assertTrue(sawB);
        // Spawns: CT at Source (160,160,0) facing yaw 90 (north) -> block (4,0,-4) facing Minecraft yaw -180.
        assertEquals(2, map.spawns().size());
        SurfMapConverter.Spawn ct = map.spawns().get(0);
        assertEquals("info_player_counterterrorist", ct.classname());
        assertEquals(new Vec3(4, 0, -4), ct.position());
        assertEquals(-180f, ct.yaw(), 1e-4);
        assertEquals(-90f, map.spawns().get(1).yaw(), 1e-4);
    }

    @Test
    public void brushEntitiesFollowSourceSolidity() {
        SurfMapConverter.Converted map = convert("func_detail.vmf");
        Set<BlockPos> solid = solids(map);
        // Each test brush is 2x2x2 blocks; x offsets 0, 5, 10, 15, 20, 25 blocks (Source 0..1000 step 200).
        assertTrue("func_detail", solid.contains(new BlockPos(0, 0, -1)));
        assertFalse("func_illusionary", solid.contains(new BlockPos(5, 0, -1)));
        assertFalse("func_brush never", solid.contains(new BlockPos(10, 0, -1)));
        assertTrue("func_brush always + disabled", solid.contains(new BlockPos(15, 0, -1)));
        assertTrue("func_wall", solid.contains(new BlockPos(20, 0, -1)));
        assertFalse("func_door", solid.contains(new BlockPos(25, 0, -1)));
        Map<String, Integer> skipped = map.summary().skipped();
        assertEquals(Integer.valueOf(1), skipped.get("func_illusionary (non-solid)"));
        assertEquals(Integer.valueOf(1), skipped.get("func_brush Solidity=Never"));
        assertEquals(Integer.valueOf(1), skipped.get("func_door (unsupported brush entity)"));
        assertEquals(4, map.summary().visibleBrushes());
    }

    @Test
    public void toolTexturesClassify() {
        SurfMapConverter.Converted map = convert("clip_tools.vmf");
        Map<BlockPos, String> blocks = new java.util.HashMap<>();
        for (Voxelizer.Placement p : map.placements()) {
            if (p instanceof Voxelizer.SolidCell s) blocks.put(s.pos(), s.block());
        }
        assertEquals("minecraft:barrier", blocks.get(new BlockPos(0, 0, -1)));   // toolsclip
        assertEquals("minecraft:barrier", blocks.get(new BlockPos(5, 0, -1)));   // toolsplayerclip
        assertEquals("minecraft:barrier", blocks.get(new BlockPos(10, 0, -1)));  // toolsnodraw
        assertNull(blocks.get(new BlockPos(15, 0, -1)));                         // skybox
        assertNull(blocks.get(new BlockPos(20, 0, -1)));                         // hint
        assertNull(blocks.get(new BlockPos(25, 0, -1)));                         // trigger texture
        assertNull(blocks.get(new BlockPos(30, 0, -1)));                         // displacement
        Map<String, Integer> skipped = map.summary().skipped();
        assertEquals(Integer.valueOf(1), skipped.get("skybox brush"));
        assertEquals(Integer.valueOf(1), skipped.get("hint/skip brush"));
        assertEquals(Integer.valueOf(1), skipped.get("trigger-textured brush"));
        assertEquals(Integer.valueOf(1), skipped.get("displacement brush"));
        assertEquals(1, map.summary().hiddenBlocksSkipped());
        assertEquals(4, map.summary().invisibleBrushes());
        // The sloped playerclip brush still yields ramp cells (there is no invisible ramp block).
        assertFalse(ramps(map).isEmpty());
        assertSeamless(ramps(map));
    }

    @Test
    public void teleportTriggersBecomeZones() {
        SurfMapConverter.Converted map = convert("teleport.vmf");
        assertEquals(2, map.zones().size());
        TeleportZone reset = map.zones().get(0);
        assertEquals("stage1_reset", reset.name());
        // Destination Source (400,-200,40) yaw 90 -> blocks (10, 1, 5), Minecraft yaw -180.
        assertEquals(new Vec3(10, 1, 5), reset.destination());
        assertTrue(reset.setAngles());
        assertEquals(-180f, reset.yaw(), 1e-4);
        assertNull(reset.landmark());
        // Trigger volume Source 0..80 cube -> blocks x 0..2, y 0..2, z -2..0 (not snapped).
        assertTrue(reset.touches(new AABB(0.7, 0, -1.3, 1.3, 1.8, -0.7)));
        assertFalse(reset.touches(new AABB(4.7, 0, -1.3, 5.3, 1.8, -0.7)));
        assertEquals(new Vec3(10, 1, 5), reset.target(new Vec3(1, 0, -1)));

        TeleportZone landmark = map.zones().get(1);
        assertFalse(landmark.setAngles());
        assertEquals(new Vec3(6, 0, -1), landmark.landmark());
        // Player 0.5 blocks east of the landmark keeps that offset from destination (25, 5, 0).
        assertEquals(new Vec3(25.5, 5.25, 0), landmark.target(new Vec3(6.5, 0.25, -1)));

        Map<String, Integer> skipped = map.summary().skipped();
        assertEquals(Integer.valueOf(1), skipped.get("trigger_teleport with filtername (filters unsupported)"));
        assertEquals(Integer.valueOf(1), skipped.get("trigger_teleport StartDisabled (entity I/O unsupported)"));
        assertEquals(Integer.valueOf(1), skipped.get("trigger_teleport without a resolvable target"));
        assertEquals(1, map.spawns().size());
        assertEquals(new Vec3(1, 0, 10), map.spawns().get(0).position());
        // Trigger volumes are not geometry: only the floor is placed (below y=0).
        for (Voxelizer.Placement p : map.placements()) assertEquals(-1, p.pos().getY());
        // Zones move with the origin.
        TeleportZone moved = reset.translated(100, 64, -30);
        assertEquals(new Vec3(110, 65, -25), moved.destination());
        assertTrue(moved.touches(new AABB(100.7, 64, -31.3, 101.3, 65.8, -30.7)));
    }

    @Test
    public void rejectsMalformedAndOversizedInput() {
        for (String name : List.of("malformed_unterminated.vmf", "malformed_braces.vmf", "malformed_plane.vmf",
                "malformed_nan.vmf", "out_of_range.vmf")) {
            assertThrows(name, SurfImportException.class, () -> convert(name));
        }
        assertTrue(assertThrows(SurfImportException.class, () -> convert("out_of_range.vmf")).getMessage()
                .contains("beyond"));
        assertThrows(SurfImportException.class, () -> SurfMapConverter.convert("versioninfo { }", ImportLimits.DEFAULT));
        String room = fixture("box_room.vmf");
        ImportLimits d = ImportLimits.DEFAULT;
        assertThrows(SurfImportException.class, () -> SurfMapConverter.convert(room, new ImportLimits(1000,
                d.maxDepth(), d.maxNodes(), d.maxPairsPerBlock(), d.maxTokenLength(), d.maxSolids(),
                d.maxSidesPerSolid(), d.maxEntities(), d.maxCoordinate(), d.maxCells())));
        assertThrows(SurfImportException.class, () -> SurfMapConverter.convert(room, new ImportLimits(d.maxBytes(),
                d.maxDepth(), d.maxNodes(), d.maxPairsPerBlock(), d.maxTokenLength(), 3,
                d.maxSidesPerSolid(), d.maxEntities(), d.maxCoordinate(), d.maxCells())));
        assertThrows(SurfImportException.class, () -> SurfMapConverter.convert(room, new ImportLimits(d.maxBytes(),
                d.maxDepth(), d.maxNodes(), d.maxPairsPerBlock(), d.maxTokenLength(), d.maxSolids(),
                d.maxSidesPerSolid(), d.maxEntities(), d.maxCoordinate(), 50)));
        assertThrows(SurfImportException.class, () -> SurfMapConverter.convert(room, new ImportLimits(d.maxBytes(),
                d.maxDepth(), d.maxNodes(), d.maxPairsPerBlock(), d.maxTokenLength(), d.maxSolids(),
                d.maxSidesPerSolid(), 1, d.maxCoordinate(), d.maxCells())));
    }

    /** 128-sided prisms (vbsp's side maximum): one converts into a ramp-faced column, many exhaust the budget. */
    @Test
    public void manySidedBrushesConvertButAreBudgeted() {
        StringBuilder one = new StringBuilder();
        appendCylinder(one, 126);
        SurfMapConverter.Converted column = SurfMapConverter.convert("world {\n" + one + "}\n", ImportLimits.DEFAULT);
        assertTrue(column.summary().rampCells() > 0);
        assertSeamless(ramps(column));
        StringBuilder many = new StringBuilder("world {\n");
        for (int i = 0; i < 60; i++) appendCylinder(many, 126);
        many.append("}\n");
        assertTrue(assertThrows(SurfImportException.class,
                () -> SurfMapConverter.convert(many.toString(), ImportLimits.DEFAULT)).getMessage()
                .contains("too complex"));
    }

    /** A vertical prism of radius 200 units with {@code faces} sides, z 0..80. */
    private static void appendCylinder(StringBuilder out, int faces) {
        out.append("solid {\n");
        for (int i = 0; i < faces; i++) {
            double a = 2 * Math.PI * i / faces;
            double c = Math.cos(a), s = Math.sin(a);
            double px = 200 * c, py = 200 * s;
            out.append(String.format(java.util.Locale.ROOT,
                    "side { \"plane\" \"(%.6f %.6f 0) (%.6f %.6f 0) (%.6f %.6f 1)\" \"material\" \"csmc_test/a\" }\n",
                    px - s, py + c, px, py, px, py));
        }
        out.append("side { \"plane\" \"(1 0 80) (0 0 80) (0 1 80)\" \"material\" \"csmc_test/a\" }\n");
        out.append("side { \"plane\" \"(0 1 0) (0 0 0) (1 0 0)\" \"material\" \"csmc_test/a\" }\n");
        out.append("}\n");
    }

    @Test
    public void openBrushIsSkippedNotPlaced() {
        SurfMapConverter.Converted map = convert("open_brush.vmf");
        assertTrue(map.placements().isEmpty());
        assertEquals(Integer.valueOf(1), map.summary().skipped().get("empty or open brush"));
    }

    @Test
    public void sourceAnglesMapToMinecraftYaw() {
        assertEquals(-90f, SourceTransform.yaw(0), 1e-4);   // east
        assertEquals(-180f, SourceTransform.yaw(90), 1e-4); // north
        assertEquals(90f, SourceTransform.yaw(180), 1e-4);  // west
        assertEquals(0f, SourceTransform.yaw(270), 1e-4);   // south
        assertEquals(0f, SourceTransform.yaw(-90), 1e-4);
        assertEquals(30f, SourceTransform.pitch(30), 1e-4);
        assertEquals(-90f, SourceTransform.pitch(-120), 1e-4);
    }
}
