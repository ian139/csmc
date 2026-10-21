package dev.csmc.client.thirdperson.melee;

import dev.csmc.client.thirdperson.ActionTimeline;
import dev.csmc.client.thirdperson.PlayerPose;
import dev.csmc.client.thirdperson.PoseContext;
import dev.csmc.client.thirdperson.PoseProvider;
import dev.csmc.item.KnifeItem;
import dev.csmc.thirdperson.melee.KnifePoseCurves;

public final class KnifePoseProvider implements PoseProvider {
    private final KnifePoseCurves.Pose sampled = new KnifePoseCurves.Pose();
    @Override
    public boolean applies(PoseContext context) {
        return !context.firstPersonOwner() && context.player().isAlive()
                && context.mainHand().getItem() instanceof KnifeItem;
    }

    @Override
    public void pose(PoseContext context, PlayerPose out) {
        ActionTimeline.Entry entry = ActionTimeline.current(context.player().getId());
        KnifePoseCurves.Action action = entry == null ? KnifePoseCurves.Action.IDLE : switch (entry.action()) {
            case DRAW, KNIFE_DRAW -> KnifePoseCurves.Action.DRAW;
            case INSPECT, KNIFE_INSPECT -> KnifePoseCurves.Action.INSPECT;
            case KNIFE_SLASH -> KnifePoseCurves.Action.SLASH;
            case KNIFE_STAB -> KnifePoseCurves.Action.STAB;
            case KNIFE_BACKSTAB -> KnifePoseCurves.Action.BACKSTAB;
            default -> KnifePoseCurves.Action.IDLE;
        };
        boolean dual = ((KnifeItem) context.mainHand().getItem()).weaponId().getPath().equals("knife_push");
        KnifePoseCurves.sample(action, entry == null ? 0 : context.nowSeconds() - entry.startSeconds(),
                entry == null ? 0 : entry.durationSeconds(), context.headPitch(), dual, sampled);
        out.rightArmX = sampled.rightArmX;
        out.rightArmY = sampled.rightArmY;
        out.rightArmZ = sampled.rightArmZ;
        out.leftArmX = sampled.leftArmX;
        out.leftArmY = sampled.leftArmY;
        out.leftArmZ = sampled.leftArmZ;
        out.rightArmWeight = sampled.rightArmWeight;
        out.leftArmWeight = sampled.leftArmWeight;
    }
}
