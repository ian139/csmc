package dev.csmc.movement.geometry;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;

/**
 * Whether an entity collides with {@link MovementGeometry} solids analytically (CS movement) instead of through
 * vanilla block shapes. The shared movement collision iterator omits {@link AnalyticBlock} shapes for such
 * entities, while direct shape and ray queries retain solid geometry for combat/visibility. The movement module
 * installs the predicate; until then no entity is analytic.
 */
public final class AnalyticCollision {
    private static volatile Predicate<Entity> predicate = entity -> false;

    private AnalyticCollision() {}

    public static void setPredicate(Predicate<Entity> value) {
        predicate = Objects.requireNonNull(value, "predicate");
    }

    public static boolean appliesTo(Entity entity) {
        return entity != null && predicate.test(entity);
    }
}
