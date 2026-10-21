package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.surf.Surf;
import dev.csmc.surf.SurfRamps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** End-to-end surf map import: command, batched placement through SurfRamps, persisted teleport zone. */
public final class SurfImportGameTests implements FabricGameTest {
    private static final String MAP = "csmc_gametest_import";

    /**
     * Synthetic map: the straight ramp prism (Source x 0..80, y 0..160, z 0..160, slope 2x + z <= 160), a nodraw box,
     * a trigger_teleport cube at Source x 200..280, y 0..80, z 0..80 and its destination at Source (120, -40, 40).
     */
    static String vmf() {
        String prism = solid(side(new int[] {2, 0, 1}, new int[] {80, 0, 0}, "csmc_test/surface_a"),
                side(new int[] {-1, 0, 0}, new int[] {0, 0, 0}, "csmc_test/surface_a"),
                side(new int[] {0, 0, -1}, new int[] {0, 0, 0}, "csmc_test/surface_a"),
                side(new int[] {0, 1, 0}, new int[] {0, 160, 0}, "csmc_test/surface_a"),
                side(new int[] {0, -1, 0}, new int[] {0, 0, 0}, "csmc_test/surface_a"));
        String nodraw = box(120, 120, 0, 160, 160, 40, "TOOLS/TOOLSNODRAW");
        String trigger = box(200, 0, 0, 280, 80, 80, "TOOLS/TOOLSTRIGGER");
        return "versioninfo\n{\n\t\"formatversion\" \"100\"\n}\n"
                + "world\n{\n\t\"id\" \"1\"\n\t\"classname\" \"worldspawn\"\n" + prism + nodraw + "}\n"
                + "entity\n{\n\t\"classname\" \"trigger_teleport\"\n\t\"targetname\" \"reset\"\n"
                + "\t\"target\" \"dest\"\n\t\"spawnflags\" \"1\"\n" + trigger + "}\n"
                + "entity\n{\n\t\"classname\" \"info_teleport_destination\"\n\t\"targetname\" \"dest\"\n"
                + "\t\"origin\" \"120 -40 40\"\n\t\"angles\" \"0 180 0\"\n}\n"
                + "entity\n{\n\t\"classname\" \"info_player_counterterrorist\"\n\t\"origin\" \"40 -40 0\"\n"
                + "\t\"angles\" \"0 0 0\"\n}\n";
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void importPlacesRampsSolidsAndTeleportZone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Path dir = SurfImport.mapDirectory(level.getServer());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(MAP + ".vmf"), vmf());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Source origin lands on this block; map rows z -4..-1 stay inside the 8x8x8 test structure.
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 6));
        CommandSourceStack source = level.getServer().createCommandSourceStack().withLevel(level)
                .withPosition(Vec3.atLowerCornerOf(origin)).withPermission(2).withSuppressedOutput();
        String command = "csmc surf import " + MAP + " " + origin.getX() + " " + origin.getY() + " " + origin.getZ();
        int result;
        try {
            result = level.getServer().getCommands().getDispatcher().execute(command, source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError(e);
        }
        helper.assertTrue(result == 1, "import command was rejected");

        // Expected slope in world space: map-local 2x + y <= 4 (normalised), canonicalised in map-local space and only
        // then moved by the integer origin (the importer's order; the stored solid is origin-independent).
        BrushPlane canonical = SurfRamps.translateCanonical(SurfRamps.canonicalPlane(BrushPlane.of(2, 1, 0, 4)),
                origin.getX(), origin.getY(), origin.getZ());
        ServerPlayer[] player = new ServerPlayer[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertFalse(SurfImport.busy(), "import still running");
                    helper.assertTrue(SurfImportData.get(level).maps().containsKey(MAP), "map not recorded");
                })
                .thenExecute(() -> {
                    // Full cells under the slope are palette concrete; the nodraw box is a barrier.
                    BlockState full = level.getBlockState(origin.offset(0, 0, -1));
                    helper.assertTrue(full.getBlock().builtInRegistryHolder().key().location().getPath()
                            .endsWith("_concrete"), "expected concrete under the slope, got " + full);
                    helper.assertTrue(level.getBlockState(origin.offset(3, 0, -4)).is(Blocks.BARRIER),
                            "nodraw brush should be a barrier");
                    // Ramp cells carry the slope plane, bit-identical across neighbours (seam-free).
                    for (BlockPos rel : List.of(new BlockPos(0, 2, -1), new BlockPos(0, 2, -2),
                            new BlockPos(0, 3, -4), new BlockPos(1, 0, -3), new BlockPos(1, 1, -2))) {
                        BlockPos pos = origin.offset(rel);
                        helper.assertTrue(level.getBlockState(pos).is(Surf.SURF_RAMP), "no surf ramp at " + rel);
                        List<BrushPlane> world = SurfRamps.worldPlanes(level, pos);
                        helper.assertTrue(world.equals(List.of(canonical)),
                                "ramp " + rel + " planes " + world + " != " + canonical);
                    }
                    helper.assertTrue(SurfRamps.localPlanes(level, origin.offset(0, 2, -1)).size() == 1,
                            "one local plane expected");
                    helper.assertTrue(level.getBlockState(origin.offset(1, 2, -1)).isAir(),
                            "cell above the slope must stay empty");
                    // A player stepping into the trigger cube (blocks x 5..7, y 0..2, z -2..0) is moved to the
                    // destination: Source (120,-40,40) -> map block (3, 1, 1), facing Source yaw 180 = MC yaw 90.
                    player[0] = helper.makeMockServerPlayerInLevel();
                    player[0].moveTo(origin.getX() + 6.0, origin.getY() + 0.0, origin.getZ() - 1.0, 0.0F, 0.0F);
                })
                .thenWaitUntil(() -> {
                    Vec3 at = player[0].position();
                    Vec3 expected = Vec3.atLowerCornerOf(origin).add(3, 1, 1);
                    helper.assertTrue(at.distanceTo(expected) < 1e-6, "player at " + at + ", expected " + expected);
                    helper.assertTrue(Math.abs(player[0].getYRot() - 90.0F) < 1e-3, "yaw " + player[0].getYRot());
                })
                .thenExecute(() -> {
                    // info_player_counterterrorist at Source (40,-40,0) -> map block (1, 0, 1).
                    CommandSourceStack asPlayer = player[0].createCommandSourceStack().withPermission(2)
                            .withSuppressedOutput();
                    int moved;
                    try {
                        moved = level.getServer().getCommands().getDispatcher()
                                .execute("csmc surf spawn " + MAP, asPlayer);
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                        throw new AssertionError(e);
                    }
                    helper.assertTrue(moved == 1, "spawn command failed");
                    Vec3 spawn = Vec3.atLowerCornerOf(origin).add(1, 0, 1);
                    helper.assertTrue(player[0].position().distanceTo(spawn) < 1e-6,
                            "player at " + player[0].position() + ", spawn " + spawn);
                })
                .thenExecute(() -> {
                    level.getServer().getPlayerList().remove(player[0]);
                    player[0].discard();
                    // The record survives a save/load cycle unchanged (zones persist across restarts).
                    SurfImportData data = SurfImportData.get(level);
                    SurfImportData reloaded = SurfImportData.load(
                            data.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()),
                            level.registryAccess());
                    helper.assertTrue(reloaded.maps().get(MAP).equals(data.maps().get(MAP)),
                            "saved surf map record differs after reload");
                    helper.assertTrue(reloaded.zoneCount() == data.zoneCount(), "zones lost on reload");
                    data.remove(MAP);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void crossColumnTeleportKeepsFileOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SurfImportData data = SurfImportData.get(level);
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        int edge = ((origin.getX() >> 4) + 1) * 16;
        double y = origin.getY(), z = origin.getZ() + 1;
        Vec3 targetA = Vec3.atLowerCornerOf(origin).add(2, 3, 2);
        Vec3 targetB = Vec3.atLowerCornerOf(origin).add(5, 3, 2);
        TeleportZone a = new TeleportZone("A", List.of(dev.csmc.movement.geometry.ConvexBrush.box(
                new net.minecraft.world.phys.AABB(edge, y, z, edge + 1, y + 2, z + 1))),
                targetA, 0, 0, true, null);
        TeleportZone b = new TeleportZone("B", List.of(dev.csmc.movement.geometry.ConvexBrush.box(
                new net.minecraft.world.phys.AABB(edge - 1, y, z, edge - 0.1, y + 2, z + 1))),
                targetB, 0, 0, true, null);
        String name = "csmc_gametest_precedence";
        data.put(name, new SurfImportData.MapRecord(new SurfImportData.BlockPosRecord(0, 0, 0),
                List.of(a, b), List.of()));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(edge - 0.2, y, z + 0.5, 0, 0);
        helper.assertTrue(a.touches(player.getBoundingBox()) && b.touches(player.getBoundingBox()),
                "player overlaps both columns' zones");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(player.position().distanceTo(targetA) < 1e-6,
                "first file-order zone must win, player at " + player.position()))
                .thenExecute(() -> {
                    data.remove(name);
                    level.getServer().getPlayerList().remove(player);
                    player.discard();
                }).thenSucceed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void importRejectsTraversalNames(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommandSourceStack source = level.getServer().createCommandSourceStack().withLevel(level)
                .withPermission(2).withSuppressedOutput();
        for (String name : List.of("..", "a.b", "missing_map_xyz")) {
            int result;
            try {
                result = level.getServer().getCommands().getDispatcher().execute("csmc surf import " + name, source);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                result = 0;
            }
            helper.assertTrue(result == 0, "import of '" + name + "' must be refused");
        }
        CommandSourceStack player = source.withPermission(0);
        boolean denied;
        try {
            denied = level.getServer().getCommands().getDispatcher().execute("csmc surf import x", player) == 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            denied = true;
        }
        helper.assertTrue(denied, "permission level 0 must not import");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void fitChecksCoverOverflowSpawnsDestinationsAndTeleportTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(0, 1, 6));
        SurfMapConverter.Converted map = SurfMapConverter.convert(vmf(), ImportLimits.DEFAULT);
        helper.assertTrue(SurfImport.fitProblem(MAP, map, level, here) == null, "map must fit at the test origin");
        // Origin y = Integer.MAX_VALUE would wrap the translated rows in int arithmetic.
        helper.assertTrue(SurfImport.fitProblem(MAP, map, level, new BlockPos(here.getX(), Integer.MAX_VALUE,
                here.getZ())) != null, "origin y Integer.MAX_VALUE must be rejected");
        helper.assertTrue(SurfImport.fitProblem(MAP, map, level, new BlockPos(here.getX(), Integer.MIN_VALUE,
                here.getZ())) != null, "origin y Integer.MIN_VALUE must be rejected");

        // A map without cells is still validated: its spawn must lie inside the world border.
        String spawnOnly = "versioninfo\n{\n\t\"formatversion\" \"100\"\n}\n"
                + "world\n{\n\t\"id\" \"1\"\n\t\"classname\" \"worldspawn\"\n}\n"
                + "entity\n{\n\t\"classname\" \"info_player_counterterrorist\"\n\t\"origin\" \"0 0 0\"\n}\n";
        SurfMapConverter.Converted spawns = SurfMapConverter.convert(spawnOnly, ImportLimits.DEFAULT);
        helper.assertTrue(spawns.placements().isEmpty() && spawns.spawns().size() == 1, "spawn-only fixture");
        helper.assertTrue(SurfImport.fitProblem("spawn_only", spawns, level, here) == null, "spawn fits here");
        int outside = (int) level.getWorldBorder().getMaxX() + 16;
        helper.assertTrue(SurfImport.fitProblem("spawn_only", spawns, level, new BlockPos(outside, here.getY(),
                here.getZ())) != null, "spawn beyond the border must be rejected");
        // A zone-only map with an in-world trigger and out-of-world destination must be rejected for its destination,
        // independently of cell and trigger bounds.
        TeleportZone localZone = map.zones().get(0);
        // Destination is local to here: keep its translated world X outside at every random test origin.
        TeleportZone badDestination = new TeleportZone("bad_destination", localZone.volumes(),
                new Vec3(outside - (double) here.getX(), 1, 0), 0, 0, true, null);
        SurfMapConverter.Converted zoneOnly = new SurfMapConverter.Converted(List.of(), List.of(badDestination),
                List.of(), map.summary());
        String problem = SurfImport.fitProblem("zone_only", zoneOnly, level, here);
        helper.assertTrue(problem != null && problem.contains("destination"),
                "out-of-world destination must be rejected: " + problem);

        // Teleport time re-checks the target against the current world.
        TeleportZone away = new TeleportZone("away", map.zones().get(0).volumes(),
                new Vec3(outside, here.getY(), here.getZ()), 0, 0, true, null);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(here.getX() + 0.5, here.getY(), here.getZ() + 0.5, 0.0F, 0.0F);
        Vec3 before = player.position();
        helper.assertFalse(SurfImport.teleport(player, level, away), "teleport outside the border must be refused");
        helper.assertTrue(player.position().equals(before), "player must not move");
        // target = destination + player - landmark; choose a landmark that yields world X == outside.
        TeleportZone landmark = new TeleportZone("landmark", localZone.volumes(), before, 0, 0, false,
                new Vec3(2.0 * before.x - outside, here.getY(), here.getZ()));
        helper.assertFalse(SurfImport.teleport(player, level, landmark),
                "computed landmark target outside the border must be refused");
        TeleportZone tooHigh = new TeleportZone("too_high", localZone.volumes(),
                new Vec3(before.x, level.getMaxBuildHeight(), before.z), 0, 0, true, null);
        helper.assertFalse(SurfImport.teleport(player, level, tooHigh), "exclusive build-height maximum");
        level.getServer().getPlayerList().remove(player);
        player.discard();

        // Placement never generates a chunk synchronously: an unloaded chunk is requested and reported not ready.
        BlockPos far = new BlockPos(1_000_000, here.getY(), 1_000_000);
        helper.assertTrue(level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null,
                "far chunk loaded");
        helper.assertFalse(new LevelImportSink(level).prepare(far), "unloaded chunk must not be ready");
        helper.assertTrue(level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null,
                "prepare must not load the chunk synchronously");
        helper.assertTrue(new LevelImportSink(level).prepare(here), "loaded chunk is ready");
        helper.succeed();
    }

    private static String box(int x0, int y0, int z0, int x1, int y1, int z1, String material) {
        return solid(side(new int[] {1, 0, 0}, new int[] {x1, y0, z0}, material),
                side(new int[] {-1, 0, 0}, new int[] {x0, y0, z0}, material),
                side(new int[] {0, 1, 0}, new int[] {x0, y1, z0}, material),
                side(new int[] {0, -1, 0}, new int[] {x0, y0, z0}, material),
                side(new int[] {0, 0, 1}, new int[] {x0, y0, z1}, material),
                side(new int[] {0, 0, -1}, new int[] {x0, y0, z0}, material));
    }

    private static String solid(String... sides) {
        return "\tsolid\n\t{\n" + String.join("", sides) + "\t}\n";
    }

    /** A side through {@code point} with outward {@code normal}, wound so (p0-p1)x(p2-p1) points along it. */
    private static String side(int[] n, int[] point, String material) {
        int[] t1 = Math.abs(n[2]) < Math.max(Math.abs(n[0]), Math.abs(n[1])) ? cross(n, new int[] {0, 0, 1})
                : cross(n, new int[] {1, 0, 0});
        if (t1[0] == 0 && t1[1] == 0 && t1[2] == 0) t1 = cross(n, new int[] {0, 1, 0});
        int[] t2 = cross(n, t1);
        int[] p0 = {point[0] + t1[0], point[1] + t1[1], point[2] + t1[2]};
        int[] p2 = {point[0] + t2[0], point[1] + t2[1], point[2] + t2[2]};
        return "\t\tside\n\t\t{\n\t\t\t\"plane\" \"(" + p0[0] + " " + p0[1] + " " + p0[2] + ") (" + point[0] + " "
                + point[1] + " " + point[2] + ") (" + p2[0] + " " + p2[1] + " " + p2[2] + ")\"\n\t\t\t\"material\" \""
                + material + "\"\n\t\t}\n";
    }

    private static int[] cross(int[] a, int[] b) {
        return new int[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
