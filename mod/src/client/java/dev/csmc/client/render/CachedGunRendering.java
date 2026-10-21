package dev.csmc.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.csmc.CsmcMod;
import dev.csmc.client.thirdperson.guns.TimedGunCue;
import dev.csmc.data.CacheManifest;
import dev.csmc.data.CachePaths;
import dev.csmc.data.MeshSets;
import dev.csmc.data.ModelWarmUp;
import dev.csmc.data.PriorityExecutor;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.item.GrenadeItem;
import dev.csmc.item.GunItem;
import dev.csmc.item.KnifeItem;
import dev.csmc.melee.KnifeClipChooser;
import dev.csmc.viewmodel.ReferencedGeometry;
import dev.csmc.viewmodel.EquipmentPlaybackOwner;
import dev.csmc.viewmodel.ViewmodelFraming;
import dev.csmc.viewmodel.ShotCorrelator;
import dev.csmc.viewmodel.ViewmodelCue;
import dev.csmc.item.SkinnableWeapon;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinResolution;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.client.gltf.GltfLoader;
import dev.csmc.client.gltf.GltfModel;
import dev.csmc.client.gltf.GltfPose;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import dev.csmc.skin.ShineParams;
import dev.csmc.client.viewmodel.ViewmodelSettings;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Runtime-only glTF assets. A missing/invalid asset leaves the original baked item model intact.
 *
 * <p>Each weapon loads lazily the first time it is rendered (or dev-preloaded): parsing, PNG decoding,
 * downscaling and alpha baking run on a bounded background pool; the render thread only uploads the prepared
 * base-color textures once and swaps the model in between frames. Only the chosen CS2 mesh set is decoded.
 * After joining a world every skinnable weapon model (guns, then knives) is also queued at background priority (held
 * weapon and hotbar first), so case reels and weapon switches rarely wait; all stay resident (about 4 MiB of GPU
 * texture each).
 *
 * <p>Knives ({@link KnifeItem}) take skins and the viewmodel settings like guns. In first person, once a weapon's
 * exported viewmodel clips load, they play in camera space with CS2 arms ({@link ViewmodelClips},
 * {@link ViewmodelArms}): the idle loops and each action plays its clip, driving the weapon's own nodes (blades;
 * magazines, bolts, slides, triggers). Guns keep their skins and legacy bodies; without clips or arms they render the
 * world model with its embedded clips. Knives otherwise, and in third person, use a procedural deploy flip,
 * horizontal slash arc or forward thrust ({@link KnifeMotion}).
 * <p>Utility items ({@link GrenadeItem}) share the cache/model pipeline under {@code models/<kind id>/} but are not
 * weapons: they never receive skins, legacy bodies, fire/reload/draw/inspect playback or gun display defaults.
 */
public final class CachedGunRendering implements ClientModInitializer {
    private static final String GRIP_NODE = "ag1_hand_r";
    private static final String[] LOGGED_ACTIONS = {"fire", "reload", "draw", "inspect", "slash", "stab", "backstab"};
    /**
     * Two workers bound transient native memory: a 4K RGBA decode is 64 MiB before downscaling. Models something
     * renders or requests now are foreground; the post-join warm-up of every gun is background and yields to them.
     */
    private static final PriorityExecutor LOADER = new PriorityExecutor("csmc-gltf-loader", 2);
    /** Whether this world session's background warm-up has been queued. */
    private static boolean warmedUp;
    private static final Map<String, Slot> MODELS = new HashMap<>();
    private static final Map<String, Slot> LEGACY_MODELS = new HashMap<>();
    private static final Map<ItemStack, Playback> PLAYBACK = new WeakHashMap<>();
    /** Event-started equipment playbacks by owning actor/hand; retained previous hand stacks keep their entry. */
    private static final Map<ItemStack, EquipmentPlayback> EQUIPMENT_PLAYBACK = new WeakHashMap<>();
    private static HeldPlayback heldPlayback;
    private static final Map<Integer, EntityPlayback> ENTITY_PLAYBACK = new HashMap<>();
    private static int renderingEntity = -1;
    private static long reloadUntil;
    private static final float INSPECT_SECONDS = 2.5f;
    /** Matches the local player's server shot events with synced magazine drops: one playback per shot. */
    private static final ShotCorrelator SHOTS = new ShotCorrelator();
    private static final GunTransforms TRANSFORMS = new GunTransforms();
    private static CacheManifest manifest;
    private static final Quaternionf ROTATION = new Quaternionf();
    /** Knife world models carry the blade along +Y; hand contexts treat it like a +Z barrel. */
    private static final Quaternionf KNIFE_BLADE_FORWARD = new Quaternionf().rotationX((float) Math.toRadians(90));
    private static final Matrix4f DISPLAY = new Matrix4f();
    private static final Matrix4f DRAW = new Matrix4f();
    private static final Matrix3f NORMAL = new Matrix3f();
    private static final Vector3f NORMAL_VECTOR = new Vector3f();
    private static final Vector3f INSPECT_GRIP = new Vector3f();
    /** Camera-space pose captured for the first-person hand being drawn; valid while {@link #viewmodelCaptured}. */
    private static final Matrix4f VIEWMODEL_CAMERA = new Matrix4f();
    private static boolean viewmodelCaptured;
    /** Knife clip placement: knife model space to Minecraft view space. */
    private static final Matrix4f PLACEMENT = new Matrix4f();
    /** Camera space with the viewmodel settings applied; knife and arms share it. */
    private static final Matrix4f VIEWMODEL_BASE = new Matrix4f();
    private static final Matrix4f ARMS_TO_VIEW = new Matrix4f();
    private static final Matrix4f OVERRIDE_ADJUSTMENT = new Matrix4f();
    /** View-space point (right hand) the viewmodel size setting scales about: roughly CS2's resting knife hand. */
    private static final Vector3f KNIFE_ANCHOR = new Vector3f(.14f, -.16f, -.42f);
    private Item previousItem;
    private int previousSlot = -1;
    private int previousMagazine;

    @Override
    public void onInitializeClient() {
        GltfBlendLayer.register();
        ShineLayer.register();
        SkinCatalog.reload();
        for (Item item : BuiltInRegistries.ITEM) {
            if (cachedId(item) != null) {
                BuiltinItemRendererRegistry.INSTANCE.register(item,
                        (BuiltinItemRendererRegistry.DynamicItemRenderer) CachedGunRendering::render);
            }
        }
        ModelLoadingPlugin.register(context -> context.modifyModelAfterBake().register(ModelModifier.WRAP_PHASE, (model, bake) -> {
            if (model == null || bake.topLevelId() == null || !"inventory".equals(bake.topLevelId().variant())) return model;
            Item item = BuiltInRegistries.ITEM.get(bake.topLevelId().id());
            String id = cachedId(item);
            return id != null ? new CachedModel(model, id) : model;
        }));
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return CsmcMod.id("cached_gun_models"); }
            @Override public void onResourceManagerReload(ResourceManager manager) {
                MODELS.values().forEach(Slot::close);
                MODELS.clear();
                LEGACY_MODELS.values().forEach(Slot::close);
                LEGACY_MODELS.clear();
                SkinTextures.clear();
                ShineLayer.clear();
                SkinCatalog.reload();
                ViewmodelClips.clear();
                ViewmodelArms.clear();
                GloveAssets.clear();
                PLAYBACK.clear();
                EQUIPMENT_PLAYBACK.clear();
                heldPlayback = null;
                ENTITY_PLAYBACK.clear();
                reloadUntil = 0;
                manifest = null;
                TRANSFORMS.reload();
                warmedUp = false;
            }
        });
        // Viewmodel playback, the arms' and glove meshes and every finish texture belong to the session.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(() -> {
                    ViewmodelClips.clear();
                    ViewmodelArms.clear();
                    SkinTextures.clear();
                    ShineLayer.clear();
                    // Closing a glove slot also cancels its queued load and discards a running one unuploaded.
                    MODELS.values().removeIf(slot -> {
                        if (!CsmcMod.GLOVES.containsKey(slot.id)) return false;
                        slot.close();
                        return true;
                    });
                    SHOTS.reset();
                }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Swap finished loads in between frames, so one frame never mixes vanilla and glTF transforms.
            for (Slot slot : MODELS.values()) slot.promote();
            for (Slot slot : LEGACY_MODELS.values()) slot.promote();
            SkinTextures.tick();
            if (client.player == null) {
                heldPlayback = null;
                warmedUp = false; // warm up again on the next join; loaded models stay resident
                ENTITY_PLAYBACK.clear();
                PLAYBACK.clear();
                EQUIPMENT_PLAYBACK.clear();
                reloadUntil = 0;
                previousItem = null;
                previousSlot = -1;
                SHOTS.reset();
                return;
            }
            if (!warmedUp) {
                warmedUp = true;
                warmUp(client);
            }
            ItemStack stack = client.player.getMainHandItem();
            int magazine = stack.getOrDefault(CsmcMod.MAGAZINE, 0);
            int slot = client.player.getInventory().selected;
            if (stack.getItem() != previousItem || slot != previousSlot) {
                cancelInspect();
                reloadUntil = 0;
            }
            if (stack.getItem() instanceof SkinnableWeapon || equipmentId(stack.getItem()) != null) {
                boolean rebound = stack.getItem() != previousItem || slot != previousSlot;
                if (rebound) {
                    playAnimation(stack, "draw");
                } else if (stack.getItem() instanceof GunItem && magazine < previousMagazine) {
                    // Fallback when no server shot event covered this drop; the correlator absorbs the late event.
                    if (SHOTS.magazineDrop(stack.getItem(), slot, magazine)) {
                        playAnimation(stack, new Playback("fire", System.nanoTime(), 0, magazine, 0));
                    }
                } else if (stack.getItem() instanceof GunItem && magazine > previousMagazine) {
                    SHOTS.magazineRaised(stack.getItem(), slot);
                }
            }
            previousItem = stack.getItem();
            previousSlot = slot;
            previousMagazine = magazine;
        });
    }

    /** Cosmetic playback only; firing still follows server-confirmed magazine changes. */
    public static void playAnimation(ItemStack stack, String action) {
        playAnimation(stack, new Playback(action, System.nanoTime(), "inspect".equals(action) ? INSPECT_SECONDS
                : stack.getItem() instanceof KnifeItem ? KnifeMotion.duration(action) : 0, -1, 0));
    }

    private static void playAnimation(ItemStack stack, Playback playback) {
        if (!(stack.getItem() instanceof SkinnableWeapon) && equipmentId(stack.getItem()) == null) return;
        PLAYBACK.put(stack, playback);
        var player = Minecraft.getInstance().player;
        if (player != null && stack == player.getMainHandItem()) {
            heldPlayback = new HeldPlayback(stack.getItem(), player.getInventory().selected, playback);
        }
    }

    /**
     * Rebase {@code actor}'s cosmetic clip to authoritative progress without accumulating packet drift; the playback
     * belongs to {@code actor}'s {@code hand} until {@link #cancelEquipment} for that owner.
     */
    public static void playEquipment(ItemStack stack, int actor, InteractionHand hand, String action, float duration,
                                     float progress) {
        if (equipmentId(stack.getItem()) == null) return;
        long started = System.nanoTime() - (long) (Math.max(0, Math.min(1, progress)) * duration * 1_000_000_000L);
        Playback playback = new Playback(action, started, duration, -1, 0);
        playAnimation(stack, playback);
        EQUIPMENT_PLAYBACK.put(stack, new EquipmentPlayback(new EquipmentPlaybackOwner<>(actor, hand, stack.getItem()),
                playback));
    }

    /** Stops only {@code actor}'s playbacks of {@code item}; {@code hand} null when it is no longer known. */
    public static void cancelEquipment(int actor, InteractionHand hand, Item item) {
        // Vanilla may retain the actor's previous synchronized hand stack until its equip transition finishes; the
        // owner entry still names that stack, so it stops too, while other actors' stacks of the same item keep theirs.
        var entries = EQUIPMENT_PLAYBACK.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            EquipmentPlayback owned = entry.getValue();
            if (!owned.owner.cancelledBy(actor, hand, item)) continue;
            entries.remove();
            if (PLAYBACK.get(entry.getKey()) == owned.playback) PLAYBACK.remove(entry.getKey());
            if (heldPlayback != null && heldPlayback.playback == owned.playback) heldPlayback = null;
        }
        var player = Minecraft.getInstance().player;
        if (player != null && actor == player.getId() && (hand == null || hand == InteractionHand.MAIN_HAND)
                && heldPlayback != null && heldPlayback.item == item) {
            heldPlayback = null;
        }
    }

    public static String equipmentId(Item item) {
        if (item == EquipmentRegistry.HEALTHSHOT) return "healthshot";
        if (item == EquipmentRegistry.C4) return "c4";
        if (item == EquipmentRegistry.DEFUSE_KIT) return "defuse_kit";
        return null;
    }

    /**
     * The server reports that {@code entityId}'s knife swing connected: its running slash/stab keeps its start time
     * and switches to the hit clip variant (the swing event always arrives first, in the same tick).
     */
    public static void knifeHit(int entityId) {
        var player = Minecraft.getInstance().player;
        if (player != null && entityId == player.getId()) {
            if (heldPlayback == null) return;
            Playback hit = hit(heldPlayback.playback);
            if (hit == heldPlayback.playback) return;
            PLAYBACK.replaceAll((stack, playback) -> playback == heldPlayback.playback ? hit : playback);
            heldPlayback = new HeldPlayback(heldPlayback.item, heldPlayback.slot, hit);
        } else {
            EntityPlayback remote = ENTITY_PLAYBACK.get(entityId);
            if (remote != null) ENTITY_PLAYBACK.put(entityId, new EntityPlayback(remote.weapon, hit(remote.playback)));
        }
    }

    private static Playback hit(Playback swing) {
        String action = KnifeClipChooser.hitAction(swing.action);
        return action.equals(swing.action) ? swing
                : new Playback(action, swing.started, swing.duration, swing.magazine, swing.rounds);
    }

    /** First-person hand pose, including Minecraft's inverse camera rotation and bob/hurt/hand sway. */
    public static void beginViewmodel(Matrix4f camera) {
        VIEWMODEL_CAMERA.set(camera);
        viewmodelCaptured = true;
    }

    public static void endViewmodel() {
        viewmodelCaptured = false;
    }

    /** ItemInHandLayer supplies the owner; remote equipment packets may replace the ItemStack each tick. */
    public static void renderingEntity(int entityId) {
        renderingEntity = entityId;
    }

    public static boolean canInspect() {
        return System.nanoTime() >= reloadUntil;
    }

    /** Cancel immediately on local intent, rather than waiting for the server round trip. */
    public static void cancelInspect() {
        if (heldPlayback != null && "inspect".equals(heldPlayback.playback.action)) heldPlayback = null;
        PLAYBACK.values().removeIf(playback -> "inspect".equals(playback.action));
    }

    /** Server-confirmed event shared by local and remote held weapons; {@code duration} 0 means the clip's own. */
    public static void serverAnimation(int entityId, ResourceLocation weapon, String action, float duration) {
        serverAnimation(entityId, weapon, action, duration, 0, -1, 0);
    }

    /**
     * Server-confirmed event with its viewmodel data (see {@code GunSoundPayload}): the shot's {@code sequence} and
     * post-shot {@code magazine} for fire, the starting magazine and {@code rounds} to load for reload.
     */
    public static void serverAnimation(int entityId, ResourceLocation weapon, String action, float duration,
                                       int sequence, int magazine, int rounds) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        if (duration <= 0 && CsmcMod.KNIVES.containsKey(weapon.getPath())) duration = KnifeMotion.duration(action);
        Playback playback = new Playback(action, System.nanoTime(), duration, magazine, rounds);
        if (client.player != null && entityId == client.player.getId()) {
            ItemStack stack = client.player.getMainHandItem();
            if (!(stack.getItem() instanceof SkinnableWeapon held) || !held.weaponId().equals(weapon)) return;
            if ("reload".equals(action)) reloadUntil = playback.started + (long) (duration * 1_000_000_000L);
            if ("draw".equals(action)) reloadUntil = 0;
            if ("fire".equals(action) && sequence > 0
                    && !SHOTS.server(stack.getItem(), client.player.getInventory().selected, sequence, magazine)) {
                return;
            }
            playAnimation(stack, playback);
        } else {
            ENTITY_PLAYBACK.put(entityId, new EntityPlayback(weapon, playback));
        }
    }

    public static void stopServerAnimation(int entityId, String action) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && entityId == client.player.getId()) {
            if ("reload".equals(action)) reloadUntil = 0;
            if (heldPlayback != null && action.equals(heldPlayback.playback.action)) heldPlayback = null;
            PLAYBACK.values().removeIf(playback -> action.equals(playback.action));
        } else {
            EntityPlayback playback = ENTITY_PLAYBACK.get(entityId);
            if (playback != null && action.equals(playback.playback.action)) ENTITY_PLAYBACK.remove(entityId);
        }
    }

    /** Read-only cue shared with procedural third-person poses; never changes viewmodel playback. */
    public static TimedGunCue thirdPersonCue(int entityId, ResourceLocation weapon) {
        var player = Minecraft.getInstance().player;
        if (player != null && entityId == player.getId()) {
            return heldPlayback != null && heldPlayback.item instanceof SkinnableWeapon held
                    && held.weaponId().equals(weapon) && player.getMainHandItem().getItem() == heldPlayback.item
                    && player.getInventory().selected == heldPlayback.slot ? heldPlayback.playback : null;
        }
        EntityPlayback remote = ENTITY_PLAYBACK.get(entityId);
        return remote != null && remote.weapon.equals(weapon) ? remote.playback : null;
    }

    /**
     * Third-person in-hand knife node motion layered over the sampled clip or bind pose. Implementations may only
     * override {@code pose}'s joint matrices and return true when they did; {@code duration} is the playback's server
     * duration, or 0 when it has none.
     */
    @FunctionalInterface
    public interface ThirdPersonNodeMotion {
        boolean apply(String modelId, GltfModel model, GltfPose pose, String action, float seconds, float duration);
    }

    private static ThirdPersonNodeMotion thirdPersonNodeMotion;

    /** Installs the single third-person knife node motion hook (client initialization only). */
    public static void thirdPersonNodeMotion(ThirdPersonNodeMotion motion) {
        thirdPersonNodeMotion = motion;
    }
    public static void inspectEvent(dev.csmc.network.GunInspectPayload event) {
        Minecraft client = Minecraft.getInstance();
        if (!event.active()) {
            stopServerAnimation(event.entityId(), "inspect");
        } else if (client.player == null || event.entityId() != client.player.getId()
                || (client.player.getInventory().selected == event.slot() && canInspect()
                    && !client.options.keyAttack.isDown())) {
            serverAnimation(event.entityId(), event.weaponId(), "inspect", INSPECT_SECONDS);
        }
    }

    /** Starts loading a weapon model without rendering it (render thread only). */
    public static void requestLoad(String id) {
        model(id);
    }

    /**
     * Whether the cached model for {@code id} is loaded, so item rendering draws it rather than the vanilla
     * placeholder; requests the load otherwise (render thread only).
     */
    public static boolean modelReady(String id) {
        return model(id) != null;
    }

    /** Load state of a skinned weapon icon for GUI previews. */
    public enum Preview { READY, LOADING, UNAVAILABLE }

    /** GUI thumbnail variants held at once; requesting more retires the least recently used thumbnail. */
    public static final int SKIN_VARIANT_CAPACITY = SkinTextures.MAX_THUMBNAILS;

    /**
     * Requests the weapon model and the skin thumbnail a GUI icon needs, without rendering (render thread only). The
     * thumbnail may still be compositing when this reports {@code READY}; the icon then shows the default albedo.
     */
    public static Preview preview(String id, SkinData skin) {
        if (model(id) == null) {
            Slot slot = MODELS.get(id);
            return slot != null && slot.pending != null ? Preview.LOADING : Preview.UNAVAILABLE;
        }
        if (SkinTextures.legacy(id, skin)) model(id, true);
        SkinTextures.texture(id, skin, SkinResolution.THUMBNAIL);
        return Preview.READY;
    }

    /** Starts compositing a GUI thumbnail before its model has loaded (render thread only). */
    public static void prefetchThumbnail(String id, SkinData skin) {
        SkinTextures.texture(id, skin, SkinResolution.THUMBNAIL);
    }

    /** The sole skin texture hook. Null retains the weapon's default albedo until the variant is ready. */
    public static ResourceLocation baseColorTexture(String weaponId, SkinData skin, SkinResolution resolution) {
        return SkinTextures.texture(weaponId, skin, resolution);
    }

    /** Cache id of an item rendered from {@code models/<id>/}: a gun/knife weapon id or a grenade kind id, else null. */
    private static String cachedId(Item item) {
        if (item instanceof SkinnableWeapon weapon) return weapon.weaponId().getPath();
        if (item instanceof GrenadeItem grenade) return grenade.kind().id();
        if (equipmentId(item) != null) return equipmentId(item);
        return null;
    }

    private static Loaded model(String id) {
        return model(id, false);
    }

    private static Loaded model(String id, boolean legacy) {
        if (!RenderSystem.isOnRenderThread()) return null;
        Map<String, Slot> models = legacy ? LEGACY_MODELS : MODELS;
        Slot slot = models.get(id);
        if (slot == null) {
            slot = new Slot(id, legacy);
            models.put(id, slot);
            slot.start(PriorityExecutor.Priority.FOREGROUND);
        } else if (slot.task != null && slot.task.priority() == PriorityExecutor.Priority.BACKGROUND) {
            slot.task.promote(); // needed now: jump ahead of the remaining warm-up
        }
        return slot.loaded;
    }

    /** Queues every gun and knife model not yet requested at background priority: held weapon, hotbar, then the rest. */
    private static void warmUp(Minecraft client) {
        var inventory = client.player.getInventory();
        List<String> hotbar = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            Item item = inventory.getItem(i).getItem();
            hotbar.add(item instanceof SkinnableWeapon ? cachedId(item) : null);
        }
        Item heldItem = client.player.getMainHandItem().getItem();
        String held = heldItem instanceof SkinnableWeapon ? cachedId(heldItem) : null;
        for (String id : ModelWarmUp.order(held, hotbar, CsmcMod.SKINNABLE.keySet())) {
            if (MODELS.containsKey(id)) continue;
            Slot slot = new Slot(id, false);
            MODELS.put(id, slot);
            slot.start(PriorityExecutor.Priority.BACKGROUND);
        }
    }

    private static CacheManifest manifest() {
        if (manifest == null) {
            try {
                manifest = CacheManifest.read(CachePaths.root());
            } catch (IOException | RuntimeException e) {
                CsmcMod.LOGGER.warn("[csmc glTF] Cannot read cache manifest; clip mapping falls back to clip names", e);
                manifest = CacheManifest.EMPTY;
            }
        }
        return manifest;
    }

    /** Manifest model path when it stays inside the cache, else the conventional {@code models/<id>/<id>.gltf}. */
    private static Path modelFile(String id, CacheManifest.Weapon entry) {
        Path root = CachePaths.root().toAbsolutePath().normalize();
        if (entry != null && entry.modelFile() != null) {
            Path file = root.resolve(entry.modelFile()).normalize();
            if (file.startsWith(root.resolve("models"))) return file;
        }
        return root.resolve("models").resolve(id).resolve(id + ".gltf");
    }

    private static void render(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                               MultiBufferSource buffers, int light, int overlay) {
        String id = cachedId(stack.getItem());
        if (id == null) return;
        SkinnableWeapon weapon = stack.getItem() instanceof SkinnableWeapon w ? w : null;
        boolean utility = weapon == null;
        boolean knife = stack.getItem() instanceof KnifeItem;
        boolean equipment = stack.getItem() == EquipmentRegistry.HEALTHSHOT || stack.getItem() == EquipmentRegistry.C4;
        // Held gloves are items, not weapons: no viewmodel clips or arms; they render in the hand like a model.
        boolean glove = stack.getItem() instanceof dev.csmc.item.GloveItem;
        SkinData skin = utility ? null : stack.get(SkinGameplay.SKIN);
        boolean legacy = !utility && SkinTextures.legacy(id, skin);
        Loaded loaded = model(id, legacy);
        if (loaded == null && legacy) loaded = model(id);
        // Knives export only body_legacy, which takes every finish; guns must match the finish's body.
        ResourceLocation skinTexture = !utility && loaded != null && (knife || loaded.legacy == legacy)
                ? baseColorTexture(id, skin, SkinResolution.of(context)) : null;
        // Selection and rendering happen on the same render thread; loads are promoted only between frames.
        if (loaded == null) throw new IllegalStateException("Selected glTF item model is unavailable: " + id);
        if (!loaded.renderLogged) {
            CsmcMod.LOGGER.info("[csmc glTF] Renderer invoked for {} in {}", id, context);
            loaded.renderLogged = true;
        }
        GltfModel.Clip clip = null;
        float seconds = 0;
        // Grenades and the passive defuse kit keep their bind pose; active equipment has its own action route.
        Playback playback = utility && !equipment ? null : PLAYBACK.get(stack);
        // Vanilla retains the previous hand stack while a synchronized magazine replacement lowers it.
        // Resolve local main-hand playback by held slot/item too, so short fire clips are visible immediately.
        var player = Minecraft.getInstance().player;
        if ((!utility || equipment) && context.firstPerson() && player != null && heldPlayback != null
                && heldPlayback.item == stack.getItem() && player.getMainHandItem().getItem() == heldPlayback.item
                && player.getInventory().selected == heldPlayback.slot
                && (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) == (player.getMainArm() == HumanoidArm.RIGHT)) {
            playback = heldPlayback.playback;
        }
        if (!utility && !context.firstPerson() && GunTransforms.inHand(context) && renderingEntity >= 0) {
            if (player != null && renderingEntity == player.getId()) {
                playback = heldPlayback == null ? null : heldPlayback.playback;
            } else {
                EntityPlayback remote = ENTITY_PLAYBACK.get(renderingEntity);
                playback = remote != null && remote.weapon.equals(weapon.weaponId()) ? remote.playback : null;
            }
        }
        if (playback != null && context != ItemDisplayContext.GUI && context != ItemDisplayContext.GROUND && context != ItemDisplayContext.FIXED) {
            clip = loaded.clip(playback.action);
            seconds = (System.nanoTime() - playback.started) / 1_000_000_000f;
        }
        boolean left = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        // First-person weapons play their exported viewmodel clips in camera space once loaded (see ViewmodelClips).
        // Only the main hand owns the two-arm viewmodel; an off-hand weapon keeps the hand placement. Knives use it
        // only for actions without a world-model clip; guns use it for every action once their clips load.
        boolean mainHand = player != null
                && (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) == (player.getMainArm() == HumanoidArm.RIGHT);
        // Guns without loaded arms keep the world-model rendering rather than float in camera space.
        ViewmodelClips.Weapon viewmodelClips = (!utility || equipment) && !glove && context.firstPerson() && mainHand && viewmodelCaptured
                && buffers instanceof MultiBufferSource.BufferSource
                && (knife ? clip == null : ViewmodelArms.ready(LOADER)) ? ViewmodelClips.weapon(LOADER, id) : null;
        String clipAction = playback == null ? null : playback.action;
        // Without a viewmodel clip for the action, its own fallback runs for this long: knives their procedural
        // motion, inspect the procedural inspect, guns the server's action duration or the world model's clip.
        float fallbackSeconds = clipAction == null ? 0
                : "inspect".equals(clipAction) ? INSPECT_SECONDS : knife ? KnifeMotion.duration(clipAction)
                : playback.duration > 0 ? playback.duration : clip != null ? clip.duration() : 0;
        // The cue carries the server's post-shot/starting magazine, so last-round and empty-reload clips never
        // depend on when the held stack's magazine syncs; the idle pose follows the held magazine.
        ViewmodelClips.Track knifeTrack = viewmodelClips == null ? null : ViewmodelClips.pose(viewmodelClips,
                playback, seconds, fallbackSeconds, knife ? -1 : stack.getOrDefault(CsmcMod.MAGAZINE, -1),
                loaded.pose, left, PLACEMENT);
        // A gun drawn by its viewmodel clip ignores the world model's clip for this frame.
        if (knifeTrack != null && !knife) clip = null;
        // Exported knife clips keep their authored length; the procedural fallback uses the playback's own.
        float duration = knife && clip != null && clip.duration() > 0 ? clip.duration()
                : playback != null && playback.duration > 0 ? playback.duration : clip == null ? 0 : clip.duration();
        boolean inspecting = playback != null && "inspect".equals(playback.action)
                && GunTransforms.inHand(context) && seconds < INSPECT_SECONDS;
        boolean proceduralInspect = inspecting && knifeTrack == null && (clip == null || clip.duration() <= 0
                || "inventory_inspect".equalsIgnoreCase(clip.name()));
        // Knife actions without any exported clip fall back to a hand-space swing.
        String knifeAction = playback == null ? null : KnifeClipChooser.baseAction(playback.action);
        boolean knifeMotion = knife && playback != null && clip == null && knifeTrack == null
                && GunTransforms.inHand(context) && KnifeMotion.handles(knifeAction) && seconds < duration;
        boolean animated = knifeTrack != null || clip != null && seconds <= duration;
        if (knifeTrack != null) {
            loaded.poseIsBind = false;
        } else if (animated) {
            loaded.pose.setClip(clip, duration > 0 ? seconds * clip.duration() / duration : 0);
            loaded.poseIsBind = false;
        } else if (!loaded.poseIsBind) {
            loaded.pose.setBind();
            loaded.poseIsBind = true;
        }
        // Third-person held knives articulate their parts over the clip or bind pose just sampled. Overridden joints
        // clear poseIsBind, so the next frame (first person included) resamples or returns through setBind.
        boolean nodeMotion = knife && playback != null && !context.firstPerson() && GunTransforms.inHand(context)
                && thirdPersonNodeMotion != null && thirdPersonNodeMotion.apply(id, loaded.model, loaded.pose,
                knifeAction, seconds, playback.duration);
        if (nodeMotion) loaded.poseIsBind = false;
        MultiBufferSource.BufferSource clipBuffers = knifeTrack == null ? null : (MultiBufferSource.BufferSource) buffers;
        Matrix4f projection = clipBuffers == null ? null : RenderSystem.getProjectionMatrix();
        float handM00 = projection == null ? 0 : projection.m00();
        float handM11 = projection == null ? 0 : projection.m11();
        if (clipBuffers != null) {
            // Buffered vertices use the projection at FLUSH time, not submission time. Isolate this pass from the
            // vanilla/off-hand items before installing the dedicated CS2 FOV. Updating the live render-thread matrix
            // avoids setProjectionMatrix's mandatory Matrix4f copy; only these two entries change, with no allocations.
            clipBuffers.endBatch();
            ViewmodelFraming.applyProjection(projection);
        }
        poses.pushPose();
        try {
            if (knifeTrack != null) {
                // Camera-space clip placement with CS2's default offsets, then the user's viewmodel offset, size
                // and per-weapon adjustment. Projection belongs to the pass, never the model transform: applying an
                // X/Y scale here would distort lighting and depend on the player's heading.
                var settings = dev.csmc.client.viewmodel.ViewmodelSettings.get();
                float side = left ? -1 : 1;
                VIEWMODEL_BASE.set(VIEWMODEL_CAMERA)
                        .translate(ViewmodelFraming.OFFSET_RIGHT * side, ViewmodelFraming.OFFSET_UP,
                                -ViewmodelFraming.OFFSET_FORWARD)
                        .translate(settings.offsetX + KNIFE_ANCHOR.x * side, settings.offsetY + KNIFE_ANCHOR.y,
                                settings.offsetZ + KNIFE_ANCHOR.z)
                        .scale(settings.scale)
                        .mul(TRANSFORMS.firstPersonAdjustment(id, left, OVERRIDE_ADJUSTMENT))
                        .translate(-KNIFE_ANCHOR.x * side, -KNIFE_ANCHOR.y, -KNIFE_ANCHOR.z);
                DISPLAY.set(VIEWMODEL_BASE).mul(PLACEMENT);
                if (ViewmodelArms.ready(LOADER)) {
                    ViewmodelArms.render(knifeTrack, VIEWMODEL_BASE, ViewmodelFraming.clipToView(left, ARMS_TO_VIEW), left,
                            buffers, light, overlay);
                }
            } else {
                // ItemRenderer translates by -0.5 before dispatch. Render normalized glTF about the slot/hand center.
                poses.translate(.5f, .5f, .5f);
                if (stack.getItem() instanceof GrenadeItem && context.firstPerson()) dev.csmc.client.grenade.GrenadeThrowClient.firstPersonPose(poses);
                boolean viewmodel = (!utility || equipment) && context.firstPerson();
                if (viewmodel) dev.csmc.client.viewmodel.ViewmodelSettings.applyTranslation(poses);
                TRANSFORMS.transform(id, context, GunTransforms.kind(stack.getItem())).apply(poses, left, ROTATION);
                if (knifeMotion) KnifeMotion.apply(poses, knifeAction, seconds / duration, context.firstPerson(), left);
                if (knife && GunTransforms.inHand(context)) poses.mulPose(KNIFE_BLADE_FORWARD);
                if (viewmodel) dev.csmc.client.viewmodel.ViewmodelSettings.applyScale(poses);
                if (proceduralInspect) inspectMotion(poses, seconds, context.firstPerson(), left);
                DISPLAY.set(poses.last().pose()).mul(GunTransforms.inHand(context) ? loaded.hand : loaded.fit);
            }
            if (proceduralInspect && animated && loaded.grip >= 0) {
                // Inventory poses move the whole weapon to the inventory origin. Keep the animated grip
                // anchored in the hand, so entering/exiting inspect cannot teleport the gun.
                loaded.pose.world(loaded.grip).getTranslation(INSPECT_GRIP);
                DISPLAY.translate(loaded.restGrip.x - INSPECT_GRIP.x, loaded.restGrip.y - INSPECT_GRIP.y,
                        loaded.restGrip.z - INSPECT_GRIP.z);
            }
            // Shine needs the finish's shine.json (or _default), the body's shine mask and the setting; older caches
            // stay matte, as does everything while an Iris shader pack is active.
            ShineParams shine = !utility && !glove && ViewmodelSettings.get().shine && ShineLayer.available()
                    && (skinTexture != null || skin == null || skin.paintKit() == 0)
                    ? ShineLayer.finish(id, skinTexture == null ? 0 : skin.paintKit()) : null;
            ResourceLocation shineMask = shine != null ? SkinTextures.shineMask(id, loaded.meshSet) : null;
            int wearStep = skinTexture == null ? -1 : Math.round(skin.wear() * 32);
            drawMeshes(loaded, buffers, skinTexture, shineMask != null ? shine : null, shineMask, wearStep, light,
                    overlay, animated || nodeMotion, knifeTrack != null && left);
        } finally {
            try {
                if (clipBuffers != null) clipBuffers.endBatch();
            } finally {
                if (projection != null) projection.m00(handM00).m11(handM11);
                poses.popPose();
            }
        }
    }

    /** Cached world model in its authored planted pose; no item-renderer hand/slot transforms. */
    public static void renderPlantedC4(PoseStack poses, MultiBufferSource buffers, int light) {
        Loaded loaded = model("c4");
        if (loaded == null) return;
        GltfModel.Clip planted = loaded.findClip("planted");
        if (planted != null) loaded.pose.setClip(planted, 0);
        else loaded.pose.setBind();
        loaded.poseIsBind = planted == null;
        DISPLAY.set(poses.last().pose()).translate(0, .12f, 0).scale(.48f).mul(loaded.fit);
        drawMeshes(loaded, buffers, null, null, null, -1, light,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, true, false);
    }

    private static void drawMeshes(Loaded loaded, MultiBufferSource buffers, ResourceLocation skinTexture,
                                   ShineParams shine, ResourceLocation shineMask, int wearStep, int light, int overlay,
                                   boolean animated, boolean reflected) {
        for (Mesh mesh : loaded.meshes) {
            GltfModel.Primitive primitive = mesh.primitive;
            float[] positions = primitive.positions();
            float[] normals = primitive.normals();
            if (primitive.skinned()) {
                if (animated || !mesh.holdsBind) {
                    loaded.pose.skin(primitive, mesh.positions, mesh.normals);
                    mesh.holdsBind = !animated;
                }
                positions = mesh.positions;
                normals = mesh.normals;
            }
            DRAW.set(DISPLAY).mul(loaded.pose.drawMatrix(primitive));
            NORMAL.set(DRAW).invert().transpose();
            VertexConsumer consumer = buffers.getBuffer(mesh.renderType(skinTexture, shine, shineMask, wearStep));
            int[] indices = primitive.indices();
            for (int triangle = 0; triangle < indices.length; triangle += 3) {
                int b = indices[reflected ? triangle + 2 : triangle + 1];
                int c = indices[reflected ? triangle + 1 : triangle + 2];
                vertex(consumer, primitive, positions, normals, indices[triangle], light, overlay);
                vertex(consumer, primitive, positions, normals, b, light, overlay);
                vertex(consumer, primitive, positions, normals, c, light, overlay);
                vertex(consumer, primitive, positions, normals, c, light, overlay);
            }
        }
    }
    /**
     * World-model inventory_inspect is an inventory pose, not a first-person wrist performance.
     * Keep its sampled node animation, and layer an authored grip-pivoted presentation over 2.5 seconds.
     */
    private static void inspectMotion(PoseStack poses, float seconds, boolean firstPerson, boolean left) {
        float progress = Math.max(0, Math.min(1, seconds / INSPECT_SECONDS));
        // Smooth lift/turn/return, with zero velocity at both ends.
        float envelope = (float) Math.sin(Math.PI * progress);
        envelope *= envelope;
        float side = left ? -1 : 1;
        float strength = firstPerson ? 1 : .45f;
        poses.translate(-.13f * side * envelope * strength, .10f * envelope * strength,
                -.03f * envelope * strength);
        poses.mulPose(ROTATION.rotationXYZ((float) Math.toRadians(-18 * envelope * strength),
                (float) Math.toRadians(62 * side * envelope * strength),
                (float) Math.toRadians(-35 * side * envelope * strength)));
    }


    private static void vertex(VertexConsumer output, GltfModel.Primitive primitive, float[] positions,
                               float[] normals, int index, int light, int overlay) {
        int xyz = index * 3;
        int uv = index * 2;
        NORMAL.transform(normals[xyz], normals[xyz + 1], normals[xyz + 2], NORMAL_VECTOR).normalize();
        float[] color = primitive.baseColorFactor();
        float alpha = primitive.alphaMode() == GltfModel.AlphaMode.BLEND ? color[3] : 1;
        output.addVertex(DRAW, positions[xyz], positions[xyz + 1], positions[xyz + 2])
                .setColor(color[0], color[1], color[2], alpha)
                .setUv(primitive.uvs()[uv], primitive.uvs()[uv + 1]).setOverlay(overlay).setLight(light)
                .setNormal(NORMAL_VECTOR.x, NORMAL_VECTOR.y, NORMAL_VECTOR.z);
    }

    /** One weapon action; also the viewmodel cue (see {@link ViewmodelCue}): magazine -1 / rounds 0 when unknown. */
    private record Playback(String action, long started, float duration, int magazine, int rounds)
            implements TimedGunCue { }

    private record EntityPlayback(ResourceLocation weapon, Playback playback) { }

    private record HeldPlayback(Item item, int slot, Playback playback) { }

    private record EquipmentPlayback(EquipmentPlaybackOwner<InteractionHand, Item> owner, Playback playback) { }

    /** Decoded, downscaled RGBA image awaiting upload; owned by whoever holds the {@link Prepared}. */
    private record PreparedTexture(NativeImage image, int sourceWidth, int sourceHeight) { }

    /** Background-thread output: geometry with encoded image bytes dropped, plus decoded textures. */
    private record Prepared(Path file, GltfModel model, String meshSet, List<String> meshGroups, List<String> preference,
                            List<PreparedTexture> textures, int[] primitiveTexture, long parseNanos, long decodeNanos) {
        void close() {
            textures.forEach(texture -> texture.image.close());
        }
    }

    /** Per-weapon load state; touched only on the render thread. */
    private static final class Slot {
        final String id;
        final boolean legacy;
        CompletableFuture<Prepared> pending;
        /** Queue handle while loading, so a background warm-up load can be promoted when something needs it now. */
        PriorityExecutor.Task<Prepared> task;
        long requested;
        Loaded loaded;

        Slot(String id, boolean legacy) {
            this.id = id;
            this.legacy = legacy;
        }

        void start(PriorityExecutor.Priority priority) {
            int maxEdge = TRANSFORMS.textureMaxEdge(id);
            if (CsmcMod.GLOVES.containsKey(id)) {
                // Gloves: only the exported viewmodel mesh (the glTF may also hold a world model).
                GloveAssets.Glove glove = GloveAssets.glove(id);
                if (glove == null) return;
                requested = System.nanoTime();
                task = LOADER.submit(() -> prepare(glove.file(), List.of(), maxEdge, glove.node()), priority);
                pending = task.future();
                return;
            }
            CacheManifest.Weapon entry = manifest().weapon(id);
            Path file = modelFile(id, entry);
            if (!Files.isRegularFile(file)) return;
            List<String> preference = legacy ? List.of("body_legacy") : TRANSFORMS.meshSets(id);
            requested = System.nanoTime();
            task = LOADER.submit(() -> prepare(file, preference, maxEdge, null), priority);
            pending = task.future();
        }

        void promote() {
            if (pending == null || !pending.isDone()) return;
            CompletableFuture<Prepared> done = pending;
            pending = null;
            task = null;
            Prepared prepared;
            try {
                prepared = done.join();
            } catch (RuntimeException e) {
                CsmcMod.LOGGER.warn("[csmc glTF] Cannot load {}; retaining vanilla item model", id, e.getCause() != null ? e.getCause() : e);
                return;
            }
            try {
                loaded = new Loaded(id, legacy, prepared, manifest().weapon(id));
            } catch (RuntimeException e) {
                prepared.close();
                CsmcMod.LOGGER.warn("[csmc glTF] Cannot upload {}; retaining vanilla item model", id, e);
                return;
            }
            log(prepared);
        }

        private void log(Prepared prepared) {
            GltfModel model = prepared.model;
            int vertices = 0;
            int triangles = 0;
            for (GltfModel.Primitive primitive : model.primitives) {
                vertices += primitive.vertexCount();
                triangles += primitive.indices().length / 3;
            }
            List<String> textures = new ArrayList<>();
            for (PreparedTexture texture : prepared.textures) {
                textures.add(loaded.textureSizes.get(textures.size()) + " (source " + texture.sourceWidth + "x" + texture.sourceHeight + ")");
            }
            Map<String, String> clips = new LinkedHashMap<>();
            for (String action : LOGGED_ACTIONS) {
                GltfModel.Clip clip = loaded.clip(action);
                clips.put(action, clip == null ? "none" : clip.name() + " [" + loaded.clipSource.get(action) + "]");
            }
            CsmcMod.LOGGER.info("[csmc glTF] Loaded {} from {}: mesh set {} of {} (preference {}); {} primitives, {} vertices, "
                            + "{} triangles; clips {} of {}; textures {}; GPU {} KiB uploaded; hand pivot {} at {} m/unit; "
                            + "parse {} ms, decode {} ms, upload {} ms, {} ms since request",
                    id, prepared.file, prepared.meshSet, prepared.meshGroups, prepared.preference, model.primitives.size(),
                    vertices, triangles, clips, model.clips.keySet(), textures, loaded.gpuBytes / 1024,
                    loaded.pivot, loaded.metersPerUnit, prepared.parseNanos / 1_000_000, prepared.decodeNanos / 1_000_000,
                    loaded.uploadNanos / 1_000_000, (System.nanoTime() - requested) / 1_000_000);
            for (String warning : model.warnings) CsmcMod.LOGGER.warn("[csmc glTF] {}: {}", id, warning);
        }

        void close() {
            // A load still queued (e.g. warm-up) never starts; a running one is discarded once it lands, unuploaded.
            if (task != null) task.cancelIfQueued();
            if (pending != null) pending.thenAccept(Prepared::close);
            pending = null;
            task = null;
            if (loaded != null) loaded.close();
            loaded = null;
        }
    }

    /** Runs on {@link #LOADER}: everything except GPU upload. {@code onlyNode} limits loading to one mesh node. */
    private static Prepared prepare(Path file, List<String> preference, int maxEdge, String onlyNode) {
        long started = System.nanoTime();
        String[] chosen = new String[1];
        List<String> groups = new ArrayList<>();
        GltfModel source;
        try {
            source = GltfLoader.load(file, names -> {
                if (onlyNode != null) return onlyNode::equals;
                LinkedHashSet<String> present = new LinkedHashSet<>();
                for (String name : names) {
                    String group = MeshSets.group(name);
                    if (group != null) present.add(group);
                }
                groups.addAll(present);
                String set = MeshSets.choose(names, preference);
                chosen[0] = set;
                return name -> MeshSets.selected(name, set);
            });
        } catch (IOException e) {
            throw new java.util.concurrent.CompletionException(e);
        }
        long parsed = System.nanoTime();
        Map<String, Integer> variants = new HashMap<>();
        List<PreparedTexture> textures = new ArrayList<>();
        int[] primitiveTexture = new int[source.primitives.size()];
        try {
            for (int i = 0; i < primitiveTexture.length; i++) {
                GltfModel.Primitive primitive = source.primitives.get(i);
                String variant = primitive.texture() + ":" + primitive.alphaMode() + ":" + primitive.alphaCutoff() + ":" + primitive.baseColorFactor()[3];
                Integer index = variants.get(variant);
                if (index == null) {
                    index = textures.size();
                    textures.add(decode(source, primitive, maxEdge));
                    variants.put(variant, index);
                }
                primitiveTexture[i] = index;
            }
        } catch (IOException | RuntimeException e) {
            textures.forEach(texture -> texture.image.close());
            throw new java.util.concurrent.CompletionException(e);
        }
        return new Prepared(file, source.withoutTextureBytes(), chosen[0] == null ? "(ungrouped)" : chosen[0], List.copyOf(groups),
                preference, List.copyOf(textures), primitiveTexture, parsed - started, System.nanoTime() - parsed);
    }

    private static PreparedTexture decode(GltfModel model, GltfModel.Primitive primitive, int maxEdge) throws IOException {
        NativeImage image;
        if (primitive.texture() < 0) {
            image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, -1);
        } else {
            image = NativeImage.read(NativeImage.Format.RGBA, new ByteArrayInputStream(model.textures.get(primitive.texture()).bytes()));
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int edge = Math.max(width, height);
        if (edge > maxEdge) {
            NativeImage scaled = new NativeImage(Math.max(1, Math.round(width * (float) maxEdge / edge)),
                    Math.max(1, Math.round(height * (float) maxEdge / edge)), false);
            try {
                image.resizeSubRectTo(0, 0, width, height, scaled);
            } catch (RuntimeException e) {
                scaled.close();
                throw e;
            } finally {
                image.close();
            }
            image = scaled;
        }
        // Vanilla cutout shaders use their own cutoff; bake glTF's exact MASK cutoff into alpha instead.
        if (primitive.alphaMode() != GltfModel.AlphaMode.BLEND) {
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixelRGBA(x, y);
                int alpha = primitive.alphaMode() == GltfModel.AlphaMode.OPAQUE
                        || ((pixel >>> 24) / 255f) * primitive.baseColorFactor()[3] >= primitive.alphaCutoff() ? 255 : 0;
                image.setPixelRGBA(x, y, (pixel & 0x00ffffff) | (alpha << 24));
            }
        }
        return new PreparedTexture(image, width, height);
    }

    /** GPU-only texture: uploads once and frees the CPU copy (vanilla DynamicTexture keeps it resident). */
    private static final class UploadedTexture extends AbstractTexture {
        UploadedTexture(NativeImage image) {
            TextureUtil.prepareImage(getId(), image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, false, false, true);
        }

        @Override
        public void load(ResourceManager manager) { }
    }

    private static final class Mesh {
        final GltfModel.Primitive primitive;
        final float[] positions;
        final float[] normals;
        final RenderType renderType;
        final boolean paintable;
        final ResourceLocation texture;
        ResourceLocation selectedSkin;
        ShineParams selectedShine;
        ResourceLocation selectedMask;
        int selectedWear;
        RenderType selectedSkinType;
        boolean holdsBind;

        Mesh(GltfModel.Primitive primitive, ResourceLocation texture, boolean paintable) {
            this.primitive = primitive;
            this.paintable = paintable;
            this.texture = texture;
            positions = primitive.skinned() ? new float[primitive.positions().length] : null;
            normals = primitive.skinned() ? new float[primitive.normals().length] : null;
            renderType = primitive.alphaMode() == GltfModel.AlphaMode.BLEND
                    ? GltfBlendLayer.get(texture, primitive.doubleSided())
                    : (primitive.doubleSided() ? RenderType.entityCutoutNoCull(texture) : RenderType.entityCutout(texture));
        }

        RenderType renderType(ResourceLocation skin, ShineParams shine, ResourceLocation mask, int wearStep) {
            boolean blend = primitive.alphaMode() == GltfModel.AlphaMode.BLEND;
            if (!paintable || blend) shine = null;
            if (!paintable || (skin == null && shine == null)) return renderType;
            ResourceLocation base = skin == null ? texture : skin;
            if (!base.equals(selectedSkin) || !java.util.Objects.equals(shine, selectedShine)
                    || !java.util.Objects.equals(mask, selectedMask) || wearStep != selectedWear) {
                selectedSkin = base;
                selectedShine = shine;
                selectedMask = mask;
                selectedWear = wearStep;
                selectedSkinType = shine != null ? ShineLayer.get(base, primitive.doubleSided(), shine, mask, wearStep)
                        : blend ? GltfBlendLayer.get(base, primitive.doubleSided())
                        : (primitive.doubleSided() ? RenderType.entityCutoutNoCull(base) : RenderType.entityCutout(base));
            }
            return selectedSkinType;
        }
    }

    private static final class Loaded {
        final GltfModel model;
        final String id;
        final boolean legacy;
        /** Drawn mesh set ({@code body_legacy}, {@code body_hd}, ...); selects the body's shine mask. */
        final String meshSet;
        final CacheManifest.Weapon entry;
        final GltfPose pose;
        /** Fits the largest extent to one block about the bounds center (GUI, ground, frames). */
        final Matrix4f fit;
        /** Real size in metres, pivoted on the grip node (hands). */
        final Matrix4f hand;
        final int grip;
        final Vector3f restGrip;
        final String pivot;
        final float metersPerUnit;
        final List<ResourceLocation> textures = new ArrayList<>();
        final List<String> textureSizes = new ArrayList<>();
        final Mesh[] meshes;
        final Map<String, Optional<GltfModel.Clip>> resolvedClips = new HashMap<>();
        final Map<String, String> clipSource = new HashMap<>();
        long gpuBytes;
        long uploadNanos;
        boolean poseIsBind = true;
        boolean renderLogged;
        /** Glove items: the one glove primitive drawn (largest non-bare-arm primitive by indexed vertices), else -1. */
        final int gloveIcon;

        private static int gloveIcon(GltfModel model) {
            int best = -1;
            int bestVertices = -1;
            for (int i = 0; i < model.primitives.size(); i++) {
                GltfModel.Primitive primitive = model.primitives.get(i);
                String texture = primitive.texture() >= 0 ? model.textures.get(primitive.texture()).name() : null;
                if (!dev.csmc.viewmodel.gloves.GloveView.paintable(texture)) continue;
                // Exported glove primitives share one vertex accessor: count what this primitive indexes.
                int vertices = ReferencedGeometry.vertexCount(primitive.indices(), primitive.vertexCount());
                if (vertices > bestVertices) {
                    best = i;
                    bestVertices = vertices;
                }
            }
            return best;
        }

        /** Fits the bind-pose skinned bounds of the vertices {@code primitive} draws to one block about their center. */
        private static Matrix4f fit(GltfPose pose, GltfModel.Primitive primitive) {
            float[] positions = new float[primitive.positions().length];
            if (primitive.skinned()) pose.skin(primitive, positions, new float[primitive.normals().length]);
            else System.arraycopy(primitive.positions(), 0, positions, 0, positions.length);
            float[] bounds = ReferencedGeometry.bounds(positions, primitive.indices());
            if (bounds == null) return new Matrix4f();
            float extent = Math.max(bounds[3] - bounds[0], Math.max(bounds[4] - bounds[1], bounds[5] - bounds[2]));
            float scale = extent > 1e-8f ? 1 / extent : 1;
            return new Matrix4f().scale(scale)
                    .translate(-(bounds[0] + bounds[3]) / 2, -(bounds[1] + bounds[4]) / 2, -(bounds[2] + bounds[5]) / 2);
        }

        Loaded(String id, boolean legacy, Prepared prepared, CacheManifest.Weapon entry) {
            this.model = prepared.model;
            this.id = id;
            this.legacy = "body_legacy".equals(prepared.meshSet);
            this.meshSet = prepared.meshSet;
            this.entry = entry;
            pose = model.newPose();
            // A glove item shows one glove of its viewmodel mesh (no bare arm, not both hands of the T-pose).
            gloveIcon = CsmcMod.GLOVES.containsKey(id) ? gloveIcon(model) : -1;
            fit = gloveIcon >= 0 ? fit(pose, model.primitives.get(gloveIcon)) : model.normalization(1);
            float extent = Math.max(model.boundsMax[0] - model.boundsMin[0],
                    Math.max(model.boundsMax[1] - model.boundsMin[1], model.boundsMax[2] - model.boundsMin[2]));
            // VRF exports metres; raw Source units are inches. A 5+ unit gun can only be inches.
            metersPerUnit = extent > 5 ? 0.0254f : 1f;
            float meters = extent * metersPerUnit;
            grip = model.nodeIndex(GRIP_NODE);
            Vector3f center = new Vector3f((model.boundsMin[0] + model.boundsMax[0]) / 2,
                    (model.boundsMin[1] + model.boundsMax[1]) / 2, (model.boundsMin[2] + model.boundsMax[2]) / 2);
            Vector3f origin = grip >= 0 ? pose.world(grip).getTranslation(new Vector3f()) : center;
            restGrip = origin;
            if (gloveIcon >= 0) {
                hand = new Matrix4f(fit).scaleLocal(.5f);
                pivot = "glove fit";
            } else if (meters >= 0.05f && meters <= 3f) {
                hand = new Matrix4f().scale(metersPerUnit).translate(-origin.x, -origin.y, -origin.z);
                pivot = grip >= 0 ? GRIP_NODE : "bounds center";
            } else {
                // Implausible real size (not a CS2 export): keep the old fit-to-block behaviour in hand.
                hand = new Matrix4f(fit).scaleLocal(.7f);
                pivot = "fit (extent " + extent + ")";
            }
            long started = System.nanoTime();
            ResourceLocation[] locations = new ResourceLocation[prepared.textures.size()];
            int uploaded = 0;
            try {
                for (; uploaded < locations.length; uploaded++) {
                    NativeImage image = prepared.textures.get(uploaded).image;
                    int width = image.getWidth();
                    int height = image.getHeight();
                    ResourceLocation location = CsmcMod.id("cached_gltf/" + id + (legacy ? "/legacy/" : "/default/") + uploaded);
                    UploadedTexture texture = new UploadedTexture(image); // closes the image
                    texture.setFilter(true, false);
                    Minecraft.getInstance().getTextureManager().register(location, texture);
                    textures.add(location);
                    textureSizes.add(width + "x" + height);
                    gpuBytes += 4L * width * height;
                    locations[uploaded] = location;
                }
            } catch (RuntimeException e) {
                // NativeImage.close is idempotent; the failing image may or may not have been consumed.
                for (int i = uploaded; i < locations.length; i++) prepared.textures.get(i).image.close();
                close();
                throw e;
            }
            uploadNanos = System.nanoTime() - started;
            List<Mesh> list = new ArrayList<>();
            // The primary weapon atlas is painted; separate lenses/scopes/accessory materials retain their albedo.
            int[] counts = new int[model.textures.size()];
            for (GltfModel.Primitive primitive : model.primitives) {
                if (primitive.texture() >= 0) counts[primitive.texture()] += primitive.vertexCount();
            }
            int primary = -1;
            for (int i = 0; i < counts.length; i++) if (primary < 0 || counts[i] > counts[primary]) primary = i;
            for (int i = 0; i < model.primitives.size(); i++) {
                if (gloveIcon >= 0 && i != gloveIcon) continue;
                GltfModel.Primitive primitive = model.primitives.get(i);
                list.add(new Mesh(primitive, locations[prepared.primitiveTexture[i]],
                        gloveIcon >= 0 || primitive.texture() == primary));
            }
            // Blended layers do not write depth; opaque geometry must establish depth first.
            list.sort(Comparator.comparing(mesh -> mesh.primitive.alphaMode() == GltfModel.AlphaMode.BLEND));
            meshes = list.toArray(Mesh[]::new);
        }

        /** Config override, then the cache manifest's mapping/clip list, then a fuzzy clip-name match. */
        GltfModel.Clip clip(String action) {
            Optional<GltfModel.Clip> cached = resolvedClips.get(action);
            if (cached == null) {
                String configured = TRANSFORMS.animationOverride(id, action);
                GltfModel.Clip clip;
                if (configured != null) {
                    clip = findClip(configured);
                    clipSource.put(action, "config");
                } else if (entry != null && !entry.clips().isEmpty()) {
                    String name = entry.clipFor(action);
                    clip = name == null ? null : exactClip(name);
                    clipSource.put(action, "manifest");
                } else {
                    clip = findClip(action);
                    clipSource.put(action, "name match");
                }
                cached = Optional.ofNullable(clip);
                resolvedClips.put(action, cached);
            }
            return cached.orElse(null);
        }

        private GltfModel.Clip exactClip(String name) {
            GltfModel.Clip exact = model.clips.get(name);
            if (exact != null) return exact;
            for (var entry : model.clips.entrySet()) if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
            return null;
        }

        private GltfModel.Clip findClip(String name) {
            GltfModel.Clip exact = exactClip(name);
            if (exact != null) return exact;
            String lower = name.toLowerCase(Locale.ROOT);
            for (var entry : model.clips.entrySet()) if (entry.getKey().toLowerCase(Locale.ROOT).contains(lower)) return entry.getValue();
            return null;
        }

        void close() {
            textures.forEach(location -> Minecraft.getInstance().getTextureManager().release(location));
            textures.clear();
        }
    }

    private record CachedModel(BakedModel original, String id) implements BakedModel {
        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) { return original.getQuads(state, side, random); }
        @Override public boolean useAmbientOcclusion() { return original.useAmbientOcclusion(); }
        @Override public boolean isGui3d() { return model(id) != null || original.isGui3d(); }
        @Override public boolean usesBlockLight() { return model(id) != null || original.usesBlockLight(); }
        @Override public boolean isCustomRenderer() { return model(id) != null || original.isCustomRenderer(); }
        @Override public TextureAtlasSprite getParticleIcon() { return original.getParticleIcon(); }
        @Override public ItemTransforms getTransforms() { return model(id) == null ? original.getTransforms() : ItemTransforms.NO_TRANSFORMS; }
        @Override public ItemOverrides getOverrides() { return original.getOverrides(); }
    }
}
