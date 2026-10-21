package dev.csmc.surf;

import dev.csmc.CsmcMod;
import dev.csmc.movement.geometry.BrushPlane;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.blockview.v2.RenderDataBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds one surf ramp cell's canonical block-local planes. Persisted as {@code planes: [{nx, ny, nz, d}, ...]}
 * (doubles, exact), synced to clients through the block entity update tag/packet; a client-side change re-renders the
 * section. The geometry is the render data read by the client model.
 */
public final class SurfRampBlockEntity extends BlockEntity implements RenderDataBlockEntity {
    static final String PLANES = "planes";

    private volatile RampGeometry geometry = SurfRamps.defaultGeometry();

    public SurfRampBlockEntity(BlockPos pos, BlockState state) {
        super(Surf.SURF_RAMP_ENTITY, pos, state);
    }

    public RampGeometry geometry() {
        return geometry;
    }

    /** Replaces the planes, marks the chunk dirty and pushes the change to clients. */
    void setGeometry(RampGeometry value, int updateFlags) {
        geometry = value;
        // BlockEntity.setChanged also notifies comparators (which reads neighbor chunks). An importer requesting
        // no neighbor updates must only mark its already-loaded target chunk dirty.
        if (level != null && (updateFlags & Block.UPDATE_NEIGHBORS) == 0) level.blockEntityChanged(worldPosition);
        else setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, updateFlags);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (BrushPlane plane : geometry.planes()) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("nx", plane.nx());
            entry.putDouble("ny", plane.ny());
            entry.putDouble("nz", plane.nz());
            entry.putDouble("d", plane.distance());
            list.add(entry);
        }
        tag.put(PLANES, list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        RampGeometry loaded = tag.contains(PLANES, Tag.TAG_LIST) ? decode(tag.getList(PLANES, Tag.TAG_COMPOUND))
                : null;
        if (loaded == null) {
            CsmcMod.LOGGER.warn("[csmc surf] Invalid surf ramp planes at {}; using the default slope", worldPosition);
            loaded = SurfRamps.defaultGeometry();
        }
        boolean changed = loaded != geometry;
        geometry = loaded;
        if (changed && level != null && level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_IMMEDIATE);
        }
    }

    /**
     * Geometry of a persisted/synced plane list, or {@code null} when it is invalid. Lists outside
     * 1..{@link SurfRamps#MAX_PLANES} entries are rejected before any plane is allocated or decoded, so an oversized
     * disk or update tag costs O(1) here.
     */
    static RampGeometry decode(ListTag list) {
        int size = list.size();
        if (size < 1 || size > SurfRamps.MAX_PLANES) return null;
        List<BrushPlane> planes = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                CompoundTag entry = list.getCompound(i);
                planes.add(new BrushPlane(entry.getDouble("nx"), entry.getDouble("ny"), entry.getDouble("nz"),
                        entry.getDouble("d")));
            }
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return SurfRamps.validate(planes);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Object getRenderData() {
        return geometry;
    }
}
