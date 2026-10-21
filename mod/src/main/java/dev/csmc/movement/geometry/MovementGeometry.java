package dev.csmc.movement.geometry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

/**
 * Collision solids beyond vanilla block shapes that CS movement traces against (for example sloped surf ramps).
 * Providers must be deterministic and side-agnostic: the same world state yields the same solids on client and
 * server. Vanilla block collision boxes are gathered separately by the movement code; a provider emits only what
 * vanilla shapes omit for entities where {@link AnalyticCollision#appliesTo} is true.
 */
@FunctionalInterface
public interface MovementGeometry {
    /** Emits every solid that may intersect {@code region} (world block coordinates). */
    void collect(BlockGetter level, AABB region, Consumer<ConvexBrush> sink);

    /** Adds a provider; call from a mod initializer. */
    static void register(MovementGeometry provider) {
        Providers.ALL.add(provider);
    }

    /** Runs every registered provider for {@code region}. */
    static void collectAll(BlockGetter level, AABB region, Consumer<ConvexBrush> sink) {
        for (MovementGeometry provider : Providers.ALL) provider.collect(level, region, sink);
    }

    final class Providers {
        private static final List<MovementGeometry> ALL = new CopyOnWriteArrayList<>();

        private Providers() {}
    }
}
