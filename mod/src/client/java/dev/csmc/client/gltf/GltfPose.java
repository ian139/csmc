package dev.csmc.client.gltf;

import dev.csmc.client.gltf.GltfModel.Channel;
import dev.csmc.client.gltf.GltfModel.Clip;
import dev.csmc.client.gltf.GltfModel.Node;
import dev.csmc.client.gltf.GltfModel.Primitive;
import dev.csmc.client.gltf.GltfModel.Skin;
import java.util.List;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/**
 * Mutable node/joint matrices for one {@link GltfModel}. Not thread-safe; create one per render thread and reuse it.
 * Pose updates and skinning allocate nothing.
 */
public final class GltfPose {
    static final Matrix4fc IDENTITY = new Matrix4f();

    private final GltfModel model;
    private final float[] translation;
    private final float[] rotation;
    private final float[] scale;
    private final boolean[] useRestMatrix;
    private final Matrix4f[] local;
    private final Matrix4f[] world;
    private final Matrix4f[][] jointMatrices;
    private final Matrix3f[][] jointNormalMatrices;
    private final Matrix3f nodeNormal = new Matrix3f();
    private final Quaternionf qa = new Quaternionf();
    private final Quaternionf qb = new Quaternionf();
    private final float[] sample = new float[4];

    GltfPose(GltfModel model) {
        this.model = model;
        int n = model.nodes.size();
        translation = new float[n * 3];
        rotation = new float[n * 4];
        scale = new float[n * 3];
        useRestMatrix = new boolean[n];
        local = new Matrix4f[n];
        world = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            local[i] = new Matrix4f();
            world[i] = new Matrix4f();
        }
        List<Skin> skins = model.skins;
        jointMatrices = new Matrix4f[skins.size()][];
        jointNormalMatrices = new Matrix3f[skins.size()][];
        for (int s = 0; s < skins.size(); s++) {
            int joints = skins.get(s).joints().length;
            jointMatrices[s] = new Matrix4f[joints];
            jointNormalMatrices[s] = new Matrix3f[joints];
            for (int j = 0; j < joints; j++) {
                jointMatrices[s][j] = new Matrix4f();
                jointNormalMatrices[s][j] = new Matrix3f();
            }
        }
        setBind();
    }

    public GltfModel model() {
        return model;
    }

    /** Resets every node to its rest transform. */
    public GltfPose setBind() {
        resetToRest();
        update();
        return this;
    }

    /**
     * Rest pose overridden by {@code clip} sampled at {@code seconds}, clamped to the clip's key range.
     * Looping/wrapping is the caller's choice ({@code seconds % clip.duration()}).
     */
    public GltfPose setClip(Clip clip, float seconds) {
        resetToRest();
        for (Channel c : clip.channels()) {
            int node = c.node();
            int width = c.target() == GltfModel.Target.ROTATION ? 4 : 3;
            sampleChannel(c, width, seconds);
            switch (c.target()) {
                case TRANSLATION -> System.arraycopy(sample, 0, translation, node * 3, 3);
                case SCALE -> System.arraycopy(sample, 0, scale, node * 3, 3);
                case ROTATION -> System.arraycopy(sample, 0, rotation, node * 4, 4);
            }
            useRestMatrix[node] = false;
        }
        update();
        return this;
    }

    /**
     * Rest pose with every mapped node's local transform taken from {@code source}'s current pose:
     * {@code map[node]} is the source node index, or -1 to keep the rest transform (see {@link #nodeMap}).
     */
    public GltfPose setFrom(GltfPose source, int[] map) {
        resetToRest();
        for (int i = 0; i < map.length; i++) {
            int s = map[i];
            if (s < 0) continue;
            System.arraycopy(source.translation, s * 3, translation, i * 3, 3);
            System.arraycopy(source.rotation, s * 4, rotation, i * 4, 4);
            System.arraycopy(source.scale, s * 3, scale, i * 3, 3);
            useRestMatrix[i] = false;
        }
        update();
        return this;
    }

    /** For each node of {@code target}, the index of {@code source}'s node with the same name, or -1. */
    public static int[] nodeMap(GltfModel target, GltfModel source) {
        int[] map = new int[target.nodes.size()];
        for (int i = 0; i < map.length; i++) {
            String name = target.nodes.get(i).name();
            map[i] = name == null ? -1 : source.nodeIndex(name);
        }
        return map;
    }

    /**
     * Overrides skin {@code skin}'s joint matrices (world · inverseBind per joint slot) for the following
     * {@link #skin} calls, e.g. when retargeting onto another skeleton's model-space pose. The next pose update
     * recomputes them.
     */
    public void setJointMatrix(int skin, int joint, Matrix4fc matrix) {
        jointMatrices[skin][joint].set(matrix);
        jointMatrices[skin][joint].normal(jointNormalMatrices[skin][joint]);
    }

    /** Scene-space matrix of node {@code index}. */
    public Matrix4fc world(int index) {
        return world[index];
    }

    /** Matrix for drawing {@code p}: its node's world matrix, or identity for skinned primitives (see {@link #skin}). */
    public Matrix4fc drawMatrix(Primitive p) {
        return p.skinned() ? IDENTITY : world[p.node()];
    }

    /** Joint matrix (world(joint) * inverseBind) for joint slot {@code joint} of skin {@code skin}. */
    public Matrix4fc jointMatrix(int skin, int joint) {
        return jointMatrices[skin][joint];
    }

    /**
     * Linear-blend skins {@code p} into scene space. {@code outPositions} (and {@code outNormals}, may be null) need at
     * least {@code p.positions().length} floats. Non-skinned primitives are transformed by their node world matrix.
     */
    public void skin(Primitive p, float[] outPositions, float[] outNormals) {
        float[] pos = p.positions();
        float[] nrm = p.normals();
        int vertices = p.vertexCount();
        if (!p.skinned()) {
            Matrix4f m = world[p.node()];
            m.normal(nodeNormal);
            for (int v = 0; v < vertices; v++) {
                int o = v * 3;
                transformPoint(m, 1f, pos[o], pos[o + 1], pos[o + 2], outPositions, o, false);
                if (outNormals != null) {
                    transformNormal(nodeNormal, 1f, nrm[o], nrm[o + 1], nrm[o + 2], outNormals, o, false);
                    normalize(outNormals, o);
                }
            }
            return;
        }
        Matrix4f[] joints = jointMatrices[p.skin()];
        Matrix3f[] normals = jointNormalMatrices[p.skin()];
        int[] ji = p.joints();
        float[] jw = p.weights();
        for (int v = 0; v < vertices; v++) {
            int o = v * 3;
            float x = pos[o], y = pos[o + 1], z = pos[o + 2];
            outPositions[o] = outPositions[o + 1] = outPositions[o + 2] = 0f;
            if (outNormals != null) outNormals[o] = outNormals[o + 1] = outNormals[o + 2] = 0f;
            boolean any = false;
            for (int k = 0; k < 4; k++) {
                float w = jw[v * 4 + k];
                if (w == 0f) continue;
                int j = ji[v * 4 + k];
                transformPoint(joints[j], w, x, y, z, outPositions, o, true);
                if (outNormals != null) transformNormal(normals[j], w, nrm[o], nrm[o + 1], nrm[o + 2], outNormals, o, true);
                any = true;
            }
            if (!any) {
                Matrix4f m = world[p.node()];
                transformPoint(m, 1f, x, y, z, outPositions, o, false);
                if (outNormals != null) {
                    m.normal(nodeNormal);
                    transformNormal(nodeNormal, 1f, nrm[o], nrm[o + 1], nrm[o + 2], outNormals, o, false);
                }
            }
            if (outNormals != null) normalize(outNormals, o);
        }
    }

    private static void transformPoint(Matrix4f m, float w, float x, float y, float z, float[] out, int o, boolean add) {
        float rx = w * (m.m00() * x + m.m10() * y + m.m20() * z + m.m30());
        float ry = w * (m.m01() * x + m.m11() * y + m.m21() * z + m.m31());
        float rz = w * (m.m02() * x + m.m12() * y + m.m22() * z + m.m32());
        if (add) {
            out[o] += rx;
            out[o + 1] += ry;
            out[o + 2] += rz;
        } else {
            out[o] = rx;
            out[o + 1] = ry;
            out[o + 2] = rz;
        }
    }

    private static void transformNormal(Matrix3f m, float w, float x, float y, float z, float[] out, int o, boolean add) {
        float rx = w * (m.m00 * x + m.m10 * y + m.m20 * z);
        float ry = w * (m.m01 * x + m.m11 * y + m.m21 * z);
        float rz = w * (m.m02 * x + m.m12 * y + m.m22 * z);
        if (add) {
            out[o] += rx;
            out[o + 1] += ry;
            out[o + 2] += rz;
        } else {
            out[o] = rx;
            out[o + 1] = ry;
            out[o + 2] = rz;
        }
    }

    private static void normalize(float[] a, int o) {
        float len = (float) Math.sqrt(a[o] * a[o] + a[o + 1] * a[o + 1] + a[o + 2] * a[o + 2]);
        if (len > 1e-12f) {
            a[o] /= len;
            a[o + 1] /= len;
            a[o + 2] /= len;
        } else {
            a[o] = 0f;
            a[o + 1] = 1f;
            a[o + 2] = 0f;
        }
    }

    private void resetToRest() {
        List<Node> nodes = model.nodes;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            System.arraycopy(n.translation(), 0, translation, i * 3, 3);
            System.arraycopy(n.rotation(), 0, rotation, i * 4, 4);
            System.arraycopy(n.scale(), 0, scale, i * 3, 3);
            useRestMatrix[i] = n.hasMatrix();
        }
    }

    private void update() {
        List<Node> nodes = model.nodes;
        for (int i : model.nodeOrder) {
            Node n = nodes.get(i);
            Matrix4f l = local[i];
            if (useRestMatrix[i]) {
                l.set(n.restMatrix());
            } else {
                qa.set(rotation[i * 4], rotation[i * 4 + 1], rotation[i * 4 + 2], rotation[i * 4 + 3]);
                l.translationRotateScale(
                    translation[i * 3], translation[i * 3 + 1], translation[i * 3 + 2],
                    qa.x, qa.y, qa.z, qa.w,
                    scale[i * 3], scale[i * 3 + 1], scale[i * 3 + 2]
                );
            }
            if (n.parent() < 0) {
                world[i].set(l);
            } else {
                world[n.parent()].mul(l, world[i]);
            }
        }
        List<Skin> skins = model.skins;
        for (int s = 0; s < skins.size(); s++) {
            Skin skin = skins.get(s);
            int[] joints = skin.joints();
            for (int j = 0; j < joints.length; j++) {
                world[joints[j]].mul(skin.inverseBindMatrices()[j], jointMatrices[s][j]);
                jointMatrices[s][j].normal(jointNormalMatrices[s][j]);
            }
        }
    }

    private void sampleChannel(Channel c, int width, float t) {
        float[] times = c.times();
        float[] values = c.values();
        int keys = times.length;
        boolean cubic = c.interpolation() == GltfModel.Interpolation.CUBICSPLINE;
        int stride = cubic ? width * 3 : width;
        int valueOffset = cubic ? width : 0;
        if (keys == 1 || t <= times[0]) {
            System.arraycopy(values, valueOffset, sample, 0, width);
            return;
        }
        if (t >= times[keys - 1]) {
            System.arraycopy(values, (keys - 1) * stride + valueOffset, sample, 0, width);
            return;
        }
        int lo = 0, hi = keys - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) lo = mid;
            else hi = mid;
        }
        float t0 = times[lo], t1 = times[hi];
        float dt = t1 - t0;
        float u = dt > 0f ? (t - t0) / dt : 0f;
        switch (c.interpolation()) {
            case STEP -> System.arraycopy(values, lo * stride, sample, 0, width);
            case LINEAR -> {
                int a = lo * stride, b = hi * stride;
                if (width == 4) {
                    qa.set(values[a], values[a + 1], values[a + 2], values[a + 3]).normalize();
                    qb.set(values[b], values[b + 1], values[b + 2], values[b + 3]).normalize();
                    qa.slerp(qb, u);
                    sample[0] = qa.x;
                    sample[1] = qa.y;
                    sample[2] = qa.z;
                    sample[3] = qa.w;
                } else {
                    for (int k = 0; k < 3; k++) sample[k] = values[a + k] + (values[b + k] - values[a + k]) * u;
                }
            }
            case CUBICSPLINE -> {
                float u2 = u * u, u3 = u2 * u;
                float h00 = 2 * u3 - 3 * u2 + 1;
                float h10 = (u3 - 2 * u2 + u) * dt;
                float h01 = -2 * u3 + 3 * u2;
                float h11 = (u3 - u2) * dt;
                int a = lo * stride, b = hi * stride;
                for (int k = 0; k < width; k++) {
                    float v0 = values[a + width + k];
                    float out0 = values[a + 2 * width + k];
                    float in1 = values[b + k];
                    float v1 = values[b + width + k];
                    sample[k] = h00 * v0 + h10 * out0 + h01 * v1 + h11 * in1;
                }
                if (width == 4) {
                    float len = (float) Math.sqrt(sample[0] * sample[0] + sample[1] * sample[1]
                        + sample[2] * sample[2] + sample[3] * sample[3]);
                    if (len > 1e-12f) for (int k = 0; k < 4; k++) sample[k] /= len;
                    else { sample[0] = sample[1] = sample[2] = 0f; sample[3] = 1f; }
                }
            }
        }
    }
}
