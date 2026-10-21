package dev.csmc.surf;

import dev.csmc.movement.geometry.AnalyticCollision;
import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import dev.csmc.movement.geometry.MovementGeometry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class SurfRampGameTests implements FabricGameTest {
    private static List<BrushPlane> slope() {
        return List.of(BrushPlane.of(0, 1, 2, 2));
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void placeValidatesAndReportsChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.assertFalse(SurfRamps.place(level, pos, List.of()), "no planes must be rejected");
        helper.assertFalse(SurfRamps.place(level, pos, List.of(BrushPlane.of(0, 1, 0, -0.25))),
                "a solid below the cell must be rejected");
        helper.assertFalse(SurfRamps.place(level, pos, List.of(BrushPlane.of(1, 1, 0, 1.5),
                BrushPlane.of(-1, 1, 0, 0.5), BrushPlane.of(0, 1, 1, 1.5), BrushPlane.of(0, 1, -1, 0.5),
                BrushPlane.of(0, 1, 0, 0.9))), "five distinct planes must be rejected");
        helper.assertBlockNotPresent(Surf.SURF_RAMP, new BlockPos(1, 1, 1));
        helper.assertTrue(SurfRamps.place(level, pos, List.of(BrushPlane.of(0, 1, 2, 2), BrushPlane.of(0, 2, 4, 4))),
                "valid planes must place a ramp");
        helper.assertBlockPresent(Surf.SURF_RAMP, new BlockPos(1, 1, 1));
        helper.assertTrue(SurfRamps.localPlanes(level, pos).size() == 1, "duplicate planes must be merged");
        helper.assertFalse(SurfRamps.place(level, pos, slope()), "identical planes are no change");
        helper.assertTrue(SurfRamps.place(level, pos, List.of(BrushPlane.of(1, 1, 0, 1))), "new planes update");
        helper.assertTrue(SurfRamps.localPlanes(level, pos).equals(List.of(SurfRamps.canonicalPlane(
                BrushPlane.of(1, 1, 0, 1)))), "updated planes are stored");
        helper.assertTrue(SurfRamps.localPlanes(level, pos.above()).isEmpty(), "air has no ramp planes");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void planesSurviveSaveLoadAndClientSync(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        List<BrushPlane> planes = List.of(BrushPlane.of(0.3, 1, -0.7, 0.8), BrushPlane.of(-0.4, 1, 0.2, 0.6));
        helper.assertTrue(SurfRamps.place(level, pos, planes), "place");
        SurfRampBlockEntity ramp = (SurfRampBlockEntity) level.getBlockEntity(pos);
        List<BrushPlane> stored = ramp.geometry().planes();
        for (CompoundTag tag : List.of(ramp.saveWithFullMetadata(level.registryAccess()),
                ramp.getUpdateTag(level.registryAccess()))) {
            SurfRampBlockEntity copy = new SurfRampBlockEntity(pos, ramp.getBlockState());
            copy.loadWithComponents(tag, level.registryAccess());
            helper.assertTrue(copy.geometry().planes().equals(stored), "round trip keeps exact planes: " + tag);
            helper.assertTrue(copy.geometry() == ramp.geometry(), "equal plane sets share interned geometry");
        }
        helper.assertTrue(ramp.getUpdatePacket() != null, "ramps send block entity data to clients");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void onlyMovementQueriesOmitAnalyticRampShapes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.assertTrue(SurfRamps.place(level, pos, slope()), "place");
        Entity analytic = EntityType.ARMOR_STAND.create(level);
        Entity vanilla = EntityType.ARMOR_STAND.create(level);
        var state = level.getBlockState(pos);
        Predicate<Entity> previous = currentPredicate();
        AnalyticCollision.setPredicate(previous.or(entity -> entity == analytic));
        try {
            var inscribed = state.getCollisionShape(level, pos, CollisionContext.of(vanilla));
            helper.assertFalse(inscribed.isEmpty(), "other entities collide with the staircase");
            helper.assertTrue(state.getCollisionShape(level, pos, CollisionContext.empty()).equals(inscribed),
                    "no-entity contexts get the staircase");
            helper.assertTrue(state.getCollisionShape(level, pos, CollisionContext.of(analytic)).equals(inscribed),
                    "direct analytic-entity queries see solid cover");
            AABB region = new AABB(pos).deflate(0.01);
            helper.assertFalse(level.getBlockCollisions(analytic, region).iterator().hasNext(),
                    "movement iterator omits analytic ramp boxes");
            helper.assertTrue(level.getBlockCollisions(vanilla, region).iterator().hasNext(),
                    "movement off retains the staircase");
            BrushPlane world = SurfRamps.worldPlanes(level, pos).get(0);
            for (AABB box : inscribed.move(pos.getX(), pos.getY(), pos.getZ()).toAabbs()) {
                helper.assertTrue(world.signedDistance(box.minX, box.maxY, box.maxZ) <= 1.0E-9
                        && world.signedDistance(box.maxX, box.maxY, box.maxZ) <= 1.0E-9,
                        "staircase box above the slope: " + box);
            }
            helper.assertFalse(state.getShape(level, pos).isEmpty(), "ramps stay selectable");
            helper.assertFalse(state.isSuffocating(level, pos), "ramps never suffocate");
            helper.assertFalse(state.canOcclude(), "ramps never occlude neighbours");
            helper.assertTrue(state.getLightBlock(level, pos) == 0, "ramps let light through");
        } finally {
            AnalyticCollision.setPredicate(previous);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void providerEmitsWorldBrushesOnlyForRampCells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ramp = helper.absolutePos(new BlockPos(4, 2, 4));
        helper.setBlock(new BlockPos(5, 2, 4), Blocks.STONE);
        helper.assertTrue(SurfRamps.place(level, ramp, slope()), "place");
        List<ConvexBrush> brushes = new ArrayList<>();
        AABB region = new AABB(ramp).inflate(1.5);
        MovementGeometry.collectAll(level, region, brushes::add);
        List<ConvexBrush> ours = brushes.stream().filter(b -> b.bounds().intersects(new AABB(ramp))).toList();
        helper.assertTrue(ours.size() == 1, "exactly one brush for one ramp cell, got " + ours.size());
        ConvexBrush brush = ours.get(0);
        List<BrushPlane> expected = new ArrayList<>(SurfRamps.worldPlanes(level, ramp));
        expected.addAll(ConvexBrush.box(new AABB(ramp)).planes());
        helper.assertTrue(new HashSet<>(brush.planes()).equals(new HashSet<>(expected)),
                "brush = world planes + cell faces: " + brush.planes());
        BrushPlane slopeWorld = SurfRamps.worldPlanes(level, ramp).get(0);
        helper.assertTrue(slopeWorld.signedDistance(ramp.getX() + 0.5, ramp.getY() + 0.25, ramp.getZ() + 0.5) < 0,
                "world plane keeps the lower part solid");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void rampBuilderProducesOneContinuousPlane(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos max = helper.absolutePos(new BlockPos(3, 6, 2));
        BoundingBox box = BoundingBox.fromCorners(min, max);
        List<BrushPlane> planes = SurfBuilder.rampPlanes(box, Direction.EAST, SurfCommands.DEFAULT_ANGLE);
        SurfBuilder.Result result = SurfBuilder.build(level, box, planes, Blocks.SMOOTH_STONE.defaultBlockState());
        helper.assertTrue(result.ramps() > 0 && result.filler() > 0, "ramp and filler cells: " + result);
        Set<List<BrushPlane>> worldSets = new HashSet<>();
        int ramps = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            var state = level.getBlockState(pos);
            double surface = box.minY() + Math.tan(Math.toRadians(SurfCommands.DEFAULT_ANGLE))
                    * (pos.getX() + 0.5 - box.minX());
            if (state.is(Surf.SURF_RAMP)) {
                worldSets.add(SurfRamps.worldPlanes(level, pos));
                ramps++;
            } else if (state.is(Blocks.SMOOTH_STONE)) {
                helper.assertTrue(pos.getY() + 1 <= surface, "filler only fully below the surface at " + pos);
            } else {
                helper.assertTrue(pos.getY() >= surface - 1.0, "air only above the surface at " + pos);
            }
        }
        helper.assertTrue(ramps == result.ramps(), "counted ramps");
        helper.assertTrue(worldSets.equals(Set.of(planes)),
                "every ramp cell reproduces the builder's world plane bit-exactly: " + worldSets);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void ridgeBuilderCellsCarryBothSlopes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos max = helper.absolutePos(new BlockPos(2, 6, 5));
        BoundingBox box = BoundingBox.fromCorners(min, max);
        List<BrushPlane> planes = SurfBuilder.ridgePlanes(box, Direction.Axis.X, 60);
        SurfBuilder.Result result = SurfBuilder.build(level, box, planes, Blocks.STONE.defaultBlockState());
        helper.assertTrue(result.ramps() > 0, "ridge cells: " + result);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(pos).is(Surf.SURF_RAMP)) continue;
            List<BrushPlane> world = SurfRamps.worldPlanes(level, pos);
            helper.assertTrue(world.size() == 2 && new HashSet<>(world).equals(new HashSet<>(planes)),
                    "ridge cell " + pos + " carries both world planes: " + world);
        }
        // Odd width (5): the crest runs through the middle of the z = 3 cells at height 1 + 2.5·tan 60° ≈ 5.33, so
        // the cell at y = 5 is cut by both slopes.
        BlockPos crest = helper.absolutePos(new BlockPos(1, 5, 3));
        RampGeometry geometry = SurfRamps.geometry(level, crest);
        helper.assertTrue(geometry != null && geometry.polygons().stream().filter(p -> p.cullFace() == null
                && p.ny() > 0 && p.ny() < 0.99).count() >= 2, "crest cell shows both slopes");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void handPlacementContinuesNeighbourOrUsesFacingDefault(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 1, 4));
        List<BrushPlane> world = List.of(SurfRamps.slopePlane(0, -1, 2.0, -(base.getZ() + 1.0), base.getY()));
        helper.assertTrue(SurfRamps.placeWorld(level, base, world), "seed ramp");
        List<BrushPlane> above = SurfRampItem.planesFor(level, base.above(), Direction.DOWN, Direction.EAST);
        helper.assertTrue(SurfRamps.toLocal(world, base.above()).equals(above),
                "placing on top of a ramp continues its world plane");
        BlockPos lone = helper.absolutePos(new BlockPos(6, 1, 1));
        List<BrushPlane> east = SurfRampItem.planesFor(level, lone, null, Direction.EAST);
        BrushPlane plane = east.get(0);
        helper.assertTrue(plane.nx() < 0 && plane.ny() > 0 && Math.abs(plane.nz()) == 0.0,
                "default slope rises east: " + plane);
        helper.assertTrue(SurfRampItem.defaultPlanes(Direction.NORTH).equals(SurfRamps.defaultGeometry().planes()),
                "documented default (north) matches the block entity default");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void builderRejectsSelectionsTouchingUnloadedChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(0, 1, 0));
        helper.assertTrue(SurfCommands.allChunksLoaded(level, BoundingBox.fromCorners(a, a.offset(4, 4, 4))),
                "test area is loaded");
        BlockPos far = new BlockPos(20_000_000, 64, 20_000_000);
        helper.assertFalse(SurfCommands.allChunksLoaded(level, BoundingBox.fromCorners(far, far.offset(40, 0, 0))),
                "a selection in unloaded chunks is rejected");
        helper.assertTrue(level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null,
                "the check never loads or generates the chunk");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void preparedChunkPlacementDoesNotLoadAnAdjacentChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 93_750, cz = 93_750;
        level.getChunk(cx, cz); // The importer prepares just the target chunk before calling the public API.
        helper.assertTrue(level.getChunkSource().getChunkNow(cx - 1, cz) == null,
                "the adjacent chunk starts unloaded");
        BlockPos pos = new BlockPos(cx << 4, 64, (cz << 4) + 8);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        // An intentionally unsupported torch detects a shape-update cascade even when getChunkNow cannot expose
        // short-lived synchronous reads. Import placement must leave neighbors untouched.
        level.setBlock(pos.above(), Blocks.TORCH.defaultBlockState(), flags);
        List<BrushPlane> world = List.of(SurfRamps.toWorld(SurfRamps.defaultGeometry().planes().get(0), pos));
        helper.assertTrue(SurfRamps.placeWorld(level, pos, world, flags), "place in the prepared target chunk");
        helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.TORCH),
                "explicit import flags suppress neighbor shape updates");
        helper.assertTrue(level.getChunkSource().getChunkNow(cx - 1, cz) == null,
                "placing at a chunk edge must not load its neighbor");
        List<BrushPlane> changed = List.of(SurfRamps.toWorld(BrushPlane.of(1, 1, 0, 1), pos));
        helper.assertTrue(SurfRamps.placeWorld(level, pos, changed, flags), "update the existing ramp geometry");
        helper.assertTrue(level.getChunkSource().getChunkNow(cx - 1, cz) == null,
                "geometry updates must not load the neighbor either");
        helper.assertTrue(level.getChunk(cx, cz).isUnsaved(), "the prepared chunk is marked dirty");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void builderBoundaryWritesNeverCascadeIntoUnloadedChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 93_760, cz = 93_760;
        level.getChunk(cx, cz); // Only the target is FULL-ready, not its western neighbor.
        BlockPos pos = new BlockPos(cx * 16, 64, cz * 16 + 8);
        BoundingBox box = BoundingBox.fromCorners(pos, pos);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        level.setBlock(pos.above(), Blocks.TORCH.defaultBlockState(), flags);
        helper.assertTrue(SurfCommands.allChunksLoaded(level, box), "target passes command preflight");
        helper.assertTrue(level.getChunkSource().getChunkNow(cx - 1, cz) == null, "west starts unloaded");
        SurfBuilder.Result ramp = SurfBuilder.build(level, box,
                SurfBuilder.rampPlanes(box, Direction.EAST, SurfCommands.DEFAULT_ANGLE),
                Blocks.SMOOTH_STONE.defaultBlockState());
        helper.assertTrue(ramp.ramps() == 1, "actual boundary ramp built: " + ramp);
        helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.TORCH), "ramp suppresses shape callbacks");
        helper.assertTrue(level.getChunkSource().getChunkNow(cx - 1, cz) == null, "ramp leaves west unloaded");
        SurfBuilder.Result fill = SurfBuilder.build(level, box, List.of(new BrushPlane(0, 1, 0, 66)),
                Blocks.SMOOTH_STONE.defaultBlockState());
        helper.assertTrue(fill.filler() == 1, "actual boundary filler built: " + fill);
        helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.TORCH), "filler suppresses shape callbacks");
        helper.assertTrue(level.getChunkSource().getChunkNow(cx - 1, cz) == null, "filler leaves west unloaded");
        // An existing ramp's geometry update must use the same flags.
        SurfBuilder.build(level, box, SurfBuilder.rampPlanes(box, Direction.EAST, 45),
                Blocks.SMOOTH_STONE.defaultBlockState());
        SurfBuilder.Result updated = SurfBuilder.build(level, box,
                SurfBuilder.rampPlanes(box, Direction.EAST, 60), Blocks.SMOOTH_STONE.defaultBlockState());
        helper.assertTrue(updated.ramps() == 1, "existing geometry changed");
        helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.TORCH), "geometry suppresses callbacks");
        helper.assertTrue(level.getChunkSource().getChunkNow(cx - 1, cz) == null, "geometry leaves west unloaded");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void handPlacementFailsWhenTheSelectedGeometryWouldTrapAnEntity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos support = helper.absolutePos(new BlockPos(5, 1, 5));
        level.setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos target = support.above();
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(5, 2, 5));
        Predicate<Entity> previous = currentPredicate();
        try {
            AnalyticCollision.setPredicate(entity -> false); // movement off: nobody uses analytic collision
            helper.assertTrue(placeOnTop(level, support) == InteractionResult.FAIL,
                    "placing into a mob's cell must fail with movement off");
            helper.assertTrue(level.getBlockState(target).isAir(), "failed placement leaves the cell untouched");
            AnalyticCollision.setPredicate(entity -> entity == zombie);
            helper.assertTrue(placeOnTop(level, support).consumesAction(),
                    "analytic entities do not obstruct (their vanilla collision is empty)");
            helper.assertTrue(SurfRamps.localPlanes(level, target).equals(SurfRamps.validate(
                    SurfRampItem.planesFor(level, target, Direction.DOWN, Direction.NORTH)).planes()),
                    "placement installs the geometry that was checked");
        } finally {
            AnalyticCollision.setPredicate(previous);
        }
        zombie.discard();
        helper.succeed();
    }

    private static InteractionResult placeOnTop(ServerLevel level, BlockPos support) {
        ItemStack stack = new ItemStack(Surf.SURF_RAMP_ITEM);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
        return ((BlockItem) stack.getItem()).place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack,
                hit));
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void rampDropsItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 6));
        helper.assertTrue(SurfRamps.place(level, pos, slope()), "place");
        List<ItemStack> drops = Block.getDrops(level.getBlockState(pos), level, pos, level.getBlockEntity(pos));
        helper.assertTrue(drops.size() == 1 && drops.get(0).is(Surf.SURF_RAMP_ITEM), "drops: " + drops);
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Predicate<Entity> currentPredicate() {
        try {
            Field field = AnalyticCollision.class.getDeclaredField("predicate");
            field.setAccessible(true);
            return (Predicate<Entity>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
