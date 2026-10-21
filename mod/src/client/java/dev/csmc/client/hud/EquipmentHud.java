package dev.csmc.client.hud;

import dev.csmc.CsmcMod;
import dev.csmc.client.equipment.EquipmentClient;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.equipment.PlantedC4Entity;
import dev.csmc.hud.AmmoHudLayout;
import dev.csmc.hud.AmmoHudState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Equipment readouts in the ammo HUD's style (translucent black box, white shadowed text, HUD units sized from the
 * screen height): the healthshot count bottom-right while a healthshot is held (the ammo HUD stays hidden, so no
 * ammo is ever shown for equipment), and a centred progress bar while the local player injects, plants or defuses.
 * Inject/plant progress comes from server events and advances smoothly between them; defuse progress is the
 * bomb's synced value. All state is cleared on disconnect.
 */
public final class EquipmentHud {
    public static final int NONE = 0;
    public static final int INJECT = 1;
    public static final int PLANT = 2;
    public static final int DEFUSE = 3;
    private static final String[] LABEL_KEYS = {null, "message.csmc.injecting", "message.csmc.planting",
            "message.csmc.defusing"};
    private static final String[] LABEL_FALLBACKS = {null, "Injecting", "Planting", "Defusing"};
    /** Width of the drawn plus sign in HUD units, standing in for the weapon icon slot. */
    private static final int CROSS_SIZE = 9;
    private static final int BAR_WIDTH = 80;
    private static final int BAR_HEIGHT = 3;
    private static final AmmoHudState.NumberText COUNT_TEXT = new AmmoHudState.NumberText();
    private static final AmmoHudLayout.Cache PLACEMENT = new AmmoHudLayout.Cache();
    private static final FormattedCharSequence[] LABEL_TEXT = new FormattedCharSequence[LABEL_KEYS.length];
    private static final int[] LABEL_WIDTH = new int[LABEL_KEYS.length];
    private static String countText;
    private static int countWidth;
    private static int useKind = NONE;
    private static float useProgress;
    private static float useSeconds;
    private static long useStamp;

    private EquipmentHud() {}

    public static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            clearUse();
            releaseText();
        }));
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override public ResourceLocation getFabricId() { return CsmcMod.id("equipment_hud"); }
                    @Override public void onResourceManagerReload(ResourceManager manager) { releaseText(); }
                });
        HudRenderCallback.EVENT.register((graphics, tick) -> render(graphics));
    }

    /** Server-confirmed inject/plant progress (0..1) over {@code seconds}. */
    public static void use(int kind, float progress, float seconds) {
        useKind = kind;
        useProgress = Math.max(0, Math.min(1, progress));
        useSeconds = seconds;
        useStamp = System.nanoTime();
    }

    public static void clearUse() {
        useKind = NONE;
    }

    private static void releaseText() {
        java.util.Arrays.fill(LABEL_TEXT, null);
        countText = null;
    }

    private static void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui || client.player == null || client.player.isSpectator()) return;
        Font font = client.font;
        int guiScale = Math.max(1, (int) Math.round(client.getWindow().getGuiScale()));
        int unitPixels = AmmoHudLayout.unitPixels(client.getWindow().getHeight(), guiScale);
        if (client.player.getMainHandItem().getItem() == EquipmentRegistry.HEALTHSHOT) {
            renderCount(graphics, client, font, guiScale, unitPixels);
        }

        int kind = NONE;
        float progress = 0;
        PlantedC4Entity bomb = EquipmentClient.defusingBomb();
        if (bomb != null) {
            kind = DEFUSE;
            progress = bomb.getDefuseProgress();
        } else if (useKind != NONE) {
            kind = useKind;
            float elapsed = (System.nanoTime() - useStamp) / 1e9f;
            progress = Math.min(1, useProgress + (useSeconds > 0 ? elapsed / useSeconds : 0));
        }
        if (kind != NONE) renderProgress(graphics, font, kind, progress, (double) unitPixels / guiScale);
    }

    private static void renderCount(GuiGraphics graphics, Minecraft client, Font font, int guiScale, int unitPixels) {
        int count = client.player.getInventory().countItem(EquipmentRegistry.HEALTHSHOT);
        String now = COUNT_TEXT.of(count);
        if (now != countText) {
            countText = now;
            countWidth = font.width(now);
        }
        boolean offhandRight = client.player.getMainArm() == HumanoidArm.LEFT
                && !client.player.getOffhandItem().isEmpty();
        AmmoHudLayout.Placement box = PLACEMENT.place(graphics.guiWidth(), graphics.guiHeight(), guiScale,
                unitPixels, CROSS_SIZE, countWidth, 0, offhandRight);
        graphics.pose().pushPose();
        graphics.pose().translate(box.x(), box.y(), 0);
        graphics.pose().scale((float) box.scale(), (float) box.scale(), 1);
        graphics.fill(0, 0, box.width(), box.height(), 0x40000000);
        int rowHeight = box.height() - 2 * AmmoHudLayout.PADDING;
        int crossY = box.rowY() + (rowHeight - CROSS_SIZE) / 2;
        int bar = CROSS_SIZE / 3;
        graphics.fill(box.iconX() + bar, crossY, box.iconX() + 2 * bar, crossY + CROSS_SIZE, 0xFFFFFFFF);
        graphics.fill(box.iconX(), crossY + bar, box.iconX() + CROSS_SIZE, crossY + 2 * bar, 0xFFFFFFFF);
        graphics.fill(box.dividerX(), box.rowY() + 1, box.dividerX() + 1, box.rowY() + rowHeight - 1, 0xA0FFFFFF);
        int digits = AmmoHudLayout.DIGIT_HEIGHT * AmmoHudLayout.MAGAZINE_SCALE;
        graphics.pose().pushPose();
        graphics.pose().translate(box.magazineX(), box.rowY() + (rowHeight - digits) / 2, 0);
        graphics.pose().scale(AmmoHudLayout.MAGAZINE_SCALE, AmmoHudLayout.MAGAZINE_SCALE, 1);
        graphics.drawString(font, countText, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
        graphics.pose().popPose();
    }

    private static void renderProgress(GuiGraphics graphics, Font font, int kind, float progress, double scale) {
        if (LABEL_TEXT[kind] == null) {
            LABEL_TEXT[kind] = Language.getInstance().getVisualOrder(
                    Component.translatableWithFallback(LABEL_KEYS[kind], LABEL_FALLBACKS[kind]));
            LABEL_WIDTH[kind] = font.width(LABEL_TEXT[kind]);
        }
        int width = Math.max(BAR_WIDTH, LABEL_WIDTH[kind]) + 2 * AmmoHudLayout.PADDING;
        int height = AmmoHudLayout.PADDING + AmmoHudLayout.FONT_HEIGHT + 2 + BAR_HEIGHT + AmmoHudLayout.PADDING;
        // Centred below the crosshair, clear of the hotbar.
        double x = Math.floor(graphics.guiWidth() / 2.0 - width * scale / 2);
        double y = Math.floor(graphics.guiHeight() * 0.62);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale((float) scale, (float) scale, 1);
        graphics.fill(0, 0, width, height, 0x40000000);
        graphics.drawString(font, LABEL_TEXT[kind], (width - LABEL_WIDTH[kind]) / 2, AmmoHudLayout.PADDING,
                0xFFFFFFFF, true);
        int barX = (width - BAR_WIDTH) / 2;
        int barY = AmmoHudLayout.PADDING + AmmoHudLayout.FONT_HEIGHT + 2;
        graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, 0x60FFFFFF);
        int filled = Math.round(BAR_WIDTH * Math.max(0, Math.min(1, progress)));
        if (filled > 0) graphics.fill(barX, barY, barX + filled, barY + BAR_HEIGHT, 0xFFFFFFFF);
        graphics.pose().popPose();
    }
}
