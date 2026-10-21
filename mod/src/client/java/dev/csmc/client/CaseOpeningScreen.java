package dev.csmc.client;

import dev.csmc.CsmcMod;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinResolution;
import dev.csmc.skin.gameplay.CaseOpeningPayload;
import dev.csmc.skin.gameplay.CaseScroll;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.WeaponCaseItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Purely cosmetic CS-style reel. The rolled skin is already in the player's inventory, so closing early, pressing
 * Esc or disconnecting loses nothing. On open, the model and the 256 px GUI thumbnail of every tile are requested in
 * {@link CaseScroll#prefetchOrder}: the result, then outward from it. Those tiles scroll slowest, stay up during the
 * reveal, and so are the ones a cold load can still finish in time; early tiles flash past within a second. The
 * thumbnail pool holds a whole strip (see {@code SkinTextures.MAX_THUMBNAILS}) and is separate from in-hand
 * textures; on-screen tiles plus up to {@link #PREFETCH_TILES} more keep their variants fresh (see
 * {@link CaseScroll#prefetchBudget}). The reel holds still until the result's model and thumbnail are ready, or for at
 * most {@link #START_TIMEOUT_MILLIS}, so the reveal is never a placeholder when assets exist.
 */
public final class CaseOpeningScreen extends Screen {
    private static final double DURATION_SECONDS = 6.0;
    private static final int TILE_WIDTH = 100;
    private static final int TILE_HEIGHT = 76;
    private static final int TILE_GAP = 4;
    private static final int PITCH = TILE_WIDTH + TILE_GAP;
    private static final int ICON_SCALE = 3;
    private static final int PREFETCH_TILES = 6;
    private static final long START_TIMEOUT_MILLIS = 1500;
    /** The reveal stays up this long, then the reel closes by itself. */
    private static final long REVEAL_HOLD_MILLIS = 3000;

    private final CaseOpeningPayload payload;
    private final CaseScroll scroll;
    /** Per-tile caches, filled when a tile first comes near the screen; ItemStacks carry no GPU state. */
    private final SkinData[] skins;
    private final ItemStack[] guns;
    private final String[] weaponLabels;
    private final String[] skinLabels;
    /** Display tier per tile: bar colour, reveal rarity and reveal sound all read it. */
    private final int[] tiers;
    /** Neutral icon for tiles whose model is loading or missing. */
    private final ItemStack placeholder = new ItemStack(SkinGameplay.WEAPON_SKIN);
    private final Component loading = Component.translatable("screen.csmc.case_opening.loading");
    private final long openedMillis = Util.getMillis();
    /** When the scroll began; negative while waiting for the result's assets. */
    private long startMillis = -1;
    private Component revealName;
    private Component revealDetails;
    private int lastTile = Integer.MIN_VALUE;
    private boolean revealed;
    private long revealedMillis;
    private final Component dismiss = Component.translatable("screen.csmc.case_opening.dismiss");
    /** Indices of tiles that passed culling this frame; preallocated so counting variants never allocates. */
    private final int[] visibleTiles;
    private final CaseScroll.SameVariant sameVariant = this::sameVariant;

    public CaseOpeningScreen(CaseOpeningPayload payload, RandomSource random) {
        super(Component.translatable(payload.pool().openingTitle()));
        this.payload = payload;
        this.scroll = CaseScroll.landing(payload.landingIndex(), random.nextDouble() * 2 - 1, DURATION_SECONDS);
        int size = payload.strip().size();
        this.skins = new SkinData[size];
        this.guns = new ItemStack[size];
        this.weaponLabels = new String[size];
        this.skinLabels = new String[size];
        this.visibleTiles = new int[size];
        this.tiers = new int[size];
        for (int i = 0; i < size; i++) tiers[i] = tier(payload.strip().get(i));
        for (int i : CaseScroll.prefetchOrder(size, payload.landingIndex())) {
            String weapon = payload.strip().get(i).weapon();
            if (!CsmcMod.SKINNABLE.containsKey(weapon)) continue;
            // Repeat requests are map lookups; the loaders start each distinct model and thumbnail once, in this order.
            CachedGunRendering.requestLoad(weapon);
            CachedGunRendering.prefetchThumbnail(weapon, skin(i));
        }
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CaseOpeningPayload.TYPE, (payload, context) -> {
            Minecraft client = context.client();
            // Never displace another screen (an open inventory would strand its cursor stack); a newer case replaces
            // a reel that is still showing. The item is already granted, so skipping the animation loses nothing.
            if (client.screen == null || client.screen instanceof CaseOpeningScreen) {
                client.setScreen(new CaseOpeningScreen(payload, RandomSource.create()));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (client.screen instanceof CaseOpeningScreen) client.setScreen(null);
        }));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        long now = Util.getMillis();
        if (startMillis < 0 && (resultReady() || now - openedMillis >= START_TIMEOUT_MILLIS)) startMillis = now;
        double elapsed = startMillis < 0 ? 0 : (now - startMillis) / 1000.0;
        double position = scroll.position(elapsed);
        boolean finished = scroll.finished(elapsed);
        int tile = CaseScroll.tileAt(position);
        if (lastTile != Integer.MIN_VALUE && tile != lastTile) playUi(SoundEvents.NOTE_BLOCK_HAT.value(), 1.6f);
        lastTile = tile;

        int centerX = width / 2;
        int stripTop = height / 2 - TILE_HEIGHT / 2 - (finished ? 50 : 0);
        graphics.drawCenteredString(font, title, centerX, stripTop - 24, 0xFFFFFFFF);
        graphics.fill(0, stripTop - 6, width, stripTop + TILE_HEIGHT + 6, 0xC0101014);

        // Tile i spans [x, x + TILE_WIDTH) with x = centerX + (i - position) * PITCH; keep only tiles on screen.
        int size = payload.strip().size();
        int first = Math.max(0, (int) Math.floor(position - (double) (centerX + TILE_WIDTH) / PITCH));
        int last = Math.min(size - 1, (int) Math.ceil(position + (double) (width - centerX) / PITCH));
        int visibleCount = 0;
        for (int i = first; i <= last; i++) {
            int x = centerX + (int) Math.round((i - position) * PITCH);
            if (x + TILE_WIDTH <= 0 || x >= width) continue;
            renderTile(graphics, i, x, stripTop);
            visibleTiles[visibleCount++] = i;
        }
        int visibleVariants = CaseScroll.distinctVariants(visibleTiles, visibleCount, sameVariant);
        if (!finished) {
            int budget = CaseScroll.prefetchBudget(visibleVariants, CachedGunRendering.SKIN_VARIANT_CAPACITY, PREFETCH_TILES);
            for (int i = last + 1; i <= Math.min(size - 1, last + budget); i++) {
                CachedGunRendering.preview(payload.strip().get(i).weapon(), skin(i));
            }
        }

        // Centre marker.
        graphics.fill(centerX - 1, stripTop - 10, centerX + 1, stripTop + TILE_HEIGHT + 10, 0xFFFFD700);

        if (finished) {
            if (!revealed) {
                revealed = true;
                revealedMillis = now;
                playReveal(tiers[payload.landingIndex()]);
            }
            renderReveal(graphics, centerX, stripTop + TILE_HEIGHT + 16);
            graphics.drawCenteredString(font, dismiss, centerX, height - 20, 0xFFA0A0A0);
            if (now - revealedMillis >= REVEAL_HOLD_MILLIS) onClose();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (revealed) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (revealed) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Whether tiles {@code a} and {@code b} use the same skin texture variant. */
    private boolean sameVariant(int a, int b) {
        return skin(a).equals(skin(b)) && payload.strip().get(a).weapon().equals(payload.strip().get(b).weapon());
    }

    /** The result tile will render as its real skin, or never can (no model or no local skin inputs). */
    private boolean resultReady() {
        int index = payload.landingIndex();
        String weapon = payload.strip().get(index).weapon();
        if (!CsmcMod.SKINNABLE.containsKey(weapon)) return true;
        return switch (CachedGunRendering.preview(weapon, skin(index))) {
            case UNAVAILABLE -> true;
            case LOADING -> false;
            case READY -> CachedGunRendering.baseColorTexture(weapon, skin(index), SkinResolution.THUMBNAIL) != null;
        };
    }

    private void renderTile(GuiGraphics graphics, int index, int x, int top) {
        CaseOpeningPayload.Entry entry = payload.strip().get(index);
        int color = tierColor(tiers[index]);
        graphics.fill(x, top, x + TILE_WIDTH, top + TILE_HEIGHT, 0xFF2A2A30);
        graphics.fillGradient(x, top, x + TILE_WIDTH, top + TILE_HEIGHT, 0x00000000, (color & 0x00FFFFFF) | 0x40000000);
        graphics.fill(x, top + TILE_HEIGHT - 4, x + TILE_WIDTH, top + TILE_HEIGHT, color);

        int iconSize = 16 * ICON_SCALE;
        renderPreview(graphics, index, x + (TILE_WIDTH - iconSize) / 2, top + 2, ICON_SCALE);
        if (weaponLabels[index] == null) {
            weaponLabels[index] = font.substrByWidth(weaponName(entry.weapon()), TILE_WIDTH - 8).getString();
            skinLabels[index] = font.substrByWidth(Component.literal(entry.name()), TILE_WIDTH - 8).getString();
        }
        graphics.drawString(font, weaponLabels[index], x + 4, top + TILE_HEIGHT - 26, 0xFFCCCCCC);
        graphics.drawString(font, skinLabels[index], x + 4, top + TILE_HEIGHT - 15, 0xFFFFFFFF);
    }

    private void renderReveal(GuiGraphics graphics, int centerX, int top) {
        CaseOpeningPayload.Entry entry = payload.resultEntry();
        int tier = tiers[payload.landingIndex()];
        int color = tierColor(tier);
        if (revealName == null) {
            SkinData result = payload.result();
            revealName = Component.translatable("screen.csmc.case_opening.name", weaponName(entry.weapon()), entry.name());
            revealDetails = Component.translatable("screen.csmc.case_opening.result",
                    Component.translatable("rarity.csmc." + SkinCatalog.RARITY_TIERS.get(tier)),
                    result.wearTier(), String.format("%.4f", result.wear()), result.seed());
        }
        int panelWidth = 260;
        int left = centerX - panelWidth / 2;
        graphics.fill(left, top, left + panelWidth, top + 92, 0xE0101014);
        graphics.fill(left, top, left + panelWidth, top + 2, color);
        graphics.fill(left, top + 90, left + panelWidth, top + 92, color);
        renderPreview(graphics, payload.landingIndex(), centerX - 32, top + 4, 4);
        graphics.drawCenteredString(font, revealName, centerX, top + 64, color);
        graphics.drawCenteredString(font, revealDetails, centerX, top + 76, 0xFFAAAAAA);
    }

    /** Skinned gun once its model is ready; otherwise the neutral attachment icon, labelled while it loads. */
    private void renderPreview(GuiGraphics graphics, int index, int x, int y, int scale) {
        String weapon = payload.strip().get(index).weapon();
        Item gun = CsmcMod.SKINNABLE.get(weapon);
        CachedGunRendering.Preview state = gun == null ? CachedGunRendering.Preview.UNAVAILABLE
                : CachedGunRendering.preview(weapon, skin(index));
        if (state == CachedGunRendering.Preview.READY) {
            ItemStack stack = guns[index];
            if (stack == null) {
                stack = guns[index] = new ItemStack(gun);
                stack.set(SkinGameplay.SKIN, skin(index));
            }
            renderIcon(graphics, stack, x, y, scale);
            return;
        }
        renderIcon(graphics, placeholder, x, y, scale);
        if (state == CachedGunRendering.Preview.LOADING) {
            graphics.drawCenteredString(font, loading, x + 8 * scale, y + 16 * scale - 9, 0xFF909090);
        }
    }

    private void renderIcon(GuiGraphics graphics, ItemStack stack, int x, int y, int scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    /** The landing tile shows the actual roll; fillers show their kit at its lowest wear, pattern seed 0. */
    private SkinData skin(int index) {
        SkinData skin = skins[index];
        if (skin == null) {
            if (index == payload.landingIndex()) {
                skin = payload.result();
            } else {
                int paintKit = payload.strip().get(index).paintKit();
                SkinCatalog.Kit kit = SkinCatalog.renderKit(paintKit);
                skin = new SkinData(paintKit, kit != null ? kit.wearMin() : 0, 0);
            }
            skins[index] = skin;
        }
        return skin;
    }

    /** Knives read as CS2 rare specials: a ★ before the name. */
    private static Component weaponName(String weapon) {
        Item item = CsmcMod.SKINNABLE.get(weapon);
        if (item == null) return Component.literal(weapon);
        return CsmcMod.KNIVES.containsKey(weapon) ? Component.literal("★ ").append(item.getDescription()) : item.getDescription();
    }

    /**
     * The weapon/kit pair's tier, as cases, skin items and the picker show it: knives are always gold ★, guns use the
     * local kit's rarity. Falls back to the entry's tier when the kit is not in the local catalog.
     */
    private static int tier(CaseOpeningPayload.Entry entry) {
        SkinCatalog.Kit kit = SkinCatalog.renderKit(entry.paintKit());
        if (kit != null) return WeaponCaseItem.pairTier(kit, entry.weapon());
        return CsmcMod.KNIVES.containsKey(entry.weapon()) ? SkinCatalog.RARITY_TIERS.size() - 1 : entry.tier();
    }

    /** Shared with {@link SkinPickerScreen}. */
    static int tierColor(int tier) {
        return 0xFF000000 | SkinCatalog.tierColor(tier);
    }

    private void playReveal(int tier) {
        if (tier >= 5) playUi(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
        else if (tier >= 3) playUi(SoundEvents.PLAYER_LEVELUP, 1.0f);
        else playUi(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f);
    }

    private static void playUi(SoundEvent sound, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }
}
