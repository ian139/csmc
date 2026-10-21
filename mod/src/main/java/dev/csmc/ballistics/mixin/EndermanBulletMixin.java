package dev.csmc.ballistics.mixin;

import dev.csmc.ballistics.Ballistics;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Hitscan bullets remain projectiles for enchantments, but do not enter Enderman's arrow-dodge branch. */
@Mixin(EnderMan.class)
abstract class EndermanBulletMixin {
    @Redirect(method = "hurt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/DamageSource;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean csmc$keepHitscanBulletDamage(DamageSource source, TagKey<DamageType> tag) {
        return !(tag.equals(DamageTypeTags.IS_PROJECTILE) && source.is(Ballistics.BULLET_DAMAGE)) && source.is(tag);
    }
}
