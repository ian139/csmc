package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.surf.SurfRamps;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Writes import cells into a level. Full blocks are set without neighbour updates (clients are still notified) so a
 * large import does not cascade block updates; surf ramps go through {@link SurfRamps#placeWorld} with planes that are
 * already canonical (translated by {@link SurfRamps#translateCanonical}), so every cell cut by the same brush face
 * carries bit-identical planes whatever the import origin. Cells are written only into chunks that are already loaded;
 * others are requested with a ticket and written on a later tick, so no write generates a chunk synchronously.
 */
public final class LevelImportSink implements ImportSink {
    /**
     * Ticket that loads (and, where needed, generates) a chunk asynchronously on the chunk system; it expires
     * {@code 300} ticks after it was last refreshed, so an abandoned import leaves nothing loaded.
     */
    static final TicketType<ChunkPos> IMPORT_TICKET =
            TicketType.create("csmc_surf_import", Comparator.comparingLong(ChunkPos::toLong), 300);

    private final ServerLevel level;
    private final Map<String, BlockState> states = new HashMap<>();

    public LevelImportSink(ServerLevel level) {
        this.level = level;
    }

    /** Writes only into loaded full chunks; otherwise requests the chunk asynchronously and reports not ready. */
    @Override
    public boolean prepare(BlockPos pos) {
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());
        // hasChunk only checks a holder's ticket level; it can be true while generation is still pending.
        if (level.getChunkSource().getChunkNow(cx, cz) != null) return true;
        ChunkPos chunk = new ChunkPos(cx, cz);
        level.getChunkSource().addRegionTicket(IMPORT_TICKET, chunk, 0, chunk);
        return false;
    }

    @Override
    public boolean solid(BlockPos pos, String block) {
        BlockState state = states.computeIfAbsent(block, id -> BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalStateException("unknown palette block " + id))
                .defaultBlockState());
        if (level.getBlockState(pos) == state) return false;
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    @Override
    public boolean ramp(BlockPos pos, List<BrushPlane> worldPlanes) {
        return SurfRamps.placeWorld(level, pos, worldPlanes, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
