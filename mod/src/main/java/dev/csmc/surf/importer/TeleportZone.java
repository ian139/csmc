package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An imported {@code trigger_teleport}: touching any volume moves the player to {@code destination} (feet), as
 * {@code CTriggerTeleport::Touch} (Source SDK 2013 {@code server/triggers.cpp}) does. With a landmark the player keeps
 * its offset from the landmark and its view angles; without one the view turns to the destination's angles unless
 * the trigger had spawnflag 32 "Preserve angles". Velocity is preserved in both cases (the SDK passes a null velocity).
 *
 * @param landmark Minecraft position of the {@code landmark} entity, or null
 */
public record TeleportZone(String name, List<ConvexBrush> volumes, Vec3 destination, float yaw, float pitch,
                           boolean setAngles, Vec3 landmark) {
    public TeleportZone {
        volumes = List.copyOf(volumes);
        if (volumes.isEmpty()) throw new IllegalArgumentException("teleport zone without volumes");
    }

    /** Union of the volumes' bounds. */
    public AABB bounds() {
        AABB box = volumes.get(0).bounds();
        for (ConvexBrush volume : volumes) box = box.minmax(volume.bounds());
        return box;
    }

    /**
     * Whether {@code box} touches a volume. Separating-axis test over the volume's planes plus its bounding box
     * (the axial bevels vbsp adds to every brush, {@code AddBrushBevels} in utils/vbsp/map.cpp). Approximation: edge
     * bevels are not added, so a box near a sloped edge may count as touching up to the bounding-box corner.
     */
    public boolean touches(AABB box) {
        for (ConvexBrush volume : volumes) {
            if (!volume.bounds().intersects(box)) continue;
            boolean separated = false;
            for (BrushPlane p : volume.planes()) {
                double x = p.nx() >= 0 ? box.minX : box.maxX;
                double y = p.ny() >= 0 ? box.minY : box.maxY;
                double z = p.nz() >= 0 ? box.minZ : box.maxZ;
                if (p.signedDistance(x, y, z) > 0) {
                    separated = true;
                    break;
                }
            }
            if (!separated) return true;
        }
        return false;
    }

    /** Where a player at {@code position} lands. */
    public Vec3 target(Vec3 position) {
        return landmark == null ? destination : destination.add(position.subtract(landmark));
    }

    public TeleportZone translated(double dx, double dy, double dz) {
        List<ConvexBrush> moved = new ArrayList<>(volumes.size());
        for (ConvexBrush volume : volumes) {
            List<BrushPlane> planes = new ArrayList<>(volume.planes().size());
            for (BrushPlane p : volume.planes()) planes.add(p.translated(dx, dy, dz));
            moved.add(new ConvexBrush(planes, volume.bounds().move(dx, dy, dz)));
        }
        return new TeleportZone(name, moved, destination.add(dx, dy, dz), yaw, pitch, setAngles,
                landmark == null ? null : landmark.add(dx, dy, dz));
    }
}
