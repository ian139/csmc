package dev.csmc.client.thirdperson;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of {@link PoseProvider}s plus the per-entity action timeline. Providers register from client initializers
 * and claim only their main-hand item family; held or in-use equipment (healthshot/C4/defuse kit) is arbitrated once
 * in {@link ThirdPersonPoseApplier} before any provider resolves.
 */
public final class ThirdPersonPoses {
    private static final List<PoseProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ThirdPersonPoses() {}

    public static void register(PoseProvider provider) {
        PROVIDERS.add(provider);
    }

    /** Clears {@code out} and lets the first applicable provider fill it; returns false when none applies. */
    public static boolean resolve(PoseContext context, PlayerPose out) {
        out.clear();
        for (int i = 0, n = PROVIDERS.size(); i < n; i++) {
            PoseProvider provider = PROVIDERS.get(i);
            if (provider.applies(context)) {
                provider.pose(context, out);
                return true;
            }
        }
        return false;
    }
}
