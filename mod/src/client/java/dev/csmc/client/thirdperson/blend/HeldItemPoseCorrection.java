package dev.csmc.client.thirdperson.blend;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;

/** Read-only snapshot for held-item layers rendered immediately after this player's model pose. */
public final class HeldItemPoseCorrection {
    private static AbstractClientPlayer appliedPlayer;
    private static float weight, x, y, z, pitch;

    private HeldItemPoseCorrection() {}

    public static void clear() { appliedPlayer = null; }

    public static void applied(HumanoidModel<?> model, AbstractClientPlayer player, float mainArmWeight) {
        ModelPart arm = player.getMainArm() == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        appliedPlayer = player;
        weight = mainArmWeight;
        x = arm.xRot;
        y = arm.yRot;
        z = arm.zRot;
        pitch = model.head.xRot;
    }

    public static float weight(AbstractClientPlayer player) { return player == appliedPlayer ? weight : 0F; }
    public static float mainArmX(AbstractClientPlayer player) { return player == appliedPlayer ? x : 0F; }
    public static float mainArmY(AbstractClientPlayer player) { return player == appliedPlayer ? y : 0F; }
    public static float mainArmZ(AbstractClientPlayer player) { return player == appliedPlayer ? z : 0F; }
    public static float headPitch(AbstractClientPlayer player) { return player == appliedPlayer ? pitch : 0F; }
}
