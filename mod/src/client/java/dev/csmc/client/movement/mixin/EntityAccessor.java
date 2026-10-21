package dev.csmc.client.movement.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla movement internals CS movement reproduces: the pending stuck multiplier and the plain collision. */
@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("stuckSpeedMultiplier")
    Vec3 csmc$stuckSpeedMultiplier();

    /** {@code Entity.collide}: the same per-axis collision the server's move replay runs. */
    @Invoker("collide")
    Vec3 csmc$collide(Vec3 movement);
}
