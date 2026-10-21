package dev.csmc.client.thirdperson.grenades;

import dev.csmc.client.thirdperson.ActionTimeline;
import dev.csmc.client.thirdperson.PlayerPose;
import dev.csmc.client.thirdperson.PoseContext;
import dev.csmc.client.thirdperson.PoseProvider;
import dev.csmc.client.thirdperson.ThirdPersonPoses;
import dev.csmc.item.GrenadeItem;
import dev.csmc.thirdperson.melee.GrenadePoseCurves;

/**
 * Third-person grenade poses ({@link GrenadePoseCurves}) for remote players and the local player in F5, driven by the
 * {@link ActionTimeline}'s {@code GRENADE_PRIME}/{@code GRENADE_THROW} entries. Never claims the first-person owner,
 * so the first-person grenade hand ({@code GrenadeThrowClient.firstPersonPose}) is untouched.
 */
public final class GrenadePoseProvider implements PoseProvider {
    private static final GrenadePoseProvider INSTANCE = new GrenadePoseProvider();
    private static final GrenadePoseCurves.Pose CURVE_POSE = new GrenadePoseCurves.Pose();
    private static boolean registered;

    private GrenadePoseProvider() {}

    /** Registers the provider once; called from the melee packet's third-person initializer. */
    public static void initialize() {
        if (registered) return;
        registered = true;
        ThirdPersonPoses.register(INSTANCE);
    }

    @Override
    public boolean applies(PoseContext context) {
        if (context.firstPersonOwner() || !context.player().isAlive()) return false;
        ActionTimeline.Entry entry = ActionTimeline.current(context.player().getId());
        boolean holding = context.mainHand().getItem() instanceof GrenadeItem;
        if (entry == null) return holding;
        return GrenadePoseCurves.claims(holding, context.mainHand().isEmpty(), stage(entry.action()),
                context.nowSeconds() - entry.startSeconds(), entry.durationSeconds());
    }

    @Override
    public void pose(PoseContext context, PlayerPose out) {
        ActionTimeline.Entry entry = ActionTimeline.current(context.player().getId());
        boolean holding = context.mainHand().getItem() instanceof GrenadeItem;
        if (entry == null) {
            GrenadePoseCurves.pose(GrenadePoseCurves.Stage.READY, 0.0, 0.0, 0, context.headPitch(), holding,
                    CURVE_POSE);
        } else {
            GrenadePoseCurves.pose(stage(entry.action()), context.nowSeconds() - entry.startSeconds(),
                    entry.durationSeconds(), entry.variant(), context.headPitch(), holding, CURVE_POSE);
        }
        out.rightArmX = CURVE_POSE.rightArmX;
        out.rightArmY = CURVE_POSE.rightArmY;
        out.rightArmZ = CURVE_POSE.rightArmZ;
        out.leftArmX = CURVE_POSE.leftArmX;
        out.leftArmY = CURVE_POSE.leftArmY;
        out.leftArmZ = CURVE_POSE.leftArmZ;
        out.rightArmWeight = CURVE_POSE.weight;
        out.leftArmWeight = CURVE_POSE.weight;
        out.bodyYOffset = CURVE_POSE.bodyYOffset;
    }

    /** Only grenade actions drive grenade stages; any other (or a cancel) shows the ready grip. */
    static GrenadePoseCurves.Stage stage(ActionTimeline.Action action) {
        return switch (action) {
            case GRENADE_PRIME -> GrenadePoseCurves.Stage.PRIME;
            case GRENADE_THROW -> GrenadePoseCurves.Stage.THROW;
            default -> GrenadePoseCurves.Stage.READY;
        };
    }
}
