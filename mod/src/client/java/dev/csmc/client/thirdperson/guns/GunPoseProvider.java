package dev.csmc.client.thirdperson.guns;

import dev.csmc.client.thirdperson.ActionTimeline;
import dev.csmc.client.thirdperson.PlayerPose;
import dev.csmc.client.thirdperson.PoseContext;
import dev.csmc.client.thirdperson.PoseProvider;
import dev.csmc.item.GunItem;
import dev.csmc.thirdperson.guns.GunPoseMath;

/** Procedural CS-like player silhouettes, not exported first-person arm animations. */
public final class GunPoseProvider implements PoseProvider {
    private final GunPoseMath.Pose sampled = new GunPoseMath.Pose();

    @Override public boolean applies(PoseContext context) {
        return !context.firstPersonOwner() && context.mainHand().getItem() instanceof GunItem;
    }

    @Override public void pose(PoseContext context, PlayerPose out) {
        GunItem gun = (GunItem) context.mainHand().getItem();
        GunPoses.synchronize(context.player());
        ActionTimeline.Entry entry = ActionTimeline.current(context.player().getId());
        GunPoseMath.Action action = entry == null ? GunPoseMath.Action.NONE : switch (entry.action()) {
            case FIRE -> GunPoseMath.Action.FIRE;
            case RELOAD -> GunPoseMath.Action.RELOAD;
            case DRAW -> GunPoseMath.Action.DRAW;
            case INSPECT -> GunPoseMath.Action.INSPECT;
            default -> GunPoseMath.Action.NONE;
        };
        double progress = entry == null || entry.durationSeconds() <= 0 ? 1
                : (context.nowSeconds() - entry.startSeconds()) / entry.durationSeconds();
        GunPoseMath.sample(GunPoseMath.grip(gun.weaponId().getPath()), context.headPitch(),
                ActionTimeline.scopeLevel(context.player().getId()) > 0, action, progress, sampled);
        out.rightArmX = sampled.rightX;
        out.rightArmY = sampled.rightY;
        out.rightArmZ = sampled.rightZ;
        out.leftArmX = sampled.leftX;
        out.leftArmY = sampled.leftY;
        out.leftArmZ = sampled.leftZ;
        out.rightArmWeight = out.leftArmWeight = 1;
    }
}
