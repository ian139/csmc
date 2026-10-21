package dev.csmc.movement.physics;

/**
 * Scale between Source units and Minecraft blocks for movement only. Approximation: the 72-unit standing CS hull
 * maps to the 1.8-block Minecraft player, so 1 block = 40 units (250 u/s = 6.25 blocks/s, a 57-unit jump = 1.43
 * blocks). Ballistic ranges keep {@code WeaponData.UNITS_PER_BLOCK}.
 * Hull comparison: <a href="https://developer.valvesoftware.com/wiki/Counter-Strike:_Global_Offensive/Mapper%27s_Reference">VDC
 * CS:GO Mapper's Reference</a>. The 40-unit scale is a project choice, not a Source constant.
 * The 20 Hz player clock is documented by
 * <a href="https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-3">Mojang's tick-command release notes</a>;
 * conversion assumes the default Minecraft tick rate.
 */
public final class CsUnits {
    public static final double UNITS_PER_BLOCK = 40.0;
    public static final double TICKS_PER_SECOND = 20.0;

    private CsUnits() {}

    public static double toBlocks(double units) {
        return units / UNITS_PER_BLOCK;
    }

    public static double toUnits(double blocks) {
        return blocks * UNITS_PER_BLOCK;
    }

    /** Units per second to Minecraft blocks per tick ({@code Entity#getDeltaMovement} scale). */
    public static double unitsPerSecondToBlocksPerTick(double unitsPerSecond) {
        return unitsPerSecond / UNITS_PER_BLOCK / TICKS_PER_SECOND;
    }

    public static double blocksPerTickToUnitsPerSecond(double blocksPerTick) {
        return blocksPerTick * UNITS_PER_BLOCK * TICKS_PER_SECOND;
    }
}
