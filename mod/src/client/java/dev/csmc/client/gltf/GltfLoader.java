package dev.csmc.client.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.csmc.client.gltf.GltfModel.AlphaMode;
import dev.csmc.client.gltf.GltfModel.Channel;
import dev.csmc.client.gltf.GltfModel.Clip;
import dev.csmc.client.gltf.GltfModel.Interpolation;
import dev.csmc.client.gltf.GltfModel.Node;
import dev.csmc.client.gltf.GltfModel.Primitive;
import dev.csmc.client.gltf.GltfModel.Skin;
import dev.csmc.client.gltf.GltfModel.Target;
import dev.csmc.client.gltf.GltfModel.Texture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Dependency-free glTF 2.0 reader (.gltf with external/data-URI buffers and images, or .glb).
 *
 * <p>Supported: TRIANGLES / TRIANGLE_STRIP / TRIANGLE_FAN, indexed and non-indexed; accessor byte
 * offsets, strides, sparse accessors, normalized integer components (KHR_mesh_quantization); node
 * TRS/matrix hierarchy; skins with inverseBindMatrices (JOINTS_0/WEIGHTS_0); node animations with
 * STEP/LINEAR/CUBICSPLINE; baseColorTexture/baseColorFactor, alpha mode and double-sided flags.
 *
 * <p>Not supported (ignored with a warning): morph targets and weight animations, points/lines,
 * JOINTS_1/WEIGHTS_1, texture transforms/samplers, other material channels. Any
 * {@code extensionsRequired} entry other than KHR_mesh_quantization (e.g. Draco, meshopt, BasisU)
 * is rejected.
 */
public final class GltfLoader {
    private static final long MAX_FILE_BYTES = 512L << 20;
    private static final Set<String> SUPPORTED_REQUIRED = Set.of("KHR_mesh_quantization");
    /** Upper bound for accessors without a bufferView (implicit zeros + sparse); 16M components. */
    private static final int MAX_IMPLICIT_COMPONENTS = 1 << 24;
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private static final int BYTE = 5120;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int SHORT = 5122;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;
    private static final int FLOAT = 5126;

    private final JsonObject root;
    private final Path dir;
    private final byte[] glbBin;
    private final List<String> warnings = new ArrayList<>();
    private final Map<Integer, byte[]> buffers = new HashMap<>();
    private final Map<Integer, float[]> floatCache = new HashMap<>();

    private GltfLoader(JsonObject root, Path dir, byte[] glbBin) {
        this.root = root;
        this.dir = dir;
        this.glbBin = glbBin;
    }

    /** Which mesh-instancing nodes to draw, decided from all of their names (node name, else mesh name). */
    @FunctionalInterface
    public interface MeshFilter {
        MeshFilter ALL = names -> name -> true;
        /** Skeleton and clips only (animation glTFs): no mesh is decoded, and having none is not an error. */
        MeshFilter NONE = names -> name -> false;

        java.util.function.Predicate<String> select(List<String> meshNodeNames);
    }

    /** Loads and fully decodes every mesh. Any failure is reported as {@link GltfException} (or I/O error). */
    public static GltfModel load(Path gltf) throws IOException {
        return load(gltf, MeshFilter.ALL);
    }

    /**
     * Loads only meshes accepted by {@code filter}; skipped meshes are never decoded and their textures never read.
     */
    public static GltfModel load(Path gltf, MeshFilter filter) throws IOException {
        Path file = gltf.toRealPath();
        Path dir = file.getParent();
        if (dir == null) {
            throw new GltfException("glTF file has no parent directory: " + gltf);
        }
        byte[] raw = readCapped(file);
        try {
            JsonObject json;
            byte[] bin = null;
            if (raw.length >= 12 && le(raw, 0) == GLB_MAGIC) {
                ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                if (bb.getInt(4) != 2) {
                    throw new GltfException("Unsupported GLB container version " + bb.getInt(4));
                }
                int total = (int) Math.min(Integer.toUnsignedLong(bb.getInt(8)), raw.length);
                String jsonText = null;
                int pos = 12;
                while (pos + 8 <= total) {
                    long len = Integer.toUnsignedLong(bb.getInt(pos));
                    int type = bb.getInt(pos + 4);
                    if (pos + 8 + len > total) {
                        throw new GltfException("GLB chunk exceeds file length");
                    }
                    int start = pos + 8;
                    if (type == CHUNK_JSON && jsonText == null) {
                        jsonText = new String(raw, start, (int) len, StandardCharsets.UTF_8);
                    } else if (type == CHUNK_BIN && bin == null) {
                        bin = Arrays.copyOfRange(raw, start, start + (int) len);
                    }
                    pos = start + (int) ((len + 3) & ~3L);
                }
                if (jsonText == null) {
                    throw new GltfException("GLB has no JSON chunk");
                }
                json = JsonParser.parseString(jsonText).getAsJsonObject();
            } else {
                String text = new String(raw, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) {
                    text = text.substring(1);
                }
                json = JsonParser.parseString(text).getAsJsonObject();
            }
            return new GltfLoader(json, dir, bin).build(filter);
        } catch (GltfException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GltfException("Malformed glTF " + gltf.getFileName() + ": " + e, e);
        }
    }

    private GltfModel build(MeshFilter filter) throws IOException {
        checkAssetAndExtensions();
        List<Node> nodes = parseNodes();
        int[] order = traversalOrder(nodes);
        int[] roots = sceneRoots(nodes);
        List<Skin> skins = parseSkins(nodes);
        List<String> meshNodeNames = new ArrayList<>();
        for (Node node : nodes) {
            if (node.mesh() >= 0) meshNodeNames.add(meshNodeName(node));
        }
        java.util.function.Predicate<String> keep = filter.select(List.copyOf(meshNodeNames));

        Map<Integer, Integer> textureForImage = new LinkedHashMap<>();
        List<Texture> textures = new ArrayList<>();
        Map<Integer, List<Primitive>> meshGeometry = new HashMap<>();
        List<Primitive> primitives = new ArrayList<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        boolean[] visited = new boolean[nodes.size()];
        for (int i = roots.length - 1; i >= 0; i--) {
            stack.push(roots[i]);
        }
        while (!stack.isEmpty()) {
            int ni = stack.pop();
            if (visited[ni]) {
                continue;
            }
            visited[ni] = true;
            Node node = nodes.get(ni);
            if (node.mesh() >= 0 && keep.test(meshNodeName(node))) {
                List<Primitive> geoms = meshGeometry.get(node.mesh());
                if (geoms == null) {
                    geoms = parseMesh(node.mesh(), textureForImage, textures);
                    meshGeometry.put(node.mesh(), geoms);
                }
                for (Primitive g : geoms) {
                    int skin = node.skin();
                    if (skin >= 0 && g.joints() == null) {
                        warn("node " + ni + " has a skin but mesh " + node.mesh() + " lacks JOINTS_0/WEIGHTS_0; drawn rigid");
                        skin = -1;
                    }
                    if (skin >= 0) {
                        int jc = skins.get(skin).joints().length;
                        for (int j : g.joints()) {
                            if (j < 0 || j >= jc) {
                                throw new GltfException("JOINTS_0 index " + j + " out of range for skin " + skin);
                            }
                        }
                    }
                    primitives.add(new Primitive(ni, skin, g.positions(), g.normals(), g.uvs(), g.indices(),
                            skin >= 0 ? g.joints() : null, skin >= 0 ? g.weights() : null,
                            g.texture(), g.baseColorFactor(), g.alphaMode(), g.alphaCutoff(), g.doubleSided()));
                }
            }
            int[] children = node.children();
            for (int c = children.length - 1; c >= 0; c--) {
                stack.push(children[c]);
            }
        }
        if (primitives.isEmpty() && filter != MeshFilter.NONE) {
            throw new GltfException("glTF scene contains no renderable triangle primitives");
        }
        Map<String, Clip> clips = parseAnimations(nodes.size());
        return new GltfModel(primitives, textures, nodes, skins, clips, roots, order, warnings);
    }

    private String meshNodeName(Node node) {
        if (node.name() != null && !node.name().isEmpty()) return node.name();
        return str(arr(root, "meshes").get(node.mesh()).getAsJsonObject(), "name", "");
    }

    // ---------------------------------------------------------------- asset / extensions

    private void checkAssetAndExtensions() throws GltfException {
        JsonObject asset = obj(root, "asset");
        if (asset == null) {
            throw new GltfException("Missing required 'asset' object");
        }
        String version = str(asset, "version", "");
        String min = str(asset, "minVersion", null);
        if (!version.startsWith("2.") || (min != null && !min.equals("2.0"))) {
            throw new GltfException("Unsupported glTF version " + version + (min != null ? " (minVersion " + min + ")" : ""));
        }
        for (JsonElement e : arr(root, "extensionsRequired")) {
            String ext = e.getAsString();
            if (!SUPPORTED_REQUIRED.contains(ext)) {
                throw new GltfException("Required glTF extension not supported: " + ext
                        + " (re-export without compression, e.g. Draco/meshopt/BasisU)");
            }
        }
        for (JsonElement e : arr(root, "extensionsUsed")) {
            String ext = e.getAsString();
            if (!SUPPORTED_REQUIRED.contains(ext)) {
                warn("optional extension ignored: " + ext);
            }
        }
    }

    // ---------------------------------------------------------------- nodes / scenes / skins

    private List<Node> parseNodes() throws GltfException {
        JsonArray arr = arr(root, "nodes");
        int n = arr.size();
        int meshCount = arr(root, "meshes").size();
        int skinCount = arr(root, "skins").size();
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        int[][] children = new int[n][];
        for (int i = 0; i < n; i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            children[i] = intArray(o, "children");
            for (int c : children[i]) {
                checkIndex(c, n, "node child");
                if (parent[c] >= 0 || c == i) {
                    throw new GltfException("Node " + c + " has multiple parents or is its own child");
                }
                parent[c] = i;
            }
        }
        List<Node> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            int mesh = integer(o, "mesh", -1);
            int skin = integer(o, "skin", -1);
            if (mesh >= 0) {
                checkIndex(mesh, meshCount, "mesh");
            }
            if (skin >= 0) {
                checkIndex(skin, skinCount, "skin");
            }
            float[] t = floatArray(o, "translation", new float[] {0, 0, 0}, 3);
            float[] r = floatArray(o, "rotation", new float[] {0, 0, 0, 1}, 4);
            float[] s = floatArray(o, "scale", new float[] {1, 1, 1}, 3);
            normalizeQuat(r);
            Matrix4f rest;
            boolean hasMatrix = o.has("matrix");
            if (hasMatrix) {
                rest = new Matrix4f().set(floatArray(o, "matrix", null, 16));
                org.joml.Vector3f tv = rest.getTranslation(new org.joml.Vector3f());
                org.joml.Vector3f sv = rest.getScale(new org.joml.Vector3f());
                org.joml.Quaternionf q = rest.getNormalizedRotation(new org.joml.Quaternionf());
                t = new float[] {tv.x, tv.y, tv.z};
                s = new float[] {sv.x, sv.y, sv.z};
                r = new float[] {q.x, q.y, q.z, q.w};
            } else {
                rest = new Matrix4f().translationRotateScale(t[0], t[1], t[2], r[0], r[1], r[2], r[3], s[0], s[1], s[2]);
            }
            nodes.add(new Node(str(o, "name", "node_" + i), parent[i], children[i], mesh, skin, t, r, s, rest, hasMatrix));
        }
        return nodes;
    }

    private static int[] traversalOrder(List<Node> nodes) throws GltfException {
        int[] order = new int[nodes.size()];
        int count = 0;
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (nodes.get(i).parent() < 0) {
                stack.push(i);
            }
        }
        while (!stack.isEmpty()) {
            int i = stack.pop();
            order[count++] = i;
            int[] c = nodes.get(i).children();
            for (int k = c.length - 1; k >= 0; k--) {
                stack.push(c[k]);
            }
        }
        if (count != nodes.size()) {
            throw new GltfException("Node hierarchy contains a cycle");
        }
        return order;
    }

    private int[] sceneRoots(List<Node> nodes) throws GltfException {
        JsonArray scenes = arr(root, "scenes");
        if (scenes.isEmpty()) {
            return java.util.stream.IntStream.range(0, nodes.size()).filter(i -> nodes.get(i).parent() < 0).toArray();
        }
        int scene = integer(root, "scene", 0);
        checkIndex(scene, scenes.size(), "scene");
        int[] roots = intArray(scenes.get(scene).getAsJsonObject(), "nodes");
        for (int r : roots) {
            checkIndex(r, nodes.size(), "scene root node");
        }
        return roots;
    }

    private List<Skin> parseSkins(List<Node> nodes) throws GltfException {
        JsonArray arr = arr(root, "skins");
        List<Skin> skins = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            int[] joints = intArray(o, "joints");
            if (joints.length == 0) {
                throw new GltfException("Skin " + i + " has no joints");
            }
            for (int j : joints) {
                checkIndex(j, nodes.size(), "skin joint");
            }
            Matrix4fc[] ibm = new Matrix4fc[joints.length];
            int acc = integer(o, "inverseBindMatrices", -1);
            if (acc >= 0) {
                Accessor a = accessor(acc);
                if (a.comps != 16 || a.count < joints.length) {
                    throw new GltfException("Skin " + i + " inverseBindMatrices must be MAT4 x " + joints.length);
                }
                float[] m = floats(acc);
                for (int j = 0; j < joints.length; j++) {
                    ibm[j] = new Matrix4f().set(Arrays.copyOfRange(m, j * 16, j * 16 + 16));
                }
            } else {
                for (int j = 0; j < joints.length; j++) {
                    ibm[j] = new Matrix4f();
                }
            }
            skins.add(new Skin(str(o, "name", "skin_" + i), joints, ibm));
        }
        return skins;
    }

    // ---------------------------------------------------------------- meshes / materials

    /** Geometry per mesh primitive; node and skin are filled by the caller. */
    private List<Primitive> parseMesh(int meshIndex, Map<Integer, Integer> textureForImage, List<Texture> textures)
            throws IOException {
        JsonObject mesh = arr(root, "meshes").get(meshIndex).getAsJsonObject();
        JsonArray prims = arr(mesh, "primitives");
        List<Primitive> out = new ArrayList<>();
        for (int pi = 0; pi < prims.size(); pi++) {
            JsonObject p = prims.get(pi).getAsJsonObject();
            String where = "mesh " + meshIndex + " primitive " + pi;
            int mode = integer(p, "mode", 4);
            if (mode < 4 || mode > 6) {
                warn(where + ": mode " + mode + " (points/lines) skipped");
                continue;
            }
            if (!arr(p, "targets").isEmpty()) {
                warn(where + ": morph targets ignored");
            }
            JsonObject attrs = obj(p, "attributes");
            if (attrs == null || !attrs.has("POSITION")) {
                throw new GltfException(where + ": missing POSITION");
            }
            int posAcc = attrs.get("POSITION").getAsInt();
            requireComps(posAcc, 3, where + " POSITION");
            float[] positions = floats(posAcc).clone();
            int vc = positions.length / 3;
            if (vc == 0) {
                continue;
            }

            // material
            int texture = -1;
            int texCoord = 0;
            float[] factor = {1, 1, 1, 1};
            AlphaMode alphaMode = AlphaMode.OPAQUE;
            float cutoff = 0.5f;
            boolean doubleSided = false;
            int matIndex = integer(p, "material", -1);
            if (matIndex >= 0) {
                JsonArray mats = arr(root, "materials");
                checkIndex(matIndex, mats.size(), "material");
                JsonObject mat = mats.get(matIndex).getAsJsonObject();
                JsonObject pbr = obj(mat, "pbrMetallicRoughness");
                if (pbr != null) {
                    factor = floatArray(pbr, "baseColorFactor", factor, 4);
                    JsonObject bct = obj(pbr, "baseColorTexture");
                    if (bct != null) {
                        texCoord = integer(bct, "texCoord", 0);
                        texture = resolveTexture(integer(bct, "index", -1), textureForImage, textures, where);
                    }
                }
                try {
                    alphaMode = AlphaMode.valueOf(str(mat, "alphaMode", "OPAQUE"));
                } catch (IllegalArgumentException e) {
                    warn(where + ": unknown alphaMode, using OPAQUE");
                }
                cutoff = mat.has("alphaCutoff") ? mat.get("alphaCutoff").getAsFloat() : 0.5f;
                doubleSided = mat.has("doubleSided") && mat.get("doubleSided").getAsBoolean();
            }

            float[] uvs;
            String uvName = "TEXCOORD_" + texCoord;
            if (attrs.has(uvName)) {
                int a = attrs.get(uvName).getAsInt();
                requireComps(a, 2, where + " " + uvName);
                uvs = floats(a).clone();
                requireCount(uvs.length / 2, vc, where + " " + uvName);
            } else {
                uvs = new float[vc * 2];
            }

            float[] normals = null;
            if (attrs.has("NORMAL")) {
                int a = attrs.get("NORMAL").getAsInt();
                requireComps(a, 3, where + " NORMAL");
                normals = floats(a).clone();
                requireCount(normals.length / 3, vc, where + " NORMAL");
            }

            int[] joints = null;
            float[] weights = null;
            if (attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")) {
                int ja = attrs.get("JOINTS_0").getAsInt();
                int wa = attrs.get("WEIGHTS_0").getAsInt();
                requireComps(ja, 4, where + " JOINTS_0");
                requireComps(wa, 4, where + " WEIGHTS_0");
                joints = ints(ja);
                weights = floats(wa).clone();
                requireCount(joints.length / 4, vc, where + " JOINTS_0");
                requireCount(weights.length / 4, vc, where + " WEIGHTS_0");
                normalizeWeights(weights);
                if (attrs.has("JOINTS_1")) {
                    warn(where + ": JOINTS_1/WEIGHTS_1 ignored (max 4 influences)");
                }
            }

            // indices
            int[] raw;
            if (p.has("indices")) {
                int ia = p.get("indices").getAsInt();
                requireComps(ia, 1, where + " indices");
                raw = ints(ia);
            } else {
                raw = new int[vc];
                for (int i = 0; i < vc; i++) {
                    raw[i] = i;
                }
            }
            for (int idx : raw) {
                if (idx < 0 || idx >= vc) {
                    throw new GltfException(where + ": index " + idx + " out of range (" + vc + " vertices)");
                }
            }
            int[] indices = triangulate(raw, mode, where);
            if (indices.length == 0) {
                continue;
            }

            if (normals == null) {
                // Flat normals require unwelding so each triangle owns its vertices.
                int n = indices.length;
                float[] np = new float[n * 3];
                float[] nu = new float[n * 2];
                int[] nj = joints != null ? new int[n * 4] : null;
                float[] nw = weights != null ? new float[n * 4] : null;
                for (int i = 0; i < n; i++) {
                    int src = indices[i];
                    System.arraycopy(positions, src * 3, np, i * 3, 3);
                    System.arraycopy(uvs, src * 2, nu, i * 2, 2);
                    if (nj != null) {
                        System.arraycopy(joints, src * 4, nj, i * 4, 4);
                        System.arraycopy(weights, src * 4, nw, i * 4, 4);
                    }
                    indices[i] = i;
                }
                positions = np;
                uvs = nu;
                joints = nj;
                weights = nw;
                normals = flatNormals(positions);
            }
            out.add(new Primitive(-1, -1, positions, normals, uvs, indices, joints, weights,
                    texture, factor, alphaMode, cutoff, doubleSided));
        }
        return out;
    }

    private int[] triangulate(int[] raw, int mode, String where) {
        int n = raw.length;
        if (mode == 4) {
            if (n % 3 != 0) {
                warn(where + ": index count not a multiple of 3; trailing indices dropped");
                return Arrays.copyOf(raw, n - n % 3);
            }
            return raw;
        }
        if (n < 3) {
            return new int[0];
        }
        int[] out = new int[(n - 2) * 3];
        for (int i = 0; i < n - 2; i++) {
            if (mode == 5) {
                out[i * 3] = raw[i];
                out[i * 3 + 1] = raw[i + 1 + (i & 1)];
                out[i * 3 + 2] = raw[i + 2 - (i & 1)];
            } else {
                out[i * 3] = raw[i + 1];
                out[i * 3 + 1] = raw[i + 2];
                out[i * 3 + 2] = raw[0];
            }
        }
        return out;
    }

    private static float[] flatNormals(float[] p) {
        float[] n = new float[p.length];
        for (int t = 0; t + 9 <= p.length; t += 9) {
            float ax = p[t + 3] - p[t], ay = p[t + 4] - p[t + 1], az = p[t + 5] - p[t + 2];
            float bx = p[t + 6] - p[t], by = p[t + 7] - p[t + 1], bz = p[t + 8] - p[t + 2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-20f) {
                nx /= len;
                ny /= len;
                nz /= len;
            } else {
                nx = 0;
                ny = 1;
                nz = 0;
            }
            for (int v = 0; v < 3; v++) {
                n[t + v * 3] = nx;
                n[t + v * 3 + 1] = ny;
                n[t + v * 3 + 2] = nz;
            }
        }
        return n;
    }

    private static void normalizeWeights(float[] w) {
        for (int v = 0; v < w.length; v += 4) {
            float sum = 0;
            for (int k = 0; k < 4; k++) {
                if (!(w[v + k] > 0f)) {
                    w[v + k] = 0f;
                }
                sum += w[v + k];
            }
            if (sum > 0f) {
                for (int k = 0; k < 4; k++) {
                    w[v + k] /= sum;
                }
            } else {
                w[v] = 1f;
            }
        }
    }

    private int resolveTexture(int texIndex, Map<Integer, Integer> textureForImage, List<Texture> textures, String where)
            throws IOException {
        if (texIndex < 0) {
            return -1;
        }
        JsonArray texArr = arr(root, "textures");
        checkIndex(texIndex, texArr.size(), "texture");
        JsonObject tex = texArr.get(texIndex).getAsJsonObject();
        int image = integer(tex, "source", -1);
        if (image < 0) {
            warn(where + ": texture " + texIndex + " has no core image source (extension-only); untextured");
            return -1;
        }
        Integer existing = textureForImage.get(image);
        if (existing != null) {
            return existing;
        }
        JsonArray images = arr(root, "images");
        checkIndex(image, images.size(), "image");
        JsonObject img = images.get(image).getAsJsonObject();
        byte[] bytes;
        String mime = str(img, "mimeType", null);
        String name = str(img, "name", null);
        if (img.has("uri")) {
            String uri = img.get("uri").getAsString();
            if (uri.startsWith("data:")) {
                String declared = uri.substring(5, Math.max(5, uri.indexOf(',') < 0 ? 5 : uri.indexOf(',')));
                int semi = declared.indexOf(';');
                if (mime == null && !declared.isEmpty()) {
                    mime = semi >= 0 ? declared.substring(0, semi) : declared;
                }
            } else if (name == null) {
                name = uri;
            }
            bytes = readUri(uri);
        } else if (img.has("bufferView")) {
            int bv = img.get("bufferView").getAsInt();
            BufferView view = bufferView(bv);
            bytes = Arrays.copyOfRange(view.data, view.offset, view.offset + view.length);
        } else {
            throw new GltfException("Image " + image + " has neither uri nor bufferView");
        }
        if (mime == null || mime.isEmpty()) {
            mime = sniffMime(bytes);
        }
        int idx = textures.size();
        textures.add(new Texture(name != null ? name : "image_" + image, mime, bytes));
        textureForImage.put(image, idx);
        return idx;
    }

    private static String sniffMime(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- animations

    private Map<String, Clip> parseAnimations(int nodeCount) throws GltfException {
        JsonArray anims = arr(root, "animations");
        Map<String, Clip> clips = new LinkedHashMap<>();
        for (int ai = 0; ai < anims.size(); ai++) {
            JsonObject anim = anims.get(ai).getAsJsonObject();
            String name = str(anim, "name", "animation_" + ai);
            if (clips.containsKey(name)) {
                name = name + "#" + ai;
            }
            JsonArray samplers = arr(anim, "samplers");
            List<Channel> channels = new ArrayList<>();
            float duration = 0f;
            for (JsonElement ce : arr(anim, "channels")) {
                JsonObject ch = ce.getAsJsonObject();
                JsonObject target = obj(ch, "target");
                if (target == null || !target.has("node")) {
                    continue;
                }
                int node = target.get("node").getAsInt();
                checkIndex(node, nodeCount, "animation target node");
                String path = str(target, "path", "");
                Target t = switch (path) {
                    case "translation" -> Target.TRANSLATION;
                    case "rotation" -> Target.ROTATION;
                    case "scale" -> Target.SCALE;
                    default -> null;
                };
                if (t == null) {
                    warn("animation '" + name + "': '" + path + "' channel ignored");
                    continue;
                }
                int comps = t == Target.ROTATION ? 4 : 3;
                int si = integer(ch, "sampler", -1);
                checkIndex(si, samplers.size(), "animation sampler");
                JsonObject sampler = samplers.get(si).getAsJsonObject();
                Interpolation interp;
                try {
                    interp = Interpolation.valueOf(str(sampler, "interpolation", "LINEAR"));
                } catch (IllegalArgumentException e) {
                    throw new GltfException("Unknown interpolation in animation '" + name + "'");
                }
                int in = integer(sampler, "input", -1);
                int outAcc = integer(sampler, "output", -1);
                requireComps(in, 1, "animation input");
                requireComps(outAcc, comps, "animation '" + name + "' " + path + " output");
                float[] times = floats(in);
                float[] values = floats(outAcc);
                int keys = times.length;
                int expected = keys * comps * (interp == Interpolation.CUBICSPLINE ? 3 : 1);
                if (keys == 0) {
                    continue;
                }
                if (values.length < expected) {
                    throw new GltfException("Animation '" + name + "' sampler " + si + " has too few output values");
                }
                for (int k = 1; k < keys; k++) {
                    if (times[k] < times[k - 1]) {
                        throw new GltfException("Animation '" + name + "' keyframe times are not increasing");
                    }
                }
                duration = Math.max(duration, times[keys - 1]);
                channels.add(new Channel(node, t, interp, times,
                        values.length == expected ? values : Arrays.copyOf(values, expected)));
            }
            clips.put(name, new Clip(name, duration, List.copyOf(channels)));
        }
        return clips;
    }

    // ---------------------------------------------------------------- accessors

    private record BufferView(byte[] data, int offset, int length, int stride) {
    }

    private record Accessor(int index, int bufferView, int offset, int componentType, boolean normalized,
                            int count, int comps, JsonObject sparse) {
    }

    @FunctionalInterface
    private interface ValueSink {
        void put(int index, double value);
    }

    private Accessor accessor(int index) throws GltfException {
        JsonArray arr = arr(root, "accessors");
        checkIndex(index, arr.size(), "accessor");
        JsonObject o = arr.get(index).getAsJsonObject();
        int comps = switch (str(o, "type", "")) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new GltfException("Accessor " + index + " has unknown type");
        };
        int ct = integer(o, "componentType", -1);
        if (ct != BYTE && ct != UNSIGNED_BYTE && ct != SHORT && ct != UNSIGNED_SHORT && ct != UNSIGNED_INT && ct != FLOAT) {
            throw new GltfException("Accessor " + index + " has unsupported componentType " + ct);
        }
        String type = str(o, "type", "");
        if ((type.equals("MAT2") || type.equals("MAT3")) && ct != FLOAT) {
            throw new GltfException("Accessor " + index + ": padded integer matrix accessors are not supported");
        }
        int count = integer(o, "count", -1);
        if (count < 0) {
            throw new GltfException("Accessor " + index + " has no count");
        }
        return new Accessor(index, integer(o, "bufferView", -1), integer(o, "byteOffset", 0), ct,
                o.has("normalized") && o.get("normalized").getAsBoolean(), count, comps, obj(o, "sparse"));
    }

    private void requireComps(int accessor, int comps, String what) throws GltfException {
        if (accessor(accessor).comps != comps) {
            throw new GltfException(what + ": expected " + comps + " components per element");
        }
    }

    private static void requireCount(int actual, int expected, String what) throws GltfException {
        if (actual != expected) {
            throw new GltfException(what + ": " + actual + " elements, expected " + expected);
        }
    }

    /** Float view of an accessor (normalized ints mapped to [-1,1]/[0,1]); cached, do not mutate. */
    private float[] floats(int index) throws GltfException {
        float[] cached = floatCache.get(index);
        if (cached != null) {
            return cached;
        }
        Accessor a = accessor(index);
        float[] out = new float[checkedSize(a)];
        boolean norm = a.normalized && a.componentType != FLOAT;
        int ct = a.componentType;
        read(a, (i, v) -> out[i] = norm ? normalize(v, ct) : (float) v);
        floatCache.put(index, out);
        return out;
    }

    private int[] ints(int index) throws GltfException {
        Accessor a = accessor(index);
        if (a.componentType == FLOAT || a.componentType == BYTE || a.componentType == SHORT) {
            throw new GltfException("Accessor " + index + " must use an unsigned integer component type");
        }
        int[] out = new int[checkedSize(a)];
        read(a, (i, v) -> out[i] = v > Integer.MAX_VALUE ? -1 : (int) v);
        return out;
    }

    private static float normalize(double v, int ct) {
        return switch (ct) {
            case BYTE -> (float) Math.max(v / 127.0, -1.0);
            case UNSIGNED_BYTE -> (float) (v / 255.0);
            case SHORT -> (float) Math.max(v / 32767.0, -1.0);
            case UNSIGNED_SHORT -> (float) (v / 65535.0);
            case UNSIGNED_INT -> (float) (v / 4294967295.0);
            default -> (float) v;
        };
    }

    /**
     * Component count of {@code a}, validated against its bufferView (or the implicit-zero cap) before the caller
     * allocates, so a hostile {@code count} cannot force a huge allocation.
     */
    private int checkedSize(Accessor a) throws GltfException {
        long total = (long) a.count * a.comps;
        if (a.bufferView >= 0) {
            BufferView view = bufferView(a.bufferView);
            int compSize = componentSize(a.componentType);
            int elemSize = compSize * a.comps;
            int stride = view.stride > 0 ? view.stride : elemSize;
            if (a.offset < 0 || a.offset % compSize != 0) {
                throw new GltfException("Accessor " + a.index + " has misaligned byteOffset");
            }
            long end = a.count == 0 ? 0 : (long) a.offset + (long) stride * (a.count - 1) + elemSize;
            if (end > view.length) {
                throw new GltfException("Accessor " + a.index + " exceeds its bufferView");
            }
        } else if (total > MAX_IMPLICIT_COMPONENTS) {
            throw new GltfException("Accessor " + a.index + " without bufferView is too large (" + total + ")");
        }
        return (int) total;
    }

    /** Streams accessor components into {@code sink}; callers MUST have sized storage with {@link #checkedSize}. */
    private void read(Accessor a, ValueSink sink) throws GltfException {
        int total = a.count * a.comps;
        if (a.bufferView >= 0) {
            BufferView view = bufferView(a.bufferView);
            int compSize = componentSize(a.componentType);
            int stride = view.stride > 0 ? view.stride : compSize * a.comps;
            ByteBuffer bb = ByteBuffer.wrap(view.data).order(ByteOrder.LITTLE_ENDIAN);
            int base = view.offset + a.offset;
            for (int e = 0; e < a.count; e++) {
                int pos = base + e * stride;
                for (int c = 0; c < a.comps; c++) {
                    sink.put(e * a.comps + c, component(bb, pos + c * compSize, a.componentType));
                }
            }
        } else {
            for (int i = 0; i < total; i++) {
                sink.put(i, 0.0);
            }
        }
        if (a.sparse != null) {
            int sc = integer(a.sparse, "count", 0);
            JsonObject idx = obj(a.sparse, "indices");
            JsonObject val = obj(a.sparse, "values");
            if (sc <= 0 || idx == null || val == null) {
                throw new GltfException("Accessor " + a.index + " has an invalid sparse block");
            }
            int ict = integer(idx, "componentType", -1);
            if (ict != UNSIGNED_BYTE && ict != UNSIGNED_SHORT && ict != UNSIGNED_INT) {
                throw new GltfException("Accessor " + a.index + " sparse indices have invalid componentType");
            }
            BufferView iv = bufferView(integer(idx, "bufferView", -1));
            BufferView vv = bufferView(integer(val, "bufferView", -1));
            int io = integer(idx, "byteOffset", 0);
            int vo = integer(val, "byteOffset", 0);
            int isz = componentSize(ict);
            int vsz = componentSize(a.componentType) * a.comps;
            if ((long) io + (long) sc * isz > iv.length || (long) vo + (long) sc * vsz > vv.length) {
                throw new GltfException("Accessor " + a.index + " sparse data exceeds its bufferView");
            }
            ByteBuffer ib = ByteBuffer.wrap(iv.data).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer vb = ByteBuffer.wrap(vv.data).order(ByteOrder.LITTLE_ENDIAN);
            int csz = componentSize(a.componentType);
            for (int k = 0; k < sc; k++) {
                long target = (long) component(ib, iv.offset + io + k * isz, ict);
                if (target >= a.count) {
                    throw new GltfException("Accessor " + a.index + " sparse index out of range");
                }
                for (int c = 0; c < a.comps; c++) {
                    sink.put((int) target * a.comps + c,
                            component(vb, vv.offset + vo + k * vsz + c * csz, a.componentType));
                }
            }
        }
    }

    private static double component(ByteBuffer bb, int pos, int ct) {
        return switch (ct) {
            case BYTE -> bb.get(pos);
            case UNSIGNED_BYTE -> bb.get(pos) & 0xFF;
            case SHORT -> bb.getShort(pos);
            case UNSIGNED_SHORT -> bb.getShort(pos) & 0xFFFF;
            case UNSIGNED_INT -> Integer.toUnsignedLong(bb.getInt(pos));
            default -> bb.getFloat(pos);
        };
    }

    private static int componentSize(int ct) {
        return switch (ct) {
            case BYTE, UNSIGNED_BYTE -> 1;
            case SHORT, UNSIGNED_SHORT -> 2;
            default -> 4;
        };
    }

    private BufferView bufferView(int index) throws GltfException {
        JsonArray arr = arr(root, "bufferViews");
        checkIndex(index, arr.size(), "bufferView");
        JsonObject o = arr.get(index).getAsJsonObject();
        byte[] data = buffer(integer(o, "buffer", -1));
        int offset = integer(o, "byteOffset", 0);
        int length = integer(o, "byteLength", -1);
        int stride = integer(o, "byteStride", 0);
        if (offset < 0 || length < 0 || (long) offset + length > data.length) {
            throw new GltfException("bufferView " + index + " exceeds its buffer");
        }
        if (stride != 0 && (stride < 4 || stride > 252)) {
            throw new GltfException("bufferView " + index + " has invalid byteStride " + stride);
        }
        return new BufferView(data, offset, length, stride);
    }

    private byte[] buffer(int index) throws GltfException {
        byte[] cached = buffers.get(index);
        if (cached != null) {
            return cached;
        }
        JsonArray arr = arr(root, "buffers");
        checkIndex(index, arr.size(), "buffer");
        JsonObject o = arr.get(index).getAsJsonObject();
        byte[] data;
        if (o.has("uri")) {
            try {
                data = readUri(o.get("uri").getAsString());
            } catch (GltfException e) {
                throw e;
            } catch (IOException e) {
                throw new GltfException("Cannot read buffer " + index + ": " + e.getMessage(), e);
            }
        } else if (index == 0 && glbBin != null) {
            data = glbBin;
        } else {
            throw new GltfException("Buffer " + index + " has no uri and no GLB BIN chunk");
        }
        int declared = integer(o, "byteLength", -1);
        if (declared < 0 || data.length < declared) {
            throw new GltfException("Buffer " + index + " is shorter than its byteLength");
        }
        buffers.put(index, data);
        return data;
    }

    // ---------------------------------------------------------------- URIs

    /** Reads a data: URI or a relative file URI confined to the model directory (symlinks included). */
    private byte[] readUri(String uri) throws IOException {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if (comma < 0) {
                throw new GltfException("Malformed data URI");
            }
            String meta = uri.substring(5, comma);
            String payload = uri.substring(comma + 1);
            try {
                return meta.endsWith(";base64")
                        ? Base64.getDecoder().decode(payload)
                        : percentDecodeBytes(payload);
            } catch (IllegalArgumentException e) {
                throw new GltfException("Malformed data URI payload", e);
            }
        }
        if (uri.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new GltfException("Only relative file URIs and data URIs are allowed: " + uri);
        }
        String decoded = percentDecode(uri);
        if (decoded.isEmpty() || decoded.startsWith("/") || decoded.startsWith("\\") || decoded.indexOf('\0') >= 0) {
            throw new GltfException("Unsafe glTF URI: " + uri);
        }
        Path target;
        try {
            target = dir.resolve(decoded).normalize();
        } catch (InvalidPathException e) {
            throw new GltfException("Invalid glTF URI: " + uri, e);
        }
        if (!target.startsWith(dir)) {
            throw new GltfException("glTF URI escapes the model folder: " + uri);
        }
        Path real = target.toRealPath();
        if (!real.startsWith(dir) || !Files.isRegularFile(real)) {
            throw new GltfException("glTF URI does not resolve to a file inside the model folder: " + uri);
        }
        return readCapped(real);
    }

    private static String percentDecode(String s) throws GltfException {
        return s.indexOf('%') < 0 ? s : new String(percentDecodeBytes(s), StandardCharsets.UTF_8);
    }

    /** Percent-decodes to raw octets (data URI payloads are binary, not text). */
    private static byte[] percentDecodeBytes(String s) throws GltfException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] src = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < src.length; i++) {
            if (src[i] == '%') {
                if (i + 2 >= src.length) {
                    throw new GltfException("Malformed percent-encoding in URI");
                }
                int hi = Character.digit(src[i + 1], 16);
                int lo = Character.digit(src[i + 2], 16);
                if (hi < 0 || lo < 0) {
                    throw new GltfException("Malformed percent-encoding in URI");
                }
                out.write(hi << 4 | lo);
                i += 2;
            } else {
                out.write(src[i]);
            }
        }
        return out.toByteArray();
    }

    private static byte[] readCapped(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new GltfException("File too large (" + size + " bytes): " + file.getFileName());
        }
        return Files.readAllBytes(file);
    }

    // ---------------------------------------------------------------- JSON helpers

    private void warn(String message) {
        warnings.add(message);
    }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    private static void checkIndex(int index, int size, String what) throws GltfException {
        if (index < 0 || index >= size) {
            throw new GltfException("Invalid " + what + " index " + index);
        }
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static int integer(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
    }

    private static int[] intArray(JsonObject o, String key) {
        JsonArray a = arr(o, key);
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    private static float[] floatArray(JsonObject o, String key, float[] def, int size) throws GltfException {
        if (!o.has(key)) {
            if (def == null) {
                throw new GltfException("Missing '" + key + "'");
            }
            return def.clone();
        }
        JsonArray a = arr(o, key);
        if (a.size() != size) {
            throw new GltfException("'" + key + "' must have " + size + " numbers");
        }
        float[] out = new float[size];
        for (int i = 0; i < size; i++) {
            out[i] = a.get(i).getAsFloat();
        }
        return out;
    }

    private static void normalizeQuat(float[] q) {
        float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (len > 1e-12f) {
            for (int i = 0; i < 4; i++) {
                q[i] /= len;
            }
        } else {
            q[0] = q[1] = q[2] = 0;
            q[3] = 1;
        }
    }
}
