package dev.csmc.client.hud;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.csmc.CsmcMod;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.data.CachePaths;
import dev.csmc.hud.AmmoHudLayout;
import dev.csmc.hud.AmmoHudState;
import dev.csmc.hud.WeaponIcons;
import dev.csmc.item.GunItem;
import dev.csmc.network.AmmoHudPayload;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Bottom-right weapon readout while a gun is held: white weapon silhouette, thin divider, large magazine count and
 * smaller reserve count, plus a small state line (reloading / no ammo / cancelled). Values come only from the
 * server's {@link AmmoHudPayload}. The exported CS2 icon is used when present; otherwise, once the gun's cached
 * model has loaded, it is rendered once offscreen and flattened to a white silhouette (never the vanilla
 * placeholder; until then only the counts show).
 *
 * <p>Lifecycle: icons, their GPU textures and the cached text are dropped on resource reload (new exports, models
 * or language) and on disconnect, together with the ammo state. Unchanged frames reuse cached strings, widths,
 * state-line text and placement.
 */
public final class AmmoHud {
    private static final int SILHOUETTE_SIZE = 128;
    private static final String[] LINE_KEYS = {null, "message.csmc.reloading", "message.csmc.no_ammo",
            "message.csmc.reload_cancelled"};
    private static final AmmoHudState STATE = new AmmoHudState();
    private static final Map<ResourceLocation, Icon> ICONS = new HashMap<>();
    /** Weapons with no usable exported PNG, so the file is not probed every frame. */
    private static final Set<ResourceLocation> NO_EXPORT = new HashSet<>();
    /** Weapons whose loaded model rendered nothing offscreen, so the capture is not retried every frame. */
    private static final Set<ResourceLocation> NO_SILHOUETTE = new HashSet<>();
    private static final AmmoHudState.NumberText MAGAZINE_TEXT = new AmmoHudState.NumberText();
    private static final AmmoHudState.NumberText RESERVE_TEXT = new AmmoHudState.NumberText();
    private static final AmmoHudLayout.Cache PLACEMENT = new AmmoHudLayout.Cache();
    private static final FormattedCharSequence[] LINE_TEXT = new FormattedCharSequence[LINE_KEYS.length];
    private static final int[] LINE_WIDTH = new int[LINE_KEYS.length];
    private static String magazineText;
    private static int magazineWidth;
    private static String reserveText;
    private static int reserveWidth;

    /** Source pixels (white with alpha) plus the GPU copy downsampled for the current size. */
    private static final class Icon {
        final ResourceLocation weapon;
        final int width;
        final int height;
        final int[] alpha;
        ResourceLocation texture;
        int uploadedWidth;
        int uploadedHeight;

        Icon(ResourceLocation weapon, int width, int height, int[] alpha) {
            this.weapon = weapon;
            this.width = width;
            this.height = height;
            this.alpha = alpha;
        }
    }

    private AmmoHud() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(AmmoHudPayload.TYPE,
                (payload, context) -> STATE.accept(payload.slot(), payload.weaponId(), payload.magazine(),
                        payload.reserve(), payload.reloading(), payload.notice(), System.currentTimeMillis()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            STATE.clear();
            releaseIcons(client);
        }));
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override public ResourceLocation getFabricId() { return CsmcMod.id("ammo_hud"); }
                    @Override public void onResourceManagerReload(ResourceManager manager) {
                        releaseIcons(Minecraft.getInstance());
                    }
                });
        HudRenderCallback.EVENT.register((graphics, tick) -> render(graphics));
    }

    /** Frees every HUD icon texture and forgets cached icons, absences and text (render thread). */
    private static void releaseIcons(Minecraft client) {
        for (Icon icon : ICONS.values()) {
            if (icon.texture != null) client.getTextureManager().release(icon.texture);
        }
        ICONS.clear();
        NO_EXPORT.clear();
        NO_SILHOUETTE.clear();
        java.util.Arrays.fill(LINE_TEXT, null);
        magazineText = null;
        reserveText = null;
    }

    private static void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui || client.player == null || client.player.isSpectator()) return;
        ItemStack held = client.player.getMainHandItem();
        if (!(held.getItem() instanceof GunItem gun)) return;
        ResourceLocation weapon = gun.weaponId();
        int slot = client.player.getInventory().selected;
        int magazine = STATE.magazine(slot, weapon, held.getOrDefault(CsmcMod.MAGAZINE, 0));
        int reserve = STATE.reserve(slot, weapon);

        Font font = client.font;
        String magazineNow = MAGAZINE_TEXT.of(magazine);
        if (magazineNow != magazineText) {
            magazineText = magazineNow;
            magazineWidth = font.width(magazineNow);
        }
        String reserveNow = RESERVE_TEXT.of(reserve);
        if (reserveNow != reserveText) {
            reserveText = reserveNow;
            reserveWidth = font.width(reserveNow);
        }

        Icon icon = icon(client, graphics, weapon, held);
        int iconWidth = icon == null ? 0 : AmmoHudLayout.iconWidth(icon.width, icon.height);
        boolean offhandRight = client.player.getMainArm() == HumanoidArm.LEFT
                && !client.player.getOffhandItem().isEmpty();
        int guiScale = Math.max(1, (int) Math.round(client.getWindow().getGuiScale()));
        int unitPixels = AmmoHudLayout.unitPixels(client.getWindow().getHeight(), guiScale);
        AmmoHudLayout.Placement box = PLACEMENT.place(graphics.guiWidth(), graphics.guiHeight(), guiScale,
                unitPixels, iconWidth, magazineWidth, reserveWidth, offhandRight);

        graphics.pose().pushPose();
        graphics.pose().translate(box.x(), box.y(), 0);
        graphics.pose().scale((float) box.scale(), (float) box.scale(), 1);
        graphics.fill(0, 0, box.width(), box.height(), 0x40000000);
        int rowHeight = box.height() - 2 * AmmoHudLayout.PADDING;
        if (icon != null) {
            // Capped-width silhouettes shrink in height rather than squash.
            int iconHeight = Math.min(AmmoHudLayout.ICON_HEIGHT, Math.round(iconWidth * (float) icon.height / icon.width));
            int iconY = box.rowY() + (rowHeight - iconHeight) / 2;
            ResourceLocation texture = upload(client, icon, iconHeight * unitPixels);
            RenderSystem.enableBlend();
            graphics.blit(texture, box.iconX(), iconY, iconWidth, iconHeight, 0, 0,
                    icon.uploadedWidth, icon.uploadedHeight, icon.uploadedWidth, icon.uploadedHeight);
            RenderSystem.disableBlend();
        }
        graphics.fill(box.dividerX(), box.rowY() + 1, box.dividerX() + 1, box.rowY() + rowHeight - 1, 0xA0FFFFFF);

        // Magazine and reserve digits share a baseline, centred in the row.
        int digits = AmmoHudLayout.DIGIT_HEIGHT * AmmoHudLayout.MAGAZINE_SCALE;
        int magazineTop = box.rowY() + (rowHeight - digits) / 2;
        int baseline = magazineTop + digits;
        int magazineColor = magazine == 0 ? 0xFFFF5555 : 0xFFFFFFFF;
        graphics.pose().pushPose();
        graphics.pose().translate(box.magazineX(), magazineTop, 0);
        graphics.pose().scale(AmmoHudLayout.MAGAZINE_SCALE, AmmoHudLayout.MAGAZINE_SCALE, 1);
        graphics.drawString(font, magazineText, 0, 0, magazineColor, true);
        graphics.pose().popPose();
        if (reserve >= 0) {
            int reserveColor = reserve == 0 ? 0xFFFF5555 : 0xFFCCCCCC;
            graphics.drawString(font, reserveText, box.reserveX(), baseline - AmmoHudLayout.DIGIT_HEIGHT,
                    reserveColor, true);
        }

        int line = STATE.line(slot, weapon, System.currentTimeMillis());
        if (line != AmmoHudState.LINE_NONE) {
            if (LINE_TEXT[line] == null) {
                LINE_TEXT[line] = Language.getInstance().getVisualOrder(Component.translatable(LINE_KEYS[line]));
                LINE_WIDTH[line] = font.width(LINE_TEXT[line]);
            }
            graphics.drawString(font, LINE_TEXT[line], box.width() - LINE_WIDTH[line], -AmmoHudLayout.FONT_HEIGHT - 1,
                    0xFFFFFFFF, true);
        }
        graphics.pose().popPose();
    }

    private static Icon icon(Minecraft client, GuiGraphics graphics, ResourceLocation weapon, ItemStack held) {
        Icon icon = ICONS.get(weapon);
        if (icon != null) return icon;
        if (!NO_EXPORT.contains(weapon)) {
            icon = loadExported(weapon);
            if (icon != null) {
                ICONS.put(weapon, icon);
                return icon;
            }
            NO_EXPORT.add(weapon);
        }
        // Until the cached model is loaded the item renders as its vanilla placeholder: capture nothing yet.
        if (NO_SILHOUETTE.contains(weapon) || !CachedGunRendering.modelReady(weapon.getPath())) return null;
        icon = silhouette(client, graphics, weapon, held);
        if (icon == null) {
            NO_SILHOUETTE.add(weapon);
        } else {
            ICONS.put(weapon, icon);
        }
        return icon;
    }

    private static Icon loadExported(ResourceLocation weapon) {
        Path file = WeaponIcons.file(CachePaths.root(), weapon.toString());
        if (file == null || !Files.isRegularFile(file)) return null;
        try (InputStream input = Files.newInputStream(file); NativeImage image = NativeImage.read(input)) {
            return trimmed(weapon, image, false);
        } catch (Exception e) {
            CsmcMod.LOGGER.warn("Ignoring unreadable weapon icon {}: {}", file, e.toString());
            return null;
        }
    }

    /** Fallback: draws the held item model once into an offscreen target and keeps only its alpha. */
    private static Icon silhouette(Minecraft client, GuiGraphics graphics, ResourceLocation weapon, ItemStack held) {
        graphics.flush();
        TextureTarget target = new TextureTarget(SILHOUETTE_SIZE, SILHOUETTE_SIZE, true, Minecraft.ON_OSX);
        try {
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 16, 16, 0, 1000, 21000),
                    VertexSorting.ORTHOGRAPHIC_Z);
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.translation(0, 0, -11000);
            RenderSystem.applyModelViewMatrix();
            GuiGraphics offscreen = new GuiGraphics(client, client.renderBuffers().bufferSource());
            offscreen.renderItem(held, 0, 0);
            offscreen.flush();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            try (NativeImage image = new NativeImage(SILHOUETTE_SIZE, SILHOUETTE_SIZE, false)) {
                RenderSystem.bindTexture(target.getColorTextureId());
                image.downloadTexture(0, false);
                image.flipY();
                return trimmed(weapon, image, true);
            }
        } finally {
            target.destroyBuffers();
            client.getMainRenderTarget().bindWrite(true);
        }
    }

    /** Alpha of the opaque bounding box; with {@code anyCoverage} every touched pixel becomes fully opaque. */
    private static Icon trimmed(ResourceLocation weapon, NativeImage image, boolean anyCoverage) {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return null;
        int width = maxX - minX + 1, height = maxY - minY + 1;
        int[] alpha = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = image.getPixelRGBA(minX + x, minY + y) >>> 24;
                alpha[y * width + x] = anyCoverage && a != 0 ? 255 : a;
            }
        }
        return new Icon(weapon, width, height, alpha);
    }

    /** Box-filters the icon to its on-screen framebuffer height so large exports stay smooth at every size. */
    private static ResourceLocation upload(Minecraft client, Icon icon, int pixelHeight) {
        int targetHeight = Math.max(1, Math.min(icon.height, pixelHeight));
        if (icon.texture != null && icon.uploadedHeight == targetHeight) return icon.texture;
        int targetWidth = Math.max(1, Math.round(icon.width * (float) targetHeight / icon.height));
        NativeImage image = new NativeImage(targetWidth, targetHeight, true);
        for (int ty = 0; ty < targetHeight; ty++) {
            int y0 = ty * icon.height / targetHeight, y1 = Math.max(y0 + 1, (ty + 1) * icon.height / targetHeight);
            for (int tx = 0; tx < targetWidth; tx++) {
                int x0 = tx * icon.width / targetWidth, x1 = Math.max(x0 + 1, (tx + 1) * icon.width / targetWidth);
                int sum = 0;
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) sum += icon.alpha[y * icon.width + x];
                image.setPixelRGBA(tx, ty, (sum / ((y1 - y0) * (x1 - x0))) << 24 | 0xFFFFFF);
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        icon.uploadedHeight = targetHeight;
        icon.uploadedWidth = targetWidth;
        if (icon.texture == null) icon.texture = CsmcMod.id("hud_icon/" + icon.weapon.getNamespace() + "/" + icon.weapon.getPath());
        // Registering over the same id closes the previous upload.
        client.getTextureManager().register(icon.texture, texture);
        return icon.texture;
    }
}
