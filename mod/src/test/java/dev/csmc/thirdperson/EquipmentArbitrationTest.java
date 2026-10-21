package dev.csmc.thirdperson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.csmc.thirdperson.EquipmentArbitration.Equipment;
import dev.csmc.thirdperson.EquipmentArbitration.Ownership;
import dev.csmc.thirdperson.blend.PoseFrame;
import dev.csmc.thirdperson.blend.PoseSmoother;
import org.junit.Test;

/**
 * Mixed-hand equipment arbitration: which arms the third-person weapon/knife/grenade pose may own, and how a decision
 * change masks the smoothed pose while its weights are still nonzero. Gun, knife and grenade main-hand items are all
 * {@link Equipment#NONE} to the decision, so one input covers every provider family.
 */
public class EquipmentArbitrationTest {
    /** An AK, knife or grenade in the main hand, or an empty hand. */
    private static final Equipment WEAPON = Equipment.NONE;
    private static final double FRAME = 1.0 / 60.0;
    private static final int ID = 7;

    @Test
    public void normalWeaponKnifeGrenadeHoldOwnsBothArms() {
        assertEquals(Ownership.FULL, EquipmentArbitration.decide(WEAPON, Equipment.NONE, Equipment.NONE));
    }

    @Test
    public void akMainWithC4OffhandIsTwoHandedVanilla() {
        assertEquals(Ownership.VANILLA, EquipmentArbitration.decide(WEAPON, Equipment.C4, Equipment.NONE));
    }

    @Test
    public void akMainWithHealthshotOffhandInUseIsVanilla() {
        assertEquals(Ownership.VANILLA,
                EquipmentArbitration.decide(WEAPON, Equipment.HEALTHSHOT, Equipment.HEALTHSHOT));
    }

    @Test
    public void akMainWithIdleHealthshotOffhandPosesMainArmOnly() {
        assertEquals(Ownership.MAIN_ARM_ONLY,
                EquipmentArbitration.decide(WEAPON, Equipment.HEALTHSHOT, Equipment.NONE));
    }

    @Test
    public void knifeMainWithDefuseKitOffhandPosesMainArmOnly() {
        assertEquals(Ownership.MAIN_ARM_ONLY,
                EquipmentArbitration.decide(WEAPON, Equipment.DEFUSE_KIT, Equipment.NONE));
    }

    @Test
    public void offhandInjectSurvivesMainHandSwitches() {
        // The server keeps an off-hand inject/plant running across main-hand switches; the decision follows the used
        // item, so every main-hand family (and an equipment main hand) stays vanilla while it runs.
        for (Equipment main : Equipment.values()) {
            assertEquals(main.name(), Ownership.VANILLA,
                    EquipmentArbitration.decide(main, Equipment.HEALTHSHOT, Equipment.HEALTHSHOT));
            assertEquals(main.name(), Ownership.VANILLA,
                    EquipmentArbitration.decide(main, Equipment.C4, Equipment.C4));
        }
    }

    @Test
    public void mainHandEquipmentUseAndC4HoldAreVanilla() {
        assertEquals(Ownership.VANILLA,
                EquipmentArbitration.decide(Equipment.HEALTHSHOT, Equipment.NONE, Equipment.HEALTHSHOT));
        assertEquals(Ownership.VANILLA, EquipmentArbitration.decide(Equipment.C4, Equipment.NONE, Equipment.C4));
        assertEquals(Ownership.VANILLA, EquipmentArbitration.decide(Equipment.C4, Equipment.NONE, Equipment.NONE));
        assertEquals(Ownership.VANILLA,
                EquipmentArbitration.decide(Equipment.C4, Equipment.DEFUSE_KIT, Equipment.NONE));
    }

    @Test
    public void idleMainHandHealthshotOrDefuseKitLeavesProvidersAlone() {
        // No provider claims equipment; FULL keeps the ordinary fade-out of the previous weapon pose.
        assertEquals(Ownership.FULL, EquipmentArbitration.decide(Equipment.HEALTHSHOT, Equipment.NONE, Equipment.NONE));
        assertEquals(Ownership.FULL, EquipmentArbitration.decide(Equipment.DEFUSE_KIT, Equipment.NONE, Equipment.NONE));
    }

    @Test
    public void fullOwnershipSmoothsExactlyLikeTheBareSmoother() {
        PoseSmoother arbitrated = new PoseSmoother();
        PoseSmoother bare = new PoseSmoother();
        PoseFrame a = new PoseFrame();
        PoseFrame b = new PoseFrame();
        for (int i = 0; i <= 30; i++) {
            double now = i * FRAME;
            EquipmentArbitration.smooth(Ownership.FULL, arbitrated, ID, now, pose(), vanilla(), a);
            bare.smooth(ID, now, pose(), vanilla(), b);
            assertEquals(b.rightArmWeight, a.rightArmWeight, 0F);
            assertEquals(b.leftArmWeight, a.leftArmWeight, 0F);
            assertEquals(b.leftArmX, a.leftArmX, 0F);
            assertEquals(b.bodyYOffset, a.bodyYOffset, 0F);
        }
    }

    @Test
    public void switchingToMainArmOnlyMasksTheOffArmImmediatelyWhileWeightsAreNonzero() {
        PoseSmoother smoother = new PoseSmoother();
        PoseFrame out = new PoseFrame();
        double now = 0;
        for (int i = 0; i <= 60; i++, now += FRAME) {
            EquipmentArbitration.smooth(Ownership.FULL, smoother, ID, now, pose(), vanilla(), out);
        }
        assertTrue(out.leftArmWeight > 0.99F);
        float mainX = out.rightArmX;

        // A defuse kit or idle healthshot lands in the off hand: the off arm is vanilla on the very next frame.
        PoseFrame target = pose();
        EquipmentArbitration.smooth(Ownership.MAIN_ARM_ONLY, smoother, ID, now, target, vanilla(), out);
        assertEquals(0F, target.leftArmWeight, 0F);
        assertEquals(0F, out.leftArmWeight, 0F);
        assertTrue(out.rightArmWeight > 0.99F);
        assertEquals(mainX, out.rightArmX, 1e-4F);
        for (int i = 0; i < 60; i++) {
            now += FRAME;
            EquipmentArbitration.smooth(Ownership.MAIN_ARM_ONLY, smoother, ID, now, pose(), vanilla(), out);
            assertEquals(0F, out.leftArmWeight, 0F);
            assertTrue(out.rightArmWeight > 0.99F);
        }

        // The retained off-arm weight decayed while masked, so giving the off arm back fades in instead of popping.
        now += FRAME;
        EquipmentArbitration.smooth(Ownership.FULL, smoother, ID, now, pose(), vanilla(), out);
        assertTrue(out.leftArmWeight < 0.1F);
    }

    @Test
    public void vanillaHandoffDropsTheRetainedPoseSoItFadesBackInFromVanilla() {
        // The applier forgets the entity's smoother for VANILLA instead of smoothing; nothing retained can override
        // the two-handed equipment pose, and the weapon pose returns from zero weight afterwards.
        PoseSmoother smoother = new PoseSmoother();
        PoseFrame out = new PoseFrame();
        double now = 0;
        for (int i = 0; i <= 60; i++, now += FRAME) {
            EquipmentArbitration.smooth(Ownership.FULL, smoother, ID, now, pose(), vanilla(), out);
        }
        assertTrue(out.rightArmWeight > 0.99F);
        smoother.forget(ID);
        EquipmentArbitration.smooth(Ownership.FULL, smoother, ID, now + FRAME, pose(), vanilla(), out);
        assertEquals(0F, out.rightArmWeight, 0F);
        assertEquals(0F, out.leftArmWeight, 0F);
    }

    @Test(expected = IllegalArgumentException.class)
    public void vanillaOwnershipHasNoPoseToSmooth() {
        EquipmentArbitration.smooth(Ownership.VANILLA, new PoseSmoother(), ID, 0, pose(), vanilla(), new PoseFrame());
    }

    private static PoseFrame pose() {
        PoseFrame p = new PoseFrame();
        p.rightArmX = -1.4F;
        p.rightArmY = -0.1F;
        p.leftArmX = -1.3F;
        p.leftArmY = 0.5F;
        p.rightArmWeight = 1F;
        p.leftArmWeight = 1F;
        p.bodyYOffset = 0.1F;
        return p;
    }

    private static PoseFrame vanilla() {
        PoseFrame p = new PoseFrame();
        p.rightArmX = 0.2F;
        p.leftArmX = -0.2F;
        return p;
    }
}
