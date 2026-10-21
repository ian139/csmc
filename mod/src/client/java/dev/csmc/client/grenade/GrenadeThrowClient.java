package dev.csmc.client.grenade;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.csmc.client.thirdperson.ActionTimeline;
import dev.csmc.grenade.GrenadeArc;
import dev.csmc.grenade.GrenadeThrow;
import dev.csmc.grenade.GrenadeThrowInput;
import dev.csmc.grenade.GrenadeTuning;
import dev.csmc.item.GrenadeItem;
import dev.csmc.network.GrenadePrimePayload;
import dev.csmc.network.GrenadeThrowPayload;
import dev.csmc.thirdperson.melee.GrenadePoseCurves;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

/**
 * CS-style prime and release on top of {@link GrenadeThrowInput}. Attack/use edges arrive from
 * {@code GrenadeInputMixin} in order; each client tick primes (pin sound, raised first-person pose, trajectory
 * preview) or, on full release, throws by sending {@link GrenadeThrowPayload} with the strength of the buttons
 * pressed while primed. Switching slot/item, opening a screen, losing window focus or dying cancels without throwing; the grenade
 * stays in the inventory. Vanilla attack/use are suppressed while a main-hand grenade is held, except for spectators.
 * Prime and cancel are announced with {@link GrenadePrimePayload} for third-person viewers, and mirrored into the
 * local {@link ActionTimeline} because the server's broadcasts skip the actor (F5 body).
 */
public final class GrenadeThrowClient {
    private static final long PRIME_NANOS = 150_000_000L;
    private static final long THROW_NANOS = 250_000_000L;
    private static final int RING_SEGMENTS = 24;
    private static final float RING_RADIUS = 0.35F;
    private static final float[] RING_COS = new float[RING_SEGMENTS + 1];
    private static final float[] RING_SIN = new float[RING_SEGMENTS + 1];
    private static final GrenadeThrowInput INPUT = new GrenadeThrowInput();
    private static final GrenadeArc.Path PATH = new GrenadeArc.Path();
    private static final Quaternionf HAND_ROTATION = new Quaternionf();

    static {
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = Math.PI * 2 * i / RING_SEGMENTS;
            RING_COS[i] = (float) Math.cos(angle);
            RING_SIN[i] = (float) Math.sin(angle);
        }
    }

    private static KeyMapping previewKey;
    private static boolean previewEnabled = true;
    /** {@link #PATH} holds this tick's prediction. */
    private static boolean pathReady;
    private static ClientLevel colliderLevel;
    private static GrenadeArc.Collider collider;
    /** Monotonic for the client's lifetime; the server forgets its last value on join. */
    private static int sequence;
    private static long primedAt;
    private static long thrownAt = Long.MIN_VALUE / 2;

    private GrenadeThrowClient() {}

    static void initialize() {
        previewKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.csmc.grenade_preview",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.csmc"));
        ClientTickEvents.END_CLIENT_TICK.register(GrenadeThrowClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(GrenadeThrowClient::renderPreview);
    }

    static void reset() {
        INPUT.reset();
        pathReady = false;
        colliderLevel = null;
        collider = null;
    }

    /** True when a main-hand grenade owns attack/use: vanilla mining, punching and item use are suppressed. */
    public static boolean ownsInput(Minecraft client) {
        LocalPlayer player = client.player;
        return player != null && GrenadeThrowInput.ownsInput(player.getMainHandItem().getItem() instanceof GrenadeItem,
                player.isSpectator());
    }

    /** Raw attack/use edge from {@code KeyMapping.setDown}, before vanilla applies it. */
    public static void keyEdge(KeyMapping mapping, boolean down) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) return;
        if (mapping == client.options.keyAttack) INPUT.edge(GrenadeThrowInput.ATTACK, down);
        else if (mapping == client.options.keyUse) INPUT.edge(GrenadeThrowInput.USE, down);
    }

    private static void tick(Minecraft client) {
        while (previewKey.consumeClick()) {
            previewEnabled = !previewEnabled;
            if (client.player != null) {
                client.player.displayClientMessage(Component.translatable(previewEnabled
                        ? "message.csmc.grenade_preview.on" : "message.csmc.grenade_preview.off"), true);
            }
        }
        pathReady = false;
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.getConnection() == null) {
            INPUT.reset();
            return;
        }
        ItemStack stack = player.getMainHandItem();
        int slot = player.getInventory().selected;
        boolean holding = stack.getItem() instanceof GrenadeItem && client.screen == null && player.isAlive()
                && !player.isSpectator();
        boolean wasPrimed = INPUT.primed();
        INPUT.tick(holding, !player.getCooldowns().isOnCooldown(stack.getItem()), client.isWindowActive(), slot,
                stack.getItem(), client.options.keyAttack.isDown(), client.options.keyUse.isDown());
        if (wasPrimed && !INPUT.primed() && INPUT.thrown() == null) {
            // Cancelled (screen, focus, death, slot/item change): third-person viewers drop the cocked pose.
            ClientPlayNetworking.send(new GrenadePrimePayload(false));
            ActionTimeline.Entry action = ActionTimeline.current(player.getId());
            if (action != null && (action.action() == ActionTimeline.Action.GRENADE_PRIME
                    || action.action() == ActionTimeline.Action.GRENADE_THROW)) {
                ActionTimeline.start(player.getId(), ActionTimeline.Action.NONE, ActionTimeline.now(), 0.0, 0);
            }
        }
        if (INPUT.primedThisTick() && stack.getItem() instanceof GrenadeItem grenade) {
            primedAt = System.nanoTime();
            GrenadeSounds.local(grenade.kind(), GrenadeSounds.PIN, player.getEyePosition(), SoundEvents.TRIPWIRE_CLICK_ON,
                    0.6F, 1.6F);
            // The server judges the prime against this slot, which vanilla only syncs next tick for number keys.
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            ClientPlayNetworking.send(new GrenadePrimePayload(true));
            // Server state broadcasts skip the actor, so the local F5 body is driven from here.
            ActionTimeline.start(player.getId(), ActionTimeline.Action.GRENADE_PRIME, ActionTimeline.now(), 0.0, 0);
        }
        GrenadeThrow.Strength thrown = INPUT.thrown();
        if (thrown != null) {
            thrownAt = System.nanoTime();
            // Number-key selection is only synced by vanilla next tick; the server must judge this slot.
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            ClientPlayNetworking.send(new GrenadeThrowPayload(thrown, ++sequence));
            ActionTimeline.start(player.getId(), ActionTimeline.Action.GRENADE_THROW, ActionTimeline.now(),
                    GrenadePoseCurves.THROW_SECONDS, thrown.ordinal());
        }
        if (INPUT.primed() && previewEnabled && stack.getItem() instanceof GrenadeItem grenade) {
            predict(client.level, player, grenade);
        }
    }

    /** Once per tick from the player's tick state; rendering only reads {@link #PATH}. */
    private static void predict(ClientLevel level, LocalPlayer player, GrenadeItem grenade) {
        if (colliderLevel != level) {
            colliderLevel = level;
            collider = GrenadeArc.collider(level);
        }
        Vec3 moved = new Vec3(player.getX() - player.xo, player.onGround() ? 0.0 : player.getY() - player.yo,
                player.getZ() - player.zo);
        GrenadeThrow.Launch launch = GrenadeThrow.launch(collider, player.getEyePosition(), player.getXRot(),
                player.getYRot(), moved, INPUT.strength().value);
        GrenadeArc.simulate(collider, grenade.kind(), launch.position(), launch.velocity(), PATH);
        pathReady = true;
    }

    private static void renderPreview(WorldRenderContext context) {
        if (!pathReady || !INPUT.primed() || !previewEnabled || PATH.size() < 2) return;
        Minecraft client = Minecraft.getInstance();
        PoseStack poses = context.matrixStack();
        Vec3 camera = context.camera().getPosition();
        double cx = camera.x, cy = camera.y - GrenadeTuning.PROJECTILE_SIZE / 2.0, cz = camera.z;
        PoseStack.Pose pose = poses.last();
        MultiBufferSource.BufferSource buffers = client.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        int last = PATH.size() - 1;
        for (int i = 1; i <= last; i++) {
            line(lines, pose, (float) (PATH.x(i - 1) - cx), (float) (PATH.y(i - 1) - cy), (float) (PATH.z(i - 1) - cz),
                    (float) (PATH.x(i) - cx), (float) (PATH.y(i) - cy), (float) (PATH.z(i) - cz), 0xFFFFE040);
        }
        buffers.endBatch(RenderType.lines());
        // Landing ring without depth testing so foliage or a ledge never hides it.
        float ex = (float) (PATH.x(last) - camera.x);
        float ey = (float) (PATH.y(last) - camera.y) + 0.03F;
        float ez = (float) (PATH.z(last) - camera.z);
        int color = PATH.extinguished() ? 0xFF40A0FF : 0xFFFF4030;
        BufferBuilder ring = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            line(ring, pose, ex + RING_COS[i] * RING_RADIUS, ey, ez + RING_SIN[i] * RING_RADIUS,
                    ex + RING_COS[i + 1] * RING_RADIUS, ey, ez + RING_SIN[i + 1] * RING_RADIUS, color);
        }
        line(ring, pose, ex - RING_RADIUS, ey, ez, ex + RING_RADIUS, ey, ez, color);
        line(ring, pose, ex, ey, ez - RING_RADIUS, ex, ey, ez + RING_RADIUS, color);
        MeshData mesh = ring.build();
        if (mesh != null) {
            RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.lineWidth(3.0F);
            BufferUploader.drawWithShader(mesh);
            RenderSystem.lineWidth(1.0F);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        }
    }

    private static void line(VertexConsumer lines, PoseStack.Pose pose, float ax, float ay, float az, float bx,
            float by, float bz, int argb) {
        float nx = bx - ax, ny = by - ay, nz = bz - az;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-6F) return;
        nx /= length;
        ny /= length;
        nz /= length;
        lines.addVertex(pose, ax, ay, az).setColor(argb).setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, bx, by, bz).setColor(argb).setNormal(pose, nx, ny, nz);
    }

    /**
     * First-person grenade pose in hand space (before the model transform): raised and cocked back while primed,
     * a short forward lob after release. Called by {@code CachedGunRendering} for utility items only.
     */
    public static void firstPersonPose(PoseStack poses) {
        long now = System.nanoTime();
        if (INPUT.primed()) {
            float p = Math.min(1.0F, (now - primedAt) / (float) PRIME_NANOS);
            float ease = p * p * (3 - 2 * p);
            poses.translate(0.0F, 0.18F * ease, 0.12F * ease);
            poses.mulPose(HAND_ROTATION.rotationX((float) Math.toRadians(25.0F * ease)));
        } else if (now - thrownAt < THROW_NANOS) {
            float t = (now - thrownAt) / (float) THROW_NANOS;
            float out = (float) Math.sin(Math.PI * t);
            poses.translate(0.0F, 0.12F * (1 - t), -0.45F * out);
            poses.mulPose(HAND_ROTATION.rotationX((float) Math.toRadians(-35.0F * out)));
        }
    }
}
