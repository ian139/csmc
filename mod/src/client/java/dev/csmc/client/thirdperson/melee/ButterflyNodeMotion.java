package dev.csmc.client.thirdperson.melee;

import dev.csmc.client.gltf.GltfModel;
import dev.csmc.client.gltf.GltfPose;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.thirdperson.melee.ButterflyFlipCurves;
import dev.csmc.thirdperson.melee.KnifePoseCurves;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Third-person butterfly-knife part articulation over the world model's sampled clip or bind pose (procedural; see
 * {@link ButterflyFlipCurves}). The {@code front} handle pin swings the blade and everything below it, the {@code rear}
 * handle swings back about its own pin, and the {@code lock} latch about its pin. Each part's scene-space delta is
 * composed onto its parent part's, and applied to every skin joint at or below the part, so the skinned mesh stays rigid
 * per part. Only joint matrices change; {@link CachedGunRendering} invokes this for third-person in-hand frames and
 * resamples the pose afterwards, so first person never sees the overrides. Render thread only, no per-frame allocation.
 */
public final class ButterflyNodeMotion implements CachedGunRendering.ThirdPersonNodeMotion {
    static final String MODEL_ID = "knife_butterfly";
    /** Part node names in {@link ButterflyFlipCurves} slot order (FRONT, REAR, LOCK); parents precede children. */
    private static final String[] PARTS = {"front", "rear", "lock"};
    private static final Matrix4fc IDENTITY = new Matrix4f();
    private static final float MIN_AXIS_LENGTH = 1e-9f;

    /**
     * Cached topology per loaded model (default and legacy bodies are distinct models). Weakly keyed so a model retired
     * by a resource reload is collectable: {@link GltfModel} keeps {@link Object}'s identity equality, and a {@link Rig}
     * holds only index arrays, never its model.
     */
    private final Map<GltfModel, Rig> rigs = new WeakHashMap<>(4);
    private final float[] angles = new float[PARTS.length];
    private final Matrix4f[] deltas = {new Matrix4f(), new Matrix4f(), new Matrix4f()};
    private final Matrix4f joint = new Matrix4f();
    private final Vector3f front = new Vector3f();
    private final Vector3f rear = new Vector3f();
    private final Vector3f lock = new Vector3f();
    private final Vector3f axis = new Vector3f();
    private final Vector3f pivot = new Vector3f();

    private ButterflyNodeMotion() {}

    /** Installs the hook; call once from {@link MeleeAnimations#initialize}. */
    public static void initialize() {
        CachedGunRendering.thirdPersonNodeMotion(new ButterflyNodeMotion());
    }

    @Override
    public boolean apply(String modelId, GltfModel model, GltfPose pose, String action, float seconds, float duration) {
        if (!MODEL_ID.equals(modelId) || action == null) return false;
        KnifePoseCurves.Action kind = KnifePoseCurves.action(action);
        // Same length as the player's arm timeline: the server duration when known, else the pose curve's own.
        double length = duration > 0 ? duration : KnifePoseCurves.duration(kind);
        if (!ButterflyFlipCurves.sample(kind, seconds, length, angles)) return false;
        Rig rig = rigs.get(model);
        if (rig == null) {
            rig = Rig.of(model);
            rigs.put(model, rig);
        }
        if (rig.parts == null) return false;
        int[] parts = rig.parts;
        pose.world(parts[ButterflyFlipCurves.FRONT]).getTranslation(front);
        pose.world(parts[ButterflyFlipCurves.REAR]).getTranslation(rear);
        pose.world(parts[ButterflyFlipCurves.LOCK]).getTranslation(lock);
        // The pins all lie in the handles' plane; they hinge about its normal.
        rear.sub(front, axis).cross(lock.sub(front, pivot));
        if (axis.length() < MIN_AXIS_LENGTH) return false;
        axis.normalize();
        for (int k = 0; k < parts.length; k++) {
            int parent = rig.parentPart[k];
            pose.world(parts[k]).getTranslation(pivot);
            ButterflyFlipCurves.hinge(parent < 0 ? IDENTITY : deltas[parent], pivot, axis, angles[k], deltas[k]);
        }
        int[][] owners = rig.owner;
        for (int s = 0; s < owners.length; s++) {
            int[] owner = owners[s];
            for (int j = 0; j < owner.length; j++) {
                int k = owner[j];
                if (k < 0) continue;
                deltas[k].mul(pose.jointMatrix(s, j), joint);
                pose.setJointMatrix(s, j, joint);
            }
        }
        return true;
    }

    /** Part node indices, each part's nearest ancestor part, and each skin joint's nearest part at or above it. */
    private record Rig(int[] parts, int[] parentPart, int[][] owner) {
        static final Rig NONE = new Rig(null, null, null);

        static Rig of(GltfModel model) {
            int[] parts = new int[PARTS.length];
            for (int k = 0; k < parts.length; k++) {
                parts[k] = model.nodeIndex(PARTS[k]);
                if (parts[k] < 0) return NONE;
            }
            int[] parentPart = new int[parts.length];
            for (int k = 0; k < parts.length; k++) {
                parentPart[k] = nearestPart(model, model.nodes.get(parts[k]).parent(), parts);
                if (parentPart[k] >= k) return NONE;
            }
            int[][] owner = new int[model.skins.size()][];
            boolean any = false;
            for (int s = 0; s < owner.length; s++) {
                int[] joints = model.skins.get(s).joints();
                owner[s] = new int[joints.length];
                for (int j = 0; j < joints.length; j++) {
                    owner[s][j] = nearestPart(model, joints[j], parts);
                    any |= owner[s][j] >= 0;
                }
            }
            return any ? new Rig(parts, parentPart, owner) : NONE;
        }

        private static int nearestPart(GltfModel model, int node, int[] parts) {
            for (int n = node; n >= 0; n = model.nodes.get(n).parent()) {
                for (int k = 0; k < parts.length; k++) if (parts[k] == n) return k;
            }
            return -1;
        }
    }
}
