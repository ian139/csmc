package dev.csmc.ballistics.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code Entity.collide}: block, world-border and entity collision exactly as {@code Entity.move} applies it. */
@Mixin(Entity.class)
public interface GrenadeCollideInvoker {
    @Invoker("collide")
    Vec3 csmc$collide(Vec3 movement);
}
