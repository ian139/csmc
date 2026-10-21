package dev.csmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.csmc.CsmcMod;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.item.SkinnableWeapon;
import net.minecraft.world.item.Item;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinNames;
import dev.csmc.skin.gameplay.CaseScroll;
import dev.csmc.skin.gameplay.SkinApplyPayload;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.SkinPickerFilter;
import dev.csmc.skin.gameplay.SkinPickerPayload;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Operator skin picker for the held gun. Tiles show every kit the local catalog makes available for the weapon, plus
 * "no skin". The grid fills the space left of the preview panel, but at most {@link #MAX_VISIBLE_TILES} tiles are on
 * screen; only those and one row ahead request 256 px skin thumbnails, within what the GUI thumbnail pool has left (see
 * {@link CaseScroll#prefetchBudget}). The large preview follows the wear/seed controls after they settle for
 * {@link #PREVIEW_SETTLE_MILLIS}, so dragging the slider does not churn the pool. Apply asks the server, which
 * re-validates; non-operators get a read-only view.
 */
public final class SkinPickerScreen extends Screen {
    /** Smallest tile; tiles grow to fill the grid area when the pool budget, not space, limits the grid. */
    private static final int MIN_TILE_WIDTH = 58;
    private static final int MIN_TILE_HEIGHT = 50;
    private static final int MAX_TILE_WIDTH = 96;
    private static final int GAP = 3;
    /**
     * Grid budget: well within the GUI thumbnail pool, leaving room for the large preview, the held gun's applied skin
     * and prefetch (prefetch never exceeds what is left, see {@link CaseScroll#prefetchBudget}).
     */
    private static final int MAX_VISIBLE_TILES = 28;
    private static final int PREVIEW_SCALE = 4;
    private static final int MARGIN = 6;
    private static final int TOP = 18;
    private static final int GRID_TOP = 38;
    private static final int SWATCH = 12;
    private static final int SCROLLBAR = 5;
    private static final long PREVIEW_SETTLE_MILLIS = 250;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA0A0A0;
    private static final String ELLIPSIS = "…";

    private static KeyMapping openKey;

    /** Held gun, knife or glove item. */
    private final Item gun;
    private final String weapon;
    /** Knife and glove finishes all read as gold ★, as in cases. */
    private final boolean knife;
    private final List<SkinCatalog.Kit> allKits = SkinCatalog.kits();
    private final ItemStack plainGun;
    private final ItemStack placeholder = new ItemStack(SkinGameplay.WEAPON_SKIN);
    private final Component loading = Component.translatable("screen.csmc.case_opening.loading");
    private final Component noSkin = Component.translatable("screen.csmc.skin_picker.none");
    private final Component readOnly = Component.translatable("screen.csmc.skin_picker.read_only");
    private final Component empty = Component.translatable("screen.csmc.skin_picker.empty");
    private final Component[] tierNames = new Component[SkinCatalog.RARITY_TIERS.size()];
    private final RandomSource random = RandomSource.create();

    private String query = "";
    private int tierMask = SkinPickerFilter.ALL_TIERS;
    /** Tile 0 is "no skin"; tile {@code i > 0} is {@code kits.get(i - 1)}. Per-tile caches are rebuilt on filtering. */
    private List<SkinCatalog.Kit> kits = List.of();
    private SkinData[] tileSkins = new SkinData[1];
    private ItemStack[] tileStacks = new ItemStack[1];
    private String[] tileLabels = new String[1];
    private Component[] tileTooltips = new Component[1];
    private int scrollRow;
    private int columns = 1;
    private int rows = 1;
    private int gridLeft;
    private int gridWidth;
    private int panelLeft;
    private int panelWidth;
    private int swatchLeft;

    /** Selected paint kit (0 = no skin) and its catalog entry, if any. */
    private int selectedKit;
    private SkinCatalog.Kit selected;
    private float wear;
    private int seed;
    private int tileWidth = MIN_TILE_WIDTH;
    private int tileHeight = MIN_TILE_HEIGHT;
    private int pitchX = MIN_TILE_WIDTH + GAP;
    private int pitchY = MIN_TILE_HEIGHT + GAP;
    private int iconScale = 2;
    private SkinData shownPreview;
    private ItemStack previewStack;
    private long controlsChangedMillis;
    private boolean previewDirty;
    private Component selectedName;

    private SkinData lastApplied;
    private List<FormattedCharSequence> appliedLines;
    private List<FormattedCharSequence> statusLines;
    private List<FormattedCharSequence> readOnlyLines;
    private int statusColor = MUTED;

    private EditBox search;
    private EditBox seedBox;
    private WearSlider wearSlider;
    private Button apply;

    private SkinPickerScreen(Item gun, SkinData current) {
        super(Component.translatable("screen.csmc.skin_picker"));
        this.gun = gun;
        this.weapon = ((SkinnableWeapon) gun).weaponId().getPath();
        this.knife = CsmcMod.KNIVES.containsKey(weapon) || CsmcMod.GLOVES.containsKey(weapon);
        this.plainGun = new ItemStack(gun);
        for (int tier = 0; tier < tierNames.length; tier++) {
            tierNames[tier] = Component.translatable("rarity.csmc." + SkinCatalog.RARITY_TIERS.get(tier))
                    .withColor(CaseOpeningScreen.tierColor(tier) & 0xFFFFFF);
        }
        CachedGunRendering.requestLoad(weapon);
        if (current != null) {
            select(current.paintKit());
            wear = current.wear();
            seed = current.seed();
        } else {
            select(0);
            seed = random.nextInt(1001);
        }
        refilter();
        updatePreviewTarget();
    }

    public static void register() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.csmc.skin_picker",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.csmc"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen == null) open(client);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(SkinPickerPayload.TYPE, (payload, context) -> {
            Minecraft client = context.client();
            if (payload.message().isEmpty()) {
                if (client.screen == null || client.screen instanceof ChatScreen) open(client);
            } else if (client.screen instanceof SkinPickerScreen picker) {
                picker.setStatus(payload.message().get(), payload.success() ? 0xFF7FD67F : 0xFFFF7070);
            } else if (client.player != null) {
                client.player.displayClientMessage(payload.message().get(), false);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (client.screen instanceof SkinPickerScreen) client.setScreen(null);
        }));
    }

    /** Opens the picker for the main-hand gun or knife, or explains that one is needed. */
    static void open(Minecraft client) {
        if (client.player == null) return;
        ItemStack held = client.player.getMainHandItem();
        if (!(held.getItem() instanceof SkinnableWeapon)) {
            client.player.displayClientMessage(Component.translatable("commands.csmc.skin.requires_gun"), true);
            return;
        }
        client.setScreen(new SkinPickerScreen(held.getItem(), held.get(SkinGameplay.SKIN)));
    }

    private boolean operator() {
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    @Override
    protected void init() {
        panelWidth = Mth.clamp(width / 4, 112, 150);
        panelLeft = width - panelWidth - MARGIN;
        gridWidth = Math.max(MIN_TILE_WIDTH + GAP, panelLeft - MARGIN * 3);
        int gridHeight = height - GRID_TOP - MARGIN;
        int fitColumns = Math.max(1, (gridWidth - SCROLLBAR + GAP) / (MIN_TILE_WIDTH + GAP));
        int fitRows = Math.max(1, (gridHeight + GAP) / (MIN_TILE_HEIGHT + GAP));
        // Most tiles within the pool budget; among equal counts, the squarest grid (ties favour columns).
        columns = 1;
        rows = 1;
        for (int c = Math.min(fitColumns, MAX_VISIBLE_TILES); c >= 1; c--) {
            int r = Math.min(fitRows, MAX_VISIBLE_TILES / c);
            int count = c * r;
            if (count > columns * rows || count == columns * rows && Math.abs(c - r) < Math.abs(columns - rows)) {
                columns = c;
                rows = r;
            }
        }
        // Grow tiles (keeping their aspect) into the space the chosen grid leaves.
        float grow = Math.min((gridWidth - SCROLLBAR + GAP) / (float) (columns * (MIN_TILE_WIDTH + GAP)),
                (gridHeight + GAP) / (float) (rows * (MIN_TILE_HEIGHT + GAP)));
        tileWidth = Mth.clamp((int) ((MIN_TILE_WIDTH + GAP) * grow) - GAP, MIN_TILE_WIDTH, MAX_TILE_WIDTH);
        tileHeight = MIN_TILE_HEIGHT * tileWidth / MIN_TILE_WIDTH;
        pitchX = tileWidth + GAP;
        pitchY = tileHeight + GAP;
        iconScale = tileHeight >= 70 ? 3 : 2;
        gridLeft = MARGIN + (gridWidth - (columns * pitchX - GAP + SCROLLBAR)) / 2;
        tileLabels = new String[tileLabels.length];
        clampScroll();

        int swatchesWidth = tierNames.length * (SWATCH + 2) - 2;
        swatchLeft = MARGIN + gridWidth - swatchesWidth;
        search = new EditBox(font, MARGIN, TOP, Math.max(40, swatchLeft - MARGIN - 6), 14,
                Component.translatable("screen.csmc.skin_picker.search"));
        search.setHint(Component.translatable("screen.csmc.skin_picker.search"));
        search.setValue(query);
        search.setResponder(value -> {
            if (!value.equals(query)) {
                query = value;
                refilter();
            }
        });
        addRenderableWidget(search);

        int previewBottom = TOP + 16 * PREVIEW_SCALE + 6;
        int y = previewBottom + 14;
        wearSlider = addRenderableWidget(new WearSlider(panelLeft, y, panelWidth, 20));
        seedBox = new EditBox(font, panelLeft, y + 24, panelWidth - 52, 20, Component.translatable("screen.csmc.skin_picker.seed"));
        seedBox.setMaxLength(4);
        seedBox.setFilter(value -> value.chars().allMatch(c -> c >= '0' && c <= '9'));
        seedBox.setValue(seed < 0 ? "" : Integer.toString(seed));
        seedBox.setResponder(value -> {
            seed = value.isEmpty() ? -1 : Integer.parseInt(value);
            controlsChanged();
        });
        addRenderableWidget(seedBox);
        addRenderableWidget(Button.builder(Component.translatable("screen.csmc.skin_picker.randomize"), button ->
                seedBox.setValue(Integer.toString(random.nextInt(1001))))
                .bounds(panelLeft + panelWidth - 48, y + 24, 48, 20).build());
        apply = addRenderableWidget(Button.builder(Component.translatable("screen.csmc.skin_picker.apply"), button -> sendApply())
                .bounds(panelLeft, y + 48, panelWidth, 20).build());
        appliedLines = null;
        readOnlyLines = null;
        updateControls();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.player == null || minecraft.player.getMainHandItem().getItem() != gun) {
            onClose();
            return;
        }
        // Operator status can change while the picker is open.
        refreshApply();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, MARGIN, 6, TEXT);
        if (previewDirty && Util.getMillis() - controlsChangedMillis >= PREVIEW_SETTLE_MILLIS) {
            previewDirty = false;
            updatePreviewTarget();
        }
        SkinData applied = minecraft.player.getMainHandItem().get(SkinGameplay.SKIN);
        if (appliedLines == null || !Objects.equals(applied, lastApplied)) {
            lastApplied = applied;
            appliedLines = font.split(applied == null ? Component.translatable("screen.csmc.skin_picker.applied_none")
                    : Component.translatable("screen.csmc.skin_picker.applied", kitName(applied.paintKit()),
                    applied.wearTier(), String.format("%.4f", applied.wear()), applied.seed()), panelWidth);
        }

        int hoveredSwatch = renderSwatches(graphics, mouseX, mouseY);
        int total = kits.size() + 1;
        int first = scrollRow * columns;
        int last = Math.min(total, first + rows * columns);
        int hoveredTile = tileAt(mouseX, mouseY);
        for (int i = first; i < last; i++) {
            int slot = i - first;
            renderTile(graphics, i, gridLeft + slot % columns * pitchX, GRID_TOP + slot / columns * pitchY, i == hoveredTile);
        }
        if (kits.isEmpty()) graphics.drawString(font, empty, gridLeft, GRID_TOP + pitchY + 4, MUTED);
        // Reserve every on-screen variant: visible kit tiles, the large preview and the held gun's applied skin.
        int onScreen = SkinPickerFilter.distinctVariants(tileSkins, Math.max(first, 1), last, shownPreview, lastApplied);
        int budget = CaseScroll.prefetchBudget(onScreen, CachedGunRendering.SKIN_VARIANT_CAPACITY, columns);
        for (int i = last; i < Math.min(total, last + budget); i++) CachedGunRendering.preview(weapon, tileSkin(i));
        if (total > rows * columns) renderScrollbar(graphics, total);

        renderPanel(graphics);
        if (hoveredTile >= 0) {
            graphics.renderTooltip(font, tileTooltip(hoveredTile), mouseX, mouseY);
        } else if (hoveredSwatch >= 0) {
            graphics.renderTooltip(font, tierNames[hoveredSwatch], mouseX, mouseY);
        }
    }

    /** Colour swatch per rarity tier; returns the hovered tier or -1. */
    private int renderSwatches(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = -1;
        int y = TOP + 1;
        for (int tier = 0; tier < tierNames.length; tier++) {
            int x = swatchLeft + tier * (SWATCH + 2);
            boolean on = (tierMask & SkinPickerFilter.bit(tier)) != 0;
            int color = CaseOpeningScreen.tierColor(tier);
            graphics.fill(x, y, x + SWATCH, y + SWATCH, on ? color : (color & 0x00FFFFFF) | 0x40000000);
            boolean hover = mouseX >= x && mouseX < x + SWATCH && mouseY >= y && mouseY < y + SWATCH;
            if (hover) hovered = tier;
            if (on && tierMask != SkinPickerFilter.ALL_TIERS || hover) {
                graphics.renderOutline(x - 1, y - 1, SWATCH + 2, SWATCH + 2, 0xFFFFFFFF);
            }
        }
        return hovered;
    }

    private int swatchAt(double mouseX, double mouseY) {
        int y = TOP + 1;
        if (mouseY < y || mouseY >= y + SWATCH || mouseX < swatchLeft) return -1;
        int tier = (int) ((mouseX - swatchLeft) / (SWATCH + 2));
        if (tier >= tierNames.length || mouseX - swatchLeft - tier * (SWATCH + 2) >= SWATCH) return -1;
        return tier;
    }

    /** Tile index under the cursor, or -1. */
    private int tileAt(double mouseX, double mouseY) {
        int column = (int) Math.floor((mouseX - gridLeft) / pitchX);
        int row = (int) Math.floor((mouseY - GRID_TOP) / pitchY);
        if (column < 0 || column >= columns || row < 0 || row >= rows) return -1;
        if (mouseX - gridLeft - column * pitchX >= tileWidth || mouseY - GRID_TOP - row * pitchY >= tileHeight) return -1;
        int index = (scrollRow + row) * columns + column;
        return index > kits.size() ? -1 : index;
    }

    private void renderTile(GuiGraphics graphics, int index, int x, int y, boolean hovered) {
        SkinCatalog.Kit kit = index == 0 ? null : kits.get(index - 1);
        int color = kit == null ? 0xFF606060 : CaseOpeningScreen.tierColor(SkinPickerFilter.pairTier(kit, knife));
        graphics.fill(x, y, x + tileWidth, y + tileHeight, hovered ? 0xFF3A3A42 : 0xFF2A2A30);
        graphics.fillGradient(x, y, x + tileWidth, y + tileHeight, 0x00000000, (color & 0x00FFFFFF) | 0x40000000);
        graphics.fill(x, y + tileHeight - 3, x + tileWidth, y + tileHeight, color);
        int paintKit = kit == null ? 0 : kit.paintKit();
        if (paintKit == selectedKit) graphics.renderOutline(x - 1, y - 1, tileWidth + 2, tileHeight + 2, 0xFFFFFFFF);
        if (paintKit == (lastApplied == null ? 0 : lastApplied.paintKit())) {
            graphics.fill(x + tileWidth - 6, y + 2, x + tileWidth - 2, y + 6, 0xFFFFD700);
        }
        int iconX = x + (tileWidth - 16 * iconScale) / 2;
        int iconY = y + (tileHeight - 13 - 16 * iconScale) / 2;
        if (kit == null) {
            renderGun(graphics, plainGun, CachedGunRendering.preview(weapon, null), iconX, iconY, iconScale);
        } else {
            CachedGunRendering.Preview state = CachedGunRendering.preview(weapon, tileSkin(index));
            ItemStack stack = tileStacks[index];
            if (stack == null && state == CachedGunRendering.Preview.READY) {
                stack = tileStacks[index] = new ItemStack(gun);
                stack.set(SkinGameplay.SKIN, tileSkin(index));
            }
            renderGun(graphics, stack, state, iconX, iconY, iconScale);
        }
        if (tileLabels[index] == null) tileLabels[index] = ellipsize(kit == null ? noSkin.getString() : SkinNames.displayName(kit), tileWidth - 4);
        graphics.drawString(font, tileLabels[index], x + 2, y + tileHeight - 13, TEXT);
    }

    private String ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width(ELLIPSIS)).stripTrailing() + ELLIPSIS;
    }

    private Component tileTooltip(int index) {
        Component tooltip = tileTooltips[index];
        if (tooltip == null) {
            SkinCatalog.Kit kit = index == 0 ? null : kits.get(index - 1);
            tooltip = tileTooltips[index] = kit == null ? noSkin : Component.literal(SkinNames.displayName(kit) + " · ")
                    .append(tierNames[SkinPickerFilter.pairTier(kit, knife)]);
        }
        return tooltip;
    }

    private void renderGun(GuiGraphics graphics, ItemStack stack, CachedGunRendering.Preview state, int x, int y, int scale) {
        boolean ready = state == CachedGunRendering.Preview.READY && stack != null;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.renderItem(ready ? stack : placeholder, 0, 0);
        graphics.pose().popPose();
        if (state == CachedGunRendering.Preview.LOADING) {
            graphics.drawCenteredString(font, loading, x + 8 * scale, y + 16 * scale - 9, 0xFF909090);
        }
    }

    private void renderScrollbar(GuiGraphics graphics, int total) {
        int totalRows = (total + columns - 1) / columns;
        int trackHeight = rows * pitchY - GAP;
        int x = gridLeft + columns * pitchX;
        int thumb = Math.max(12, trackHeight * rows / totalRows);
        int thumbTop = GRID_TOP + (trackHeight - thumb) * scrollRow / Math.max(1, totalRows - rows);
        graphics.fill(x, GRID_TOP, x + 3, GRID_TOP + trackHeight, 0x60000000);
        graphics.fill(x, thumbTop, x + 3, thumbTop + thumb, 0xFFA0A0A0);
    }

    private void renderPanel(GuiGraphics graphics) {
        int color = selected == null ? 0xFF606060 : CaseOpeningScreen.tierColor(SkinPickerFilter.pairTier(selected, knife));
        int previewSize = 16 * PREVIEW_SCALE;
        int bottom = TOP + previewSize + 6;
        graphics.fill(panelLeft, TOP, panelLeft + panelWidth, bottom, 0xC0101014);
        graphics.fill(panelLeft, bottom - 2, panelLeft + panelWidth, bottom, color);
        CachedGunRendering.Preview state = CachedGunRendering.preview(weapon, shownPreview);
        renderGun(graphics, previewStack, state, panelLeft + (panelWidth - previewSize) / 2, TOP + 2, PREVIEW_SCALE);
        graphics.drawCenteredString(font, selectedName, panelLeft + panelWidth / 2, bottom + 3, color);
        int y = drawLines(graphics, appliedLines, panelLeft, apply.getY() + 24, MUTED);
        if (!operator()) {
            if (readOnlyLines == null) readOnlyLines = font.split(readOnly, panelWidth);
            drawLines(graphics, readOnlyLines, panelLeft, y + 2, 0xFFFFB050);
        } else if (statusLines != null) {
            drawLines(graphics, statusLines, panelLeft, y + 2, statusColor);
        }
    }

    private int drawLines(GuiGraphics graphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (int i = 0; i < lines.size(); i++, y += font.lineHeight) graphics.drawString(font, lines.get(i), x, y, color);
        return y;
    }

    private void setStatus(Component message, int color) {
        statusLines = font.split(message, panelWidth);
        statusColor = color;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int swatch = swatchAt(mouseX, mouseY);
        if (swatch >= 0) {
            toggleTier(swatch);
            return true;
        }
        int index = tileAt(mouseX, mouseY);
        if (index < 0) return false;
        select(index == 0 ? 0 : kits.get(index - 1).paintKit());
        if (selected != null) wear = selected.wearMin();
        updateControls();
        controlsChanged();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < panelLeft && scrollY != 0) {
            scrollRow -= (int) Math.signum(scrollY);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void clampScroll() {
        int totalRows = (kits.size() + 1 + columns - 1) / columns;
        scrollRow = Mth.clamp(scrollRow, 0, Math.max(0, totalRows - rows));
    }

    private void select(int paintKit) {
        selectedKit = paintKit;
        selected = paintKit == 0 ? null : SkinCatalog.renderKit(paintKit);
        selectedName = paintKit == 0 ? noSkin : Component.literal(kitName(paintKit));
    }

    private String kitName(int paintKit) {
        SkinCatalog.Kit kit = SkinCatalog.renderKit(paintKit);
        return kit != null ? SkinNames.displayName(kit) : "#" + paintKit;
    }

    private void refilter() {
        kits = SkinPickerFilter.filter(allKits, weapon, knife, query, tierMask);
        int total = kits.size() + 1;
        tileSkins = new SkinData[total];
        tileStacks = new ItemStack[total];
        tileLabels = new String[total];
        tileTooltips = new Component[total];
        scrollRow = 0;
    }

    private void toggleTier(int tier) {
        int mask = tierMask ^ SkinPickerFilter.bit(tier);
        // From "all", a click isolates that tier; clearing the last tier restores all.
        if (tierMask == SkinPickerFilter.ALL_TIERS) mask = SkinPickerFilter.bit(tier);
        tierMask = mask == 0 ? SkinPickerFilter.ALL_TIERS : mask;
        refilter();
    }

    /** Kit tiles show their lowest wear with pattern seed 0, like case filler tiles. */
    private SkinData tileSkin(int index) {
        SkinData skin = tileSkins[index];
        if (skin == null) {
            SkinCatalog.Kit kit = kits.get(index - 1);
            skin = tileSkins[index] = new SkinData(kit.paintKit(), kit.wearMin(), 0);
        }
        return skin;
    }

    private boolean seedValid() {
        return seed >= 0 && seed <= 1000;
    }

    private void controlsChanged() {
        controlsChangedMillis = Util.getMillis();
        previewDirty = true;
        refreshApply();
    }

    private void updatePreviewTarget() {
        SkinData target = selectedKit == 0 ? null : new SkinData(selectedKit, wear, seedValid() ? seed : 0);
        if (previewStack != null && Objects.equals(target, shownPreview)) return;
        shownPreview = target;
        previewStack = new ItemStack(gun);
        if (target != null) previewStack.set(SkinGameplay.SKIN, target);
    }

    private void updateControls() {
        if (wearSlider != null) {
            wearSlider.set(wear);
            wearSlider.active = selectedKit != 0;
        }
        refreshApply();
    }

    private void refreshApply() {
        if (apply != null) apply.active = operator() && (selectedKit == 0 || seedValid());
    }

    private void sendApply() {
        if (!operator()) return;
        if (selectedKit != 0 && !seedValid()) return;
        ClientPlayNetworking.send(new SkinApplyPayload(selectedKit, selectedKit == 0 ? 0 : wear,
                selectedKit == 0 ? 0 : seed));
        setStatus(Component.translatable("screen.csmc.skin_picker.applying"), MUTED);
    }

    private final class WearSlider extends AbstractSliderButton {
        WearSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), wear);
            updateMessage();
        }

        void set(float value) {
            this.value = value;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float current = (float) value;
            setMessage(Component.translatable("screen.csmc.skin_picker.wear",
                    String.format("%.3f", current), SkinData.wearTier(current)));
        }

        @Override
        protected void applyValue() {
            wear = Mth.clamp((float) value, 0, 1);
            controlsChanged();
        }
    }
}
