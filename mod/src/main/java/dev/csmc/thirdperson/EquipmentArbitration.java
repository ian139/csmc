package dev.csmc.thirdperson;

import dev.csmc.thirdperson.blend.PoseFrame;
import dev.csmc.thirdperson.blend.PoseSmoother;

/**
 * Decides how much of a player's arms the third-person CS pose may own when CS equipment (healthshot, C4, defuse kit)
 * is held or used, so weapon, knife and grenade providers never override the equipment arm pose
 * ({@code EquipmentClient.armPose}): an in-use healthshot/C4 in either hand and a held C4 are vanilla two-handed
 * crossbow poses, so the third-person pose steps aside entirely; any other equipment held in the off hand keeps its
 * vanilla off-arm pose while the main-hand item is still posed on the main arm. An off-hand use keeps running across
 * main-hand switches, so the decision reads the item actually being used, not the current main-hand item. Pure: no
 * Minecraft classes; frames are main-hand relative ({@code right*} = main arm, {@code left*} = off arm).
 */
public final class EquipmentArbitration {
    /** CS equipment family of one stack, or {@link Equipment#NONE} for anything else (including empty). */
    public enum Equipment { NONE, HEALTHSHOT, C4, DEFUSE_KIT }

    public enum Ownership {
        /** No equipment involvement: the provider's pose owns both arms. */
        FULL,
        /** Equipment held idle in the off hand: pose the main arm only; the off arm stays vanilla. */
        MAIN_ARM_ONLY,
        /** Equipment in use, or a two-handed C4 hold: no third-person pose at all this frame. */
        VANILLA
    }

    private EquipmentArbitration() {}

    /**
     * @param inUse the equipment of the item currently being used in either hand, {@link Equipment#NONE} when the
     *              player is not using an item
     */
    public static Ownership decide(Equipment mainHand, Equipment offHand, Equipment inUse) {
        if (inUse != Equipment.NONE || mainHand == Equipment.C4 || offHand == Equipment.C4) return Ownership.VANILLA;
        return offHand != Equipment.NONE ? Ownership.MAIN_ARM_ONLY : Ownership.FULL;
    }

    /**
     * Smooths a {@link Ownership#FULL} or {@link Ownership#MAIN_ARM_ONLY} frame. Main-arm-only masks the off arm both
     * before smoothing (its retained weight decays) and after it, so a weapon pose fading out never overrides the
     * equipment's vanilla off arm, not even for the frames right after the decision changes. {@link Ownership#VANILLA}
     * has no pose to smooth: callers drop the entity's smoother state instead, so a later pose fades in from vanilla.
     */
    public static void smooth(Ownership ownership, PoseSmoother smoother, int entityId, double nowSeconds,
            PoseFrame target, PoseFrame vanilla, PoseFrame out) {
        if (ownership == Ownership.VANILLA) throw new IllegalArgumentException("VANILLA ownership has no pose");
        boolean mainOnly = ownership == Ownership.MAIN_ARM_ONLY;
        if (mainOnly) target.leftArmWeight = 0F;
        smoother.smooth(entityId, nowSeconds, target, vanilla, out);
        if (mainOnly) out.leftArmWeight = 0F;
    }
}
