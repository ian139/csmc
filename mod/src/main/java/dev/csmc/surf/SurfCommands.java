package dev.csmc.surf;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.csmc.movement.geometry.BrushPlane;
import java.math.BigInteger;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * {@code /csmc surf ramp <from> <to> <north|south|east|west> [angle] [filler]} and
 * {@code /csmc surf ridge <from> <to> <x|z> [angle] [filler]} (permission level 2). The ramp direction is the
 * upslope direction; the ridge axis is the crest direction. Angle in degrees, default {@link #DEFAULT_ANGLE}; filler
 * defaults to smooth stone. Registered under the shared {@code /csmc} root (brigadier merges literal children).
 */
public final class SurfCommands {
    /** Default slope: atan(2) = 63.43°, the same rise:run 2:1 as hand placement. */
    public static final double DEFAULT_ANGLE = Math.toDegrees(Math.atan(2.0));
    public static final double MIN_ANGLE = 5.0;
    public static final double MAX_ANGLE = 85.0;

    @FunctionalInterface
    private interface Shape {
        List<BrushPlane> planes(BoundingBox box, double angleDegrees);
    }

    private SurfCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        var rampTo = Commands.argument("to", BlockPosArgument.blockPos());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            rampTo.then(shape(Commands.literal(direction.getName()), context,
                    (box, angle) -> SurfBuilder.rampPlanes(box, direction, angle)));
        }
        var ridgeTo = Commands.argument("to", BlockPosArgument.blockPos());
        for (Direction.Axis axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
            ridgeTo.then(shape(Commands.literal(axis.getName()), context,
                    (box, angle) -> SurfBuilder.ridgePlanes(box, axis, angle)));
        }
        dispatcher.register(Commands.literal("csmc")
                .then(Commands.literal("surf").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("ramp")
                                .then(Commands.argument("from", BlockPosArgument.blockPos()).then(rampTo)))
                        .then(Commands.literal("ridge")
                                .then(Commands.argument("from", BlockPosArgument.blockPos()).then(ridgeTo)))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> shape(LiteralArgumentBuilder<CommandSourceStack> literal,
            CommandBuildContext context, Shape shape) {
        return literal.executes(c -> run(c, shape, DEFAULT_ANGLE, Blocks.SMOOTH_STONE.defaultBlockState()))
                .then(Commands.argument("angle", DoubleArgumentType.doubleArg(MIN_ANGLE, MAX_ANGLE))
                        .executes(c -> run(c, shape, DoubleArgumentType.getDouble(c, "angle"),
                                Blocks.SMOOTH_STONE.defaultBlockState()))
                        .then(Commands.argument("filler", BlockStateArgument.block(context))
                                .executes(c -> run(c, shape, DoubleArgumentType.getDouble(c, "angle"),
                                        BlockStateArgument.getBlock(c, "filler").getState()))));
    }

    private static int run(CommandContext<CommandSourceStack> c, Shape shape, double angle, BlockState filler)
            throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        BoundingBox box = BoundingBox.fromCorners(BlockPosArgument.getLoadedBlockPos(c, "from"),
                BlockPosArgument.getLoadedBlockPos(c, "to"));
        if (exceedsMaxVolume(box.getXSpan(), box.getYSpan(), box.getZSpan())) {
            source.sendFailure(Component.translatable("commands.csmc.surf.too_large",
                    exactVolume(box.getXSpan(), box.getYSpan(), box.getZSpan()).toString(), SurfBuilder.MAX_VOLUME));
            return 0;
        }
        if (!allChunksLoaded(source.getLevel(), box)) {
            source.sendFailure(Component.translatable("commands.csmc.surf.unloaded"));
            return 0;
        }
        SurfBuilder.Result result = SurfBuilder.build(source.getLevel(), box, shape.planes(box, angle), filler);
        source.sendSuccess(() -> Component.translatable("commands.csmc.surf.built", result.ramps(), result.filler()),
                true);
        return result.ramps() + result.filler();
    }

    /**
     * Whether a box of the given spans holds more than {@link SurfBuilder#MAX_VOLUME} cells. Overflow-safe: the
     * product is never formed in wrapping arithmetic (a 58,000,000 x 4064 x 58,000,000 box overflows {@code long}).
     */
    static boolean exceedsMaxVolume(int xSpan, int ySpan, int zSpan) {
        if (xSpan <= 0 || ySpan <= 0 || zSpan <= 0) return false;
        try {
            return Math.multiplyExact(Math.multiplyExact((long) xSpan, ySpan), zSpan) > SurfBuilder.MAX_VOLUME;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }

    /** Exact cell count of a box with the given spans, for the error message. */
    static BigInteger exactVolume(int xSpan, int ySpan, int zSpan) {
        return BigInteger.valueOf(xSpan).multiply(BigInteger.valueOf(ySpan)).multiply(BigInteger.valueOf(zSpan));
    }

    /**
     * Whether every chunk the box touches is already loaded at full status. Selections touching unloaded chunks are
     * rejected before any block changes. Builder writes also suppress neighbor/shape update cascades, so unchecked
     * adjacent chunks are not synchronously requested. Called only after the volume check, so the scan is bounded.
     */
    static boolean allChunksLoaded(ServerLevel level, BoundingBox box) {
        for (int cx = SectionPos.blockToSectionCoord(box.minX()); cx <= SectionPos.blockToSectionCoord(box.maxX());
                cx++) {
            for (int cz = SectionPos.blockToSectionCoord(box.minZ());
                    cz <= SectionPos.blockToSectionCoord(box.maxZ()); cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }
}
