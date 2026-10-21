package dev.csmc.item;

import net.minecraft.resources.ResourceLocation;

/**
 * An item that carries a CS2 finish (paint kit + wear + seed) for one canonical weapon id. Implemented by
 * {@link GunItem} and {@link KnifeItem}. {@code weaponId().getPath()} is the skin catalog's weapon string.
 */
public interface SkinnableWeapon {
    /** {@code csmc:<id>}, where {@code <id>} is the CS2 vdata key without {@code weapon_}. */
    ResourceLocation weaponId();
}
