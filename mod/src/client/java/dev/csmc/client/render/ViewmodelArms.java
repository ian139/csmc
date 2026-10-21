package dev.csmc.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.csmc.CsmcMod;
import dev.csmc.client.gloves.GloveClient;
import dev.csmc.client.gltf.GltfLoader;
import dev.csmc.client.gltf.GltfModel;
import dev.csmc.client.gltf.GltfPose;
import dev.csmc.data.CachePaths;
import dev.csmc.data.PriorityExecutor;
import dev.csmc.item.GloveItem;
import dev.csmc.melee.KnifeArmOcclusion;
import dev.csmc.skin.SkinResolution;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.viewmodel.gloves.BoundedResidency;
import dev.csmc.viewmodel.gloves.GloveView;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * CS2 first-person arms (agent arms, gloves, sleeves) for knife and gun viewmodel clips. The exporter writes each part to
 * {@code models/arms/<part>/<part>.gltf}, described by the manifest's top-level {@code arms} record:
 * {@code default.ct = {part: [mesh suffix, ...]}} names the meshes worn, and
 * {@code parts.<part>.meshes.<suffix>} gives the mesh {@code node}, its {@code role} and {@code joint_anchors}
 * (unmatched joint → matched joint).
 *
 * <p>The parts are bound in the character T-pose on a hierarchy that differs from the clips', but with identical
 * bone frames, so they are retargeted in model space: each joint's skin matrix is the clip's global matrix of the
 * same-named joint times the joint's inverse bind; a joint the clip lacks (twist, clavicle, ...) uses its anchor's
 * clip global and the anchor's inverse bind. The result is in clip scene space, the same frame as {@code wpn}, and is
 * drawn in the weapon's view space by the main hand only. A worn sleeve hides the bare arm beneath it
 * ({@link KnifeArmOcclusion}), applied once during preparation.
 *
 * <p>Ownership: the loader task owns decoded images until upload hands them to registered textures; textures are
 * owned by the uploaded parts and released by {@link #clear} (resource reload, disconnect). A task abandoned by
 * {@link #clear} closes its images whenever it completes. Clip node maps are built once per clip model and resource
 * generation; drawing reuses every buffer.
 */
final class ViewmodelArms {
    private static final String TEAM = "ct";
    private static PriorityExecutor.Task<List<Prepared>> task;
    private static List<Part> parts;
    private static final Matrix4f JOINT = new Matrix4f();
    private static final Matrix4f DRAW = new Matrix4f();
    private static final Matrix3f NORMAL = new Matrix3f();
    private static final Vector3f NORMAL_VECTOR = new Vector3f();

    private ViewmodelArms() {}

    /**
     * Background output: geometry, decoded textures (one per primitive kept), and per skin the joint names whose
     * clip globals drive each slot plus the slot whose inverse bind applies (itself, or its anchor's slot). Owns its
     * images until {@link #release()} hands them over; {@link #close()} is idempotent.
     */
    private static final class Prepared implements AutoCloseable {
        final String name;
        final GltfModel model;
        final List<GltfModel.Primitive> primitives;
        final List<NativeImage> images;
        final String[][] driver;
        final int[][] bindSlot;
        /** Per primitive: team default-gloves mesh hidden under a worn glove ({@link GloveView#replacedByGlove}). */
        final boolean[] replaced;
        /** Per primitive: glove material taking the worn finish (glove parts only). */
        final boolean[] paintable;
        private boolean owned = true;

        Prepared(String name, GltfModel model, List<GltfModel.Primitive> primitives, List<NativeImage> images,
                 String[][] driver, int[][] bindSlot, boolean[] replaced, boolean[] paintable) {
            this.name = name;
            this.model = model;
            this.primitives = primitives;
            this.images = images;
            this.driver = driver;
            this.bindSlot = bindSlot;
            this.replaced = replaced;
            this.paintable = paintable;
        }

        /** Hands the images to their uploaded textures. */
        synchronized List<NativeImage> release() {
            owned = false;
            return images;
        }

        @Override
        public synchronized void close() {
            if (!owned) return;
            owned = false;
            images.forEach(NativeImage::close);
        }
    }

    private static final class Part {
        final GltfModel model;
        final GltfPose pose;
        final List<GltfModel.Primitive> primitives;
        final RenderType[] types;
        final ResourceLocation[] textures;
        final float[][] positions;
        final float[][] normals;
        final String[][] driver;
        final int[][] bindSlot;
        final boolean[] replaced;
        final boolean[] paintable;
        /** Per clip model: per skin and slot, the clip node whose global drives it, or -1 (slot keeps the bind). */
        final Map<GltfModel, int[][]> clipNodes = new IdentityHashMap<>();
        boolean disposed;

        Part(Prepared prepared, RenderType[] types, ResourceLocation[] textures) {
            model = prepared.model;
            pose = model.newPose();
            primitives = prepared.primitives;
            this.types = types;
            this.textures = textures;
            driver = prepared.driver;
            bindSlot = prepared.bindSlot;
            replaced = prepared.replaced;
            paintable = prepared.paintable;
            positions = new float[primitives.size()][];
            normals = new float[primitives.size()][];
            for (int i = 0; i < primitives.size(); i++) {
                positions[i] = new float[primitives.get(i).positions().length];
                normals[i] = new float[primitives.get(i).normals().length];
            }
        }

        int[][] prepare(GltfModel clip) {
            int[][] nodes = clipNodes.get(clip);
            if (nodes != null) return nodes;
            nodes = new int[driver.length][];
            for (int s = 0; s < driver.length; s++) {
                nodes[s] = new int[driver[s].length];
                for (int j = 0; j < driver[s].length; j++) {
                    nodes[s][j] = driver[s][j] == null ? -1 : clip.nodeIndex(driver[s][j]);
                }
            }
            clipNodes.put(clip, nodes);
            return nodes;
        }

        void dispose() {
            if (disposed) return;
            disposed = true;
            var manager = Minecraft.getInstance().getTextureManager();
            for (ResourceLocation texture : textures) if (texture != null) manager.release(texture);
            clipNodes.clear();
        }
    }

    /**
     * A worn glove's viewmodel mesh (bare arm + glove): loaded on first wear, resident while among the
     * {@link #GLOVE_CAPACITY} most recently worn, then disposed like the arms' own parts.
     */
    private static final class GloveSet {
        final PriorityExecutor.Task<Prepared> task;
        Part part;
        boolean failed;

        GloveSet(PriorityExecutor.Task<Prepared> task) {
            this.task = task;
        }

        void dispose() {
            // Abandoned or finished-unuploaded images are freed; uploaded ones were released to their textures.
            if (!task.cancelIfQueued()) task.future().thenAccept(prepared -> { if (prepared != null) prepared.close(); });
            if (part != null) part.dispose();
            part = null;
        }
    }

    /** Glove meshes held at once (each a few MiB of texture); switching back and forth never reloads. */
    private static final int GLOVE_CAPACITY = 2;
    private static final BoundedResidency<GloveSet> GLOVES = new BoundedResidency<>(GLOVE_CAPACITY, GloveSet::dispose);
    /** Glove part and finish type drawn this frame, resolved by {@link #ready}; null for the team's default gloves. */
    private static Part worn;
    private static RenderType wornFinish;

    /** Releases the arms' textures and abandons any load (resource reload, disconnect); render thread only. */
    static void clear() {
        if (task != null) {
            PriorityExecutor.Task<List<Prepared>> abandoned = task;
            task = null;
            // Whether it finishes later or already finished unuploaded, its images are freed; uploads were released.
            if (!abandoned.cancelIfQueued()) abandoned.future().thenAccept(ViewmodelArms::closeAll);
        }
        if (parts != null) {
            parts.forEach(Part::dispose);
            parts = null;
        }
        GLOVES.clear();
        worn = null;
        wornFinish = null;
    }

    private static void closeAll(List<Prepared> prepared) {
        prepared.forEach(Prepared::close);
    }

    /** True once arms are loaded and drawable; starts the load and resolves the worn glove. Render thread. */
    static boolean ready(PriorityExecutor loader) {
        if (task == null) task = loader.submit(ViewmodelArms::load, PriorityExecutor.Priority.FOREGROUND);
        if (parts == null) {
            var future = task.future();
            if (!future.isDone()) return false;
            parts = new ArrayList<>();
            if (!future.isCompletedExceptionally()) upload(future.join());
            ViewmodelClips.forEachTrack(ViewmodelArms::prepare);
        }
        resolveGlove(loader);
        return !parts.isEmpty();
    }

    /**
     * The local player's worn glove (synced attachment): its mesh once loaded, with its finish once composited;
     * until then, and for an empty slot or missing assets, the team's default gloves.
     */
    private static void resolveGlove(PriorityExecutor loader) {
        worn = null;
        wornFinish = null;
        ItemStack stack = GloveClient.localGloveStack();
        String id = GloveView.armsGlove(stack.getItem() instanceof GloveItem glove ? glove.weaponId().getPath() : null,
                glove -> GloveAssets.glove(glove) != null);
        if (id == null) return;
        GloveSet set = GLOVES.get(id);
        if (set == null) {
            GloveAssets.Glove glove = GloveAssets.glove(id);
            set = new GloveSet(loader.submit(() -> loadGlove(glove), PriorityExecutor.Priority.FOREGROUND));
            GLOVES.put(id, set);
        }
        if (set.part == null && !set.failed && set.task.future().isDone()) {
            Prepared prepared = set.task.future().isCompletedExceptionally() ? null : set.task.future().join();
            if (prepared == null) {
                set.failed = true;
            } else {
                set.part = uploadPart(prepared, "glove/" + id);
                CsmcMod.LOGGER.info("[csmc glTF] viewmodel glove {}: {} primitive(s)", id, prepared.primitives.size());
            }
        }
        if (set.part == null) return;
        worn = set.part;
        ResourceLocation finish = SkinTextures.texture(id, stack.get(SkinGameplay.SKIN), SkinResolution.FULL);
        wornFinish = finish == null ? null : RenderType.entityCutoutNoCull(finish);
    }

    /** Builds the node maps for a newly loaded clip, outside drawing. Render thread. */
    static void prepare(ViewmodelClips.Track track) {
        if (parts == null) return;
        for (Part part : parts) part.prepare(track.model);
        if (worn != null) worn.prepare(track.model);
    }

    /**
     * Draws the arms on {@code track}'s sampled pose. {@code view} is camera space with the viewmodel settings
     * applied; {@code toView} maps glTF viewmodel space into it, and {@code reflected} says it mirrors (left hand),
     * which reverses triangle winding. A worn glove replaces the team's default-gloves mesh.
     */
    static void render(ViewmodelClips.Track track, Matrix4f view, Matrix4f toView, boolean reflected,
                       MultiBufferSource buffers, int light, int overlay) {
        if (parts == null) return;
        DRAW.set(view).mul(toView);
        NORMAL.set(DRAW).invert().transpose();
        for (int index = 0; index < parts.size(); index++) {
            draw(parts.get(index), track, worn != null, null, reflected, buffers, light, overlay);
        }
        if (worn != null) draw(worn, track, false, wornFinish, reflected, buffers, light, overlay);
    }

    private static void draw(Part part, ViewmodelClips.Track track, boolean skipReplaced, RenderType finish,
                             boolean reflected, MultiBufferSource buffers, int light, int overlay) {
        int[][] clipNodes = part.prepare(track.model);
        List<GltfModel.Skin> skins = part.model.skins;
        for (int s = 0; s < skins.size(); s++) {
            var inverseBinds = skins.get(s).inverseBindMatrices();
            int[] nodes = clipNodes[s];
            for (int j = 0; j < nodes.length; j++) {
                if (nodes[j] < 0) continue;
                JOINT.set(track.pose.world(nodes[j])).mul(inverseBinds[part.bindSlot[s][j]]);
                part.pose.setJointMatrix(s, j, JOINT);
            }
        }
        for (int p = 0; p < part.primitives.size(); p++) {
            if (skipReplaced && part.replaced[p]) continue;
            GltfModel.Primitive primitive = part.primitives.get(p);
            part.pose.skin(primitive, part.positions[p], part.normals[p]);
            VertexConsumer consumer = buffers.getBuffer(finish != null && part.paintable[p] ? finish : part.types[p]);
            float[] positions = part.positions[p];
            float[] normals = part.normals[p];
            int[] indices = primitive.indices();
            for (int i = 0; i < indices.length; i += 3) {
                int b = indices[reflected ? i + 2 : i + 1];
                int c = indices[reflected ? i + 1 : i + 2];
                vertex(consumer, primitive, positions, normals, indices[i], light, overlay);
                vertex(consumer, primitive, positions, normals, b, light, overlay);
                vertex(consumer, primitive, positions, normals, c, light, overlay);
                vertex(consumer, primitive, positions, normals, c, light, overlay);
            }
        }
    }

    private static void vertex(VertexConsumer output, GltfModel.Primitive primitive, float[] positions,
                               float[] normals, int index, int light, int overlay) {
        int xyz = index * 3;
        int uv = index * 2;
        NORMAL.transform(normals[xyz], normals[xyz + 1], normals[xyz + 2], NORMAL_VECTOR).normalize();
        float[] color = primitive.baseColorFactor();
        output.addVertex(DRAW, positions[xyz], positions[xyz + 1], positions[xyz + 2])
                .setColor(color[0], color[1], color[2], 1)
                .setUv(primitive.uvs()[uv], primitive.uvs()[uv + 1]).setOverlay(overlay).setLight(light)
                .setNormal(NORMAL_VECTOR.x, NORMAL_VECTOR.y, NORMAL_VECTOR.z);
    }

    private static void upload(List<Prepared> prepared) {
        for (Prepared part : prepared) parts.add(uploadPart(part, part.name));
        CsmcMod.LOGGER.info("[csmc glTF] viewmodel arms: {} part(s)", parts.size());
    }

    private static Part uploadPart(Prepared part, String path) {
        var textures = Minecraft.getInstance().getTextureManager();
        List<NativeImage> images = part.release();
        RenderType[] types = new RenderType[part.primitives.size()];
        ResourceLocation[] locations = new ResourceLocation[types.length];
        for (int i = 0; i < types.length; i++) {
            locations[i] = CsmcMod.id("cached_gltf/arms/" + path + "/" + i);
            textures.register(locations[i], new DynamicTexture(images.get(i)));
            types[i] = RenderType.entityCutoutNoCull(locations[i]);
        }
        return new Part(part, types, locations);
    }

    /** Runs on the glTF loader: the team's default arm meshes with their textures and retarget tables. */
    private static List<Prepared> load() {
        List<Prepared> out = new ArrayList<>();
        try {
            Path root = CachePaths.root().toAbsolutePath().normalize();
            JsonObject worn = teamMeshes(root);
            JsonObject records = object(object(manifest(root), "arms"), "parts");
            if (records == null || worn == null) return out;
            for (var entry : worn.entrySet()) {
                Prepared part = part(root, entry.getKey(), object(records, entry.getKey()), entry.getValue());
                if (part != null) out.add(part);
            }
            return out;
        } catch (Exception e) {
            closeAll(out);
            CsmcMod.LOGGER.warn("[csmc glTF] Cannot load viewmodel arms; drawing none", e);
            return List.of();
        }
    }

    private static JsonObject manifest(Path root) throws java.io.IOException {
        return JsonParser.parseString(Files.readString(root.resolve("manifest.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    /** {@code arms.default.<team>}: part name → worn mesh suffixes. */
    private static JsonObject teamMeshes(Path root) throws java.io.IOException {
        return object(object(object(manifest(root), "arms"), "default"), TEAM);
    }

    /**
     * Runs on the glTF loader: a glove's viewmodel mesh (bare arm + glove), retargeted like an arms part; its bare
     * arm is cut under the team's sleeves. Null when unusable (the default gloves stay).
     */
    private static Prepared loadGlove(GloveAssets.Glove glove) {
        try {
            Path root = CachePaths.root().toAbsolutePath().normalize();
            JsonObject team = teamMeshes(root);
            JsonObject records = object(object(manifest(root), "arms"), "parts");
            boolean sleeved = false;
            if (team != null) {
                for (var entry : team.entrySet()) {
                    JsonObject meshes = object(object(records, entry.getKey()), "meshes");
                    if (!entry.getValue().isJsonArray()) continue;
                    for (JsonElement suffix : entry.getValue().getAsJsonArray()) {
                        JsonObject mesh = object(meshes, suffix.getAsString());
                        sleeved |= mesh != null && mesh.has("role") && "sleeves".equals(mesh.get("role").getAsString());
                    }
                }
            }
            Map<String, String> anchors = new HashMap<>();
            JsonObject meshAnchors = object(glove.mesh(), "joint_anchors");
            if (meshAnchors != null) {
                for (var a : meshAnchors.entrySet()) anchors.put(a.getKey(), a.getValue().getAsString());
            }
            return build("glove/" + glove.id(), glove.file(), Set.of(glove.node()), Set.of(), sleeved, anchors, Map.of(),
                    true);
        } catch (Exception e) {
            CsmcMod.LOGGER.warn("[csmc glTF] Cannot load viewmodel glove {}; keeping the default gloves", glove.id(), e);
            return null;
        }
    }

    private static Prepared part(Path root, String name, JsonObject record, JsonElement wornMeshes) throws Exception {
        JsonObject meshes = object(record, "meshes");
        JsonElement file = record == null ? null : record.get("file");
        if (meshes == null || file == null || !file.isJsonPrimitive() || !wornMeshes.isJsonArray()) return null;
        Path path = root.resolve(file.getAsString()).normalize();
        if (!path.startsWith(root.resolve("models")) || !Files.isRegularFile(path)) return null;
        Set<String> render = new HashSet<>();
        Set<String> sleeves = new HashSet<>();
        Map<String, String> anchors = new HashMap<>();
        Map<String, String> roles = new HashMap<>();
        for (JsonElement suffix : wornMeshes.getAsJsonArray()) {
            JsonObject mesh = object(meshes, suffix.getAsString());
            if (mesh == null || !mesh.has("node")) continue;
            String node = mesh.get("node").getAsString();
            render.add(node);
            String role = mesh.has("role") ? mesh.get("role").getAsString() : null;
            if (role != null) roles.put(node, role);
            if ("sleeves".equals(role)) sleeves.add(node);
            JsonObject meshAnchors = object(mesh, "joint_anchors");
            if (meshAnchors != null) {
                for (var a : meshAnchors.entrySet()) anchors.put(a.getKey(), a.getValue().getAsString());
            }
        }
        if (render.isEmpty()) return null;
        return build(name, path, render, sleeves, !sleeves.isEmpty(), anchors, roles, false);
    }

    /**
     * Loads {@code render}'s skinned primitives from {@code path}: bare arms outside {@code sleeves} lose the
     * triangles a sleeve covers when {@code sleeved}; {@code glove} marks the non-bare-arm primitives paintable.
     */
    private static Prepared build(String name, Path path, Set<String> render, Set<String> sleeves, boolean sleeved,
                                  Map<String, String> anchors, Map<String, String> roles, boolean glove)
            throws Exception {
        GltfModel model = GltfLoader.load(path, meshNodes -> render::contains);
        List<GltfModel.Primitive> primitives = new ArrayList<>();
        List<NativeImage> images = new ArrayList<>();
        List<Boolean> replaced = new ArrayList<>();
        List<Boolean> paintable = new ArrayList<>();
        try {
            for (GltfModel.Primitive primitive : model.primitives) {
                String node = model.nodes.get(primitive.node()).name();
                if (!primitive.skinned() || !render.contains(node)) continue;
                boolean bare = bareArm(model, primitive);
                if (sleeved && !sleeves.contains(node) && bare) {
                    primitive = occluded(model, primitive);
                    if (primitive == null) continue;
                }
                primitives.add(primitive);
                replaced.add(GloveView.replacedByGlove(roles.get(node)));
                paintable.add(glove && !bare);
                images.add(image(model, primitive));
            }
        } catch (Exception e) {
            images.forEach(NativeImage::close);
            throw e;
        }
        if (primitives.isEmpty()) return null;
        int skinCount = model.skins.size();
        String[][] driver = new String[skinCount][];
        int[][] bindSlot = new int[skinCount][];
        for (int s = 0; s < skinCount; s++) {
            int[] joints = model.skins.get(s).joints();
            driver[s] = new String[joints.length];
            bindSlot[s] = new int[joints.length];
            Map<String, Integer> slots = new HashMap<>();
            for (int j = 0; j < joints.length; j++) slots.put(model.nodes.get(joints[j]).name(), j);
            for (int j = 0; j < joints.length; j++) {
                String joint = model.nodes.get(joints[j]).name();
                String anchor = anchors.get(joint);
                Integer anchorSlot = anchor == null ? null : slots.get(anchor);
                // Matched joints drive themselves; unmatched ones follow their anchor's clip global.
                driver[s][j] = anchorSlot != null ? anchor : joint;
                bindSlot[s][j] = anchorSlot != null ? anchorSlot : j;
            }
        }
        boolean[] replacedFlags = new boolean[primitives.size()];
        boolean[] paintableFlags = new boolean[primitives.size()];
        for (int i = 0; i < replacedFlags.length; i++) {
            replacedFlags[i] = replaced.get(i);
            paintableFlags[i] = paintable.get(i);
        }
        return new Prepared(name, model, List.copyOf(primitives), images, driver, bindSlot, replacedFlags, paintableFlags);
    }

    /** Bare-arm skin material ({@code bare_arm_*}), which a worn sleeve covers. */
    private static boolean bareArm(GltfModel model, GltfModel.Primitive primitive) {
        return primitive.texture() >= 0 && model.textures.get(primitive.texture()).name().startsWith("bare_arm");
    }

    /** {@code primitive} without the triangles a sleeve covers, or null when none remain. */
    private static GltfModel.Primitive occluded(GltfModel model, GltfModel.Primitive p) {
        int[] joints = model.skins.get(p.skin()).joints();
        boolean[] covered = new boolean[joints.length];
        for (int j = 0; j < joints.length; j++) {
            covered[j] = KnifeArmOcclusion.sleeveCovers(model.nodes.get(joints[j]).name());
        }
        int[] indices = KnifeArmOcclusion.uncovered(p.indices(), p.joints(), p.weights(), covered);
        if (indices.length == 0) return null;
        return new GltfModel.Primitive(p.node(), p.skin(), p.positions(), p.normals(), p.uvs(), indices, p.joints(),
                p.weights(), p.texture(), p.baseColorFactor(), p.alphaMode(), p.alphaCutoff(), p.doubleSided());
    }

    private static NativeImage image(GltfModel model, GltfModel.Primitive primitive) throws java.io.IOException {
        if (primitive.texture() >= 0) {
            GltfModel.Texture texture = model.textures.get(primitive.texture());
            if (texture.isPng()) return NativeImage.read(new ByteArrayInputStream(texture.bytes()));
        }
        NativeImage white = new NativeImage(1, 1, false);
        white.setPixelRGBA(0, 0, 0xFFFFFFFF);
        return white;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}
