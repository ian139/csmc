package dev.csmc.client.gltf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Immutable, render-ready glTF 2.0 scene produced by {@link GltfLoader}.
 *
 * <p>All arrays are shared with every {@link GltfPose} and must be treated as read-only. Geometry is
 * flattened per drawn mesh-node instance: one {@link Primitive} per (scene node, mesh primitive).
 */
public final class GltfModel {
    /** glTF material alpha mode. */
    public enum AlphaMode { OPAQUE, MASK, BLEND }

    /** Animated node property. */
    public enum Target { TRANSLATION, ROTATION, SCALE }

    /** Keyframe interpolation mode. */
    public enum Interpolation { STEP, LINEAR, CUBICSPLINE }

    /**
     * One drawable triangle list.
     *
     * @param node scene node that instantiates this mesh; its world matrix is the draw transform unless skinned
     * @param skin skin index, or -1; skinned primitives are drawn in scene space via {@link GltfPose#skin}
     * @param positions xyz per vertex, mesh space
     * @param normals unit xyz per vertex (flat face normals are generated when the asset omits them)
     * @param uvs uv per vertex for the base color texture set, glTF convention (origin top-left)
     * @param indices triangle list, always present, three per face
     * @param joints four joint slots per vertex (indices into the skin's joint list), or null
     * @param weights four weights per vertex summing to 1, or null
     * @param texture index into {@link GltfModel#textures} for the base color, or -1
     * @param baseColorFactor RGBA multiplier, linear
     */
    public record Primitive(
        int node,
        int skin,
        float[] positions,
        float[] normals,
        float[] uvs,
        int[] indices,
        int[] joints,
        float[] weights,
        int texture,
        float[] baseColorFactor,
        AlphaMode alphaMode,
        float alphaCutoff,
        boolean doubleSided
    ) {
        public int vertexCount() {
            return positions.length / 3;
        }

        public boolean skinned() {
            return skin >= 0;
        }
    }

    /** Encoded image bytes as found in the asset; {@code mimeType} is {@code image/png} for NativeImage-readable data. */
    public record Texture(String name, String mimeType, byte[] bytes) {
        public boolean isPng() {
            return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        }
    }

    /**
     * Scene graph node with rest transform.
     *
     * @param restMatrix local matrix from {@code matrix} or rest TRS
     * @param hasMatrix true when the asset specified {@code matrix}; TRS are then its decomposition
     */
    public record Node(
        String name,
        int parent,
        int[] children,
        int mesh,
        int skin,
        float[] translation,
        float[] rotation,
        float[] scale,
        Matrix4fc restMatrix,
        boolean hasMatrix
    ) {}

    /** Skin joints (node indices) and their inverse bind matrices (identity when absent). */
    public record Skin(String name, int[] joints, Matrix4fc[] inverseBindMatrices) {}

    /**
     * One animated node property. {@code values} holds 3 (translation/scale) or 4 (rotation xyzw) floats per key,
     * tripled (in-tangent, value, out-tangent) for CUBICSPLINE.
     */
    public record Channel(int node, Target target, Interpolation interpolation, float[] times, float[] values) {}

    /** Named animation; {@code duration} is the last keyframe time in seconds. */
    public record Clip(String name, float duration, List<Channel> channels) {}

    public final List<Primitive> primitives;
    public final List<Texture> textures;
    public final List<Node> nodes;
    public final List<Skin> skins;
    public final Map<String, Clip> clips;
    /** Scene root node indices. */
    public final int[] roots;
    /** Ignored features (morph targets, non-triangle modes, extra UV/joint sets, ...). */
    public final List<String> warnings;
    /** Bind pose scene-space axis-aligned bounds; zero when the model has no vertices. */
    public final float[] boundsMin;
    public final float[] boundsMax;

    /** Parent-before-child traversal of every node; used by {@link GltfPose}. */
    final int[] nodeOrder;

    GltfModel(
        List<Primitive> primitives,
        List<Texture> textures,
        List<Node> nodes,
        List<Skin> skins,
        Map<String, Clip> clips,
        int[] roots,
        int[] nodeOrder,
        List<String> warnings
    ) {
        this.primitives = List.copyOf(primitives);
        this.textures = List.copyOf(textures);
        this.nodes = List.copyOf(nodes);
        this.skins = List.copyOf(skins);
        this.clips = Collections.unmodifiableMap(new LinkedHashMap<>(clips));
        this.roots = roots;
        this.nodeOrder = nodeOrder;
        this.warnings = List.copyOf(warnings);
        float[][] bounds = computeBindBounds();
        this.boundsMin = bounds[0];
        this.boundsMax = bounds[1];
    }

    private GltfModel(GltfModel source, List<Texture> textures) {
        this.primitives = source.primitives;
        this.textures = List.copyOf(textures);
        this.nodes = source.nodes;
        this.skins = source.skins;
        this.clips = source.clips;
        this.roots = source.roots;
        this.nodeOrder = source.nodeOrder;
        this.warnings = source.warnings;
        this.boundsMin = source.boundsMin;
        this.boundsMax = source.boundsMax;
    }

    /** Same model with empty texture byte arrays, so encoded images can be freed once decoded elsewhere. */
    public GltfModel withoutTextureBytes() {
        return new GltfModel(this, textures.stream().map(t -> new Texture(t.name(), t.mimeType(), new byte[0])).toList());
    }

    /** Index of the first node with this exact name, or -1. */
    public int nodeIndex(String name) {
        for (int i = 0; i < nodes.size(); i++) if (name.equals(nodes.get(i).name())) return i;
        return -1;
    }

    /** New mutable pose initialised to the bind/rest pose. One per render thread; reuse across frames. */
    public GltfPose newPose() {
        return new GltfPose(this);
    }

    /** Clip by exact name, or null. */
    public Clip clip(String name) {
        return clips.get(name);
    }

    /**
     * Matrix that centers the bind bounds at the origin and scales the largest extent to {@code targetMaxExtent}.
     * Apply before the pose's draw matrices.
     */
    public Matrix4f normalization(float targetMaxExtent) {
        float ex = boundsMax[0] - boundsMin[0];
        float ey = boundsMax[1] - boundsMin[1];
        float ez = boundsMax[2] - boundsMin[2];
        float extent = Math.max(ex, Math.max(ey, ez));
        float s = extent > 1e-8f ? targetMaxExtent / extent : 1f;
        return new Matrix4f()
            .scale(s)
            .translate(
                -(boundsMin[0] + boundsMax[0]) * 0.5f,
                -(boundsMin[1] + boundsMax[1]) * 0.5f,
                -(boundsMin[2] + boundsMax[2]) * 0.5f
            );
    }

    private float[][] computeBindBounds() {
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        GltfPose pose = newPose();
        float[] scratch = new float[0];
        Vector3f v = new Vector3f();
        for (Primitive p : primitives) {
            float[] pos;
            Matrix4fc m;
            if (p.skinned()) {
                if (scratch.length < p.positions.length) scratch = new float[p.positions.length];
                pose.skin(p, scratch, null);
                pos = scratch;
                m = GltfPose.IDENTITY;
            } else {
                pos = p.positions;
                m = pose.world(p.node);
            }
            int n = p.positions.length;
            for (int i = 0; i < n; i += 3) {
                m.transformPosition(pos[i], pos[i + 1], pos[i + 2], v);
                min[0] = Math.min(min[0], v.x);
                min[1] = Math.min(min[1], v.y);
                min[2] = Math.min(min[2], v.z);
                max[0] = Math.max(max[0], v.x);
                max[1] = Math.max(max[1], v.y);
                max[2] = Math.max(max[2], v.z);
            }
        }
        if (min[0] > max[0]) return new float[][] {new float[3], new float[3]};
        return new float[][] {min, max};
    }
}
