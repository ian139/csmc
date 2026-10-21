package dev.csmc.client.thirdperson;

import dev.csmc.client.thirdperson.blend.MovementLayer;
import dev.csmc.item.C4Item;
import dev.csmc.item.DefuseKitItem;
import dev.csmc.item.HealthshotItem;
import dev.csmc.thirdperson.EquipmentArbitration;
import dev.csmc.thirdperson.EquipmentArbitration.Equipment;
import dev.csmc.thirdperson.EquipmentArbitration.Ownership;
import dev.csmc.client.thirdperson.blend.HeldItemPoseCorrection;
import dev.csmc.thirdperson.blend.PoseAngles;
import dev.csmc.thirdperson.blend.PoseSmoother;
import dev.csmc.thirdperson.blend.PoseFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the {@link PoseContext}, resolves a provider and blends its pose onto the vanilla-posed model. Every provider
 * passes through one {@link EquipmentArbitration} decision first, so held or in-use CS equipment keeps its vanilla
 * ({@code EquipmentClient.armPose}) arms.
 */
public final class ThirdPersonPoseApplier {
    private static final PlayerPose POSE = new PlayerPose();
    private static final PoseFrame TARGET = new PoseFrame();
    private static final PoseFrame VANILLA = new PoseFrame();
    private static final PoseFrame SMOOTHED = new PoseFrame();
    private static final PoseSmoother SMOOTHER = new PoseSmoother();

    private ThirdPersonPoseApplier() {}

    public static void apply(HumanoidModel<?> model, LivingEntity entity, float limbSwing, float limbSwingAmount,
            float ageInTicks) {
        HeldItemPoseCorrection.clear();
        if (!(entity instanceof AbstractClientPlayer player)) return;
        if (player.isSpectator() || player.isSleeping()) {
            forget(player.getId());
            return;
        }
        Minecraft client = Minecraft.getInstance();
        boolean owner = player == client.getCameraEntity() && client.options.getCameraType().isFirstPerson();
        // Never even fade onto the first-person owner: preserve all existing first-person rendering.
        if (owner) {
            forget(player.getId());
            return;
        }
        Ownership ownership = EquipmentArbitration.decide(equipment(player.getMainHandItem()),
                equipment(player.getOffhandItem()),
                player.isUsingItem() ? equipment(player.getUseItem()) : Equipment.NONE);
        // Dropping the smoother state hands both arms back to vanilla at once; a later pose fades in from vanilla.
        if (ownership == Ownership.VANILLA) {
            forget(player.getId());
            return;
        }
        float partial = ageInTicks - player.tickCount;
        double dx = player.getX() - player.xOld;
        double dz = player.getZ() - player.zOld;
        PoseContext context = new PoseContext(player, player.getMainHandItem(), owner, partial, ActionTimeline.now(),
                model.head.xRot, !player.onGround() && !player.isPassenger(), player.isCrouching(),
                Math.sqrt(dx * dx + dz * dz), limbSwing, limbSwingAmount);
        ThirdPersonPoses.resolve(context, POSE);
        captureTarget();
        MovementLayer.apply(context, TARGET);
        boolean rightHanded = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        captureVanilla(rightHanded ? model.rightArm : model.leftArm,
                rightHanded ? model.leftArm : model.rightArm, rightHanded);
        EquipmentArbitration.smooth(ownership, SMOOTHER, player.getId(), context.nowSeconds(), TARGET, VANILLA,
                SMOOTHED);
        blend(rightHanded ? model.rightArm : model.leftArm, SMOOTHED.rightArmX,
                rightHanded ? SMOOTHED.rightArmY : -SMOOTHED.rightArmY,
                rightHanded ? SMOOTHED.rightArmZ : -SMOOTHED.rightArmZ, SMOOTHED.rightArmWeight);
        blend(rightHanded ? model.leftArm : model.rightArm, SMOOTHED.leftArmX,
                rightHanded ? SMOOTHED.leftArmY : -SMOOTHED.leftArmY,
                rightHanded ? SMOOTHED.leftArmZ : -SMOOTHED.leftArmZ, SMOOTHED.leftArmWeight);
        model.body.yRot += SMOOTHED.bodyYOffset;
        model.head.xRot += SMOOTHED.headXOffset;
        HeldItemPoseCorrection.applied(model, player, SMOOTHED.rightArmWeight);
    }

    public static void forget(int entityId) {
        HeldItemPoseCorrection.clear();
        SMOOTHER.forget(entityId);
        MovementLayer.forget(entityId);
    }

    public static void clear() {
        HeldItemPoseCorrection.clear();
        SMOOTHER.clear();
        MovementLayer.clear();
    }

    private static Equipment equipment(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof HealthshotItem) return Equipment.HEALTHSHOT;
        if (item instanceof C4Item) return Equipment.C4;
        if (item instanceof DefuseKitItem) return Equipment.DEFUSE_KIT;
        return Equipment.NONE;
    }

    private static void captureTarget() {
        TARGET.rightArmX = POSE.rightArmX;
        TARGET.rightArmY = POSE.rightArmY;
        TARGET.rightArmZ = POSE.rightArmZ;
        TARGET.leftArmX = POSE.leftArmX;
        TARGET.leftArmY = POSE.leftArmY;
        TARGET.leftArmZ = POSE.leftArmZ;
        TARGET.rightArmWeight = POSE.rightArmWeight;
        TARGET.leftArmWeight = POSE.leftArmWeight;
        TARGET.bodyYOffset = POSE.bodyYOffset;
        TARGET.headXOffset = POSE.headXOffset;
    }

    private static void captureVanilla(net.minecraft.client.model.geom.ModelPart main,
            net.minecraft.client.model.geom.ModelPart off, boolean rightHanded) {
        float mirror = rightHanded ? 1F : -1F;
        VANILLA.rightArmX = main.xRot;
        VANILLA.rightArmY = main.yRot * mirror;
        VANILLA.rightArmZ = main.zRot * mirror;
        VANILLA.leftArmX = off.xRot;
        VANILLA.leftArmY = off.yRot * mirror;
        VANILLA.leftArmZ = off.zRot * mirror;
    }

    private static void blend(net.minecraft.client.model.geom.ModelPart arm, float x, float y, float z, float weight) {
        if (weight <= 0F) return;
        arm.xRot = PoseAngles.blend(arm.xRot, x, weight);
        arm.yRot = PoseAngles.blend(arm.yRot, y, weight);
        arm.zRot = PoseAngles.blend(arm.zRot, z, weight);
    }
}
