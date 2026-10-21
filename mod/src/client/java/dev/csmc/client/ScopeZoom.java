package dev.csmc.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.csmc.item.GunItem;
import dev.csmc.network.GunActionPayload;
import dev.csmc.network.ScopeStatePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** Server-confirmed, local-player-only scope presentation; no client weapon registry or zoom prediction. */
public final class ScopeZoom {
    private static final GunActionPayload ZOOM = new GunActionPayload(GunActionPayload.ZOOM);
    private static final int SEGMENTS = 256;
    private static final float[] UNIT_X = new float[SEGMENTS + 1];
    private static final float[] UNIT_Y = new float[SEGMENTS + 1];
    private static final float[] INNER_X = new float[SEGMENTS + 1];
    private static final float[] INNER_Y = new float[SEGMENTS + 1];
    private static final float[] OUTER_X = new float[SEGMENTS + 1];
    private static final float[] OUTER_Y = new float[SEGMENTS + 1];
    private static ClientLevel world;
    private static LocalPlayer player;
    private static ResourceLocation weaponId;
    private static int slot = -1;
    private static int level;
    private static int levels;
    private static int usePresses;
    private static double fromRatio = 1;
    private static double targetRatio = 1;
    private static long transitionStart;
    private static double transitionNanos;
    private static int maskWidth;
    private static int maskHeight;
    private static float maskRadius;

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            double angle = i * (Math.PI * 2 / SEGMENTS);
            UNIT_X[i] = (float) Math.cos(angle);
            UNIT_Y[i] = (float) Math.sin(angle);
        }
        UNIT_X[SEGMENTS] = UNIT_X[0];
        UNIT_Y[SEGMENTS] = UNIT_Y[0];
    }

    private ScopeZoom() { }

    public static void initialize() {
        // Fabric invokes play payload handlers on the render thread.
        ClientPlayNetworking.registerGlobalReceiver(ScopeStatePayload.TYPE,
                (payload, context) -> receive(context.client(), payload));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null || client.getConnection() == null) clear();
            else validateBinding(client);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> usePresses = 0);
    }

    private static void receive(Minecraft client, ScopeStatePayload payload) {
        validateBinding(client);
        if (payload.levels() <= 0) {
            clearScope();
            return;
        }
        if (client.player == null || client.level == null || !client.player.isAlive()
                || client.player.isSpectator() || client.player.getInventory().selected != payload.slot()
                || !(client.player.getMainHandItem().getItem() instanceof GunItem gun)
                || !gun.weaponId().equals(payload.weaponId())) return;

        long now = System.nanoTime();
        fromRatio = ratioAt(now);
        world = client.level;
        player = client.player;
        slot = payload.slot();
        weaponId = payload.weaponId();
        level = payload.level();
        levels = payload.levels();
        targetRatio = level == 0 ? 1 : Math.tan(Math.toRadians(payload.fov()) / 2);
        transitionStart = now;
        transitionNanos = Math.max(0, payload.timeS()) * 1_000_000_000.0;
    }

    private static boolean matchesBinding(Minecraft client) {
        return world != null && client.level == world && client.player == player && player.isAlive()
                && !player.isSpectator() && player.getInventory().selected == slot
                && player.getMainHandItem().getItem() instanceof GunItem gun
                && gun.weaponId().equals(weaponId);
    }

    private static boolean validateBinding(Minecraft client) {
        if (matchesBinding(client)) return true;
        if (weaponId != null) clearScope();
        return false;
    }

    private static void clearScope() {
        world = null;
        player = null;
        weaponId = null;
        slot = -1;
        level = 0;
        levels = 0;
        fromRatio = 1;
        targetRatio = 1;
        transitionNanos = 0;
    }

    /** World changes/disconnects clear capabilities as well as the cosmetic transition. */
    public static void clear() {
        clearScope();
        usePresses = 0;
    }

    /** Only the server's advertisement for the currently held gun takes over vanilla Use. */
    public static boolean blocksUse(Minecraft client) {
        return validateBinding(client) && levels > 0;
    }

    /** KeyMapping's actual up-to-down edge excludes keyboard autorepeat and preserves rapid taps. */
    public static void useKeyDown(KeyMapping mapping, boolean down) {
        Minecraft client = Minecraft.getInstance();
        if (client.options != null && mapping == client.options.keyUse && down && !mapping.isDown()
                && client.player != null && client.level != null && client.screen == null
                && client.getOverlay() == null) usePresses++;
    }

    /** Runs after vanilla hotbar selection; startUseItem is separately cancelled while capable. */
    public static void handleUseInput(Minecraft client) {
        int presses = usePresses;
        usePresses = 0;
        if (presses == 0 || client.screen != null || client.getOverlay() != null
                || client.getConnection() == null || !blocksUse(client)) return;
        // Vanilla only syncs number-key selection later. Preserve packet ordering before the intent.
        client.getConnection().send(new ServerboundSetCarriedItemPacket(client.player.getInventory().selected));
        for (int i = 0; i < presses; i++) ClientPlayNetworking.send(ZOOM);
        // No local level change: reload/cadence/spectator rejection must leave the server in charge.
    }

    private static double ratioAt(long now) {
        if (transitionNanos <= 0 || now - transitionStart >= transitionNanos) return targetRatio;
        double progress = Math.max(0, (now - transitionStart) / transitionNanos);
        return fromRatio + (targetRatio - fromRatio) * progress;
    }

    public static boolean isScoped() {
        Minecraft client = Minecraft.getInstance();
        return validateBinding(client) && level > 0 && client.options.getCameraType().isFirstPerson()
                && client.getCameraEntity() == client.player;
    }

    /** Shared tangent-space multiplier for world FOV and actual mouse rotation deltas. */
    public static double zoomRatio() {
        Minecraft client = Minecraft.getInstance();
        if (!validateBinding(client) || !client.options.getCameraType().isFirstPerson()
                || client.getCameraEntity() != client.player) return 1;
        return ratioAt(System.nanoTime());
    }

    public static double fieldOfView(double normalFov) {
        double ratio = zoomRatio();
        return ratio == 1 ? normalFov : Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(normalFov) / 2) * ratio));
    }

    /** Original opaque circular aperture and hairlines, batched without frame-local allocations. */
    public static void render(GuiGraphics graphics) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        if (width != maskWidth || height != maskHeight) {
            maskWidth = width;
            maskHeight = height;
            maskRadius = Math.min(width, height) * 0.48f;
            float outer = (float) Math.hypot(cx, cy) + 2;
            for (int i = 0; i <= SEGMENTS; i++) {
                INNER_X[i] = cx + UNIT_X[i] * maskRadius;
                INNER_Y[i] = cy + UNIT_Y[i] * maskRadius;
                OUTER_X[i] = cx + UNIT_X[i] * outer;
                OUTER_Y[i] = cy + UNIT_Y[i] * outer;
            }
        }
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < SEGMENTS; i++) {
            vertex(vertices, pose, INNER_X[i], INNER_Y[i]);
            vertex(vertices, pose, INNER_X[i + 1], INNER_Y[i + 1]);
            vertex(vertices, pose, OUTER_X[i + 1], OUTER_Y[i + 1]);
            vertex(vertices, pose, OUTER_X[i], OUTER_Y[i]);
        }
        float halfLine = (float) (0.5 / Minecraft.getInstance().getWindow().getGuiScale());
        rectangle(vertices, pose, cx - maskRadius, cy - halfLine, cx + maskRadius, cy + halfLine);
        rectangle(vertices, pose, cx - halfLine, cy - maskRadius, cx + halfLine, cy + maskRadius);
        graphics.flush();
    }

    private static void rectangle(VertexConsumer vertices, Matrix4f pose, float x0, float y0, float x1, float y1) {
        vertex(vertices, pose, x0, y0);
        vertex(vertices, pose, x0, y1);
        vertex(vertices, pose, x1, y1);
        vertex(vertices, pose, x1, y0);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, float x, float y) {
        vertices.addVertex(pose, x, y, 0).setColor(0xFF000000);
    }
}
