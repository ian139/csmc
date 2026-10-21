package dev.csmc.client.viewmodel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Compact vanilla controls leave the live first-person gun visible on the right. */
public final class ViewmodelSettingsScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int PANEL_HEIGHT = 200;
    private final Screen parent;
    private final ViewmodelSettings settings = ViewmodelSettings.get();
    private final SettingSlider[] sliders = new SettingSlider[4];
    private final Component tip = Component.translatable("csmc.viewmodel.tip");
    private int panelTop;
    private int panelWidth;

    public ViewmodelSettingsScreen(Screen parent) {
        super(Component.translatable("csmc.viewmodel.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(216, Math.max(140, width / 2 - MARGIN));
        panelTop = Math.max(MARGIN, height - PANEL_HEIGHT - MARGIN);
        int x = MARGIN + 8;
        int sliderWidth = panelWidth - 16;
        int y = panelTop + 44;
        sliders[0] = addRenderableWidget(new SettingSlider(x, y, sliderWidth, "csmc.viewmodel.size",
                ViewmodelSettings.SCALE_MIN, ViewmodelSettings.SCALE_MAX, ViewmodelSettings.SCALE_STEP,
                () -> settings.scale, value -> settings.scale = (float) value));
        sliders[1] = addRenderableWidget(new SettingSlider(x, y + 24, sliderWidth, "csmc.viewmodel.x",
                ViewmodelSettings.OFFSET_X_MIN, ViewmodelSettings.OFFSET_X_MAX, ViewmodelSettings.OFFSET_STEP,
                () -> settings.offsetX, value -> settings.offsetX = (float) value));
        sliders[2] = addRenderableWidget(new SettingSlider(x, y + 48, sliderWidth, "csmc.viewmodel.y",
                ViewmodelSettings.OFFSET_Y_MIN, ViewmodelSettings.OFFSET_Y_MAX, ViewmodelSettings.OFFSET_STEP,
                () -> settings.offsetY, value -> settings.offsetY = (float) value));
        sliders[3] = addRenderableWidget(new SettingSlider(x, y + 72, sliderWidth, "csmc.viewmodel.z",
                ViewmodelSettings.OFFSET_Z_MIN, ViewmodelSettings.OFFSET_Z_MAX, ViewmodelSettings.OFFSET_STEP,
                () -> settings.offsetZ, value -> settings.offsetZ = (float) value));

        addRenderableWidget(net.minecraft.client.gui.components.CycleButton.onOffBuilder(settings.shine)
                .create(x, panelTop + 148, sliderWidth, 20, Component.translatable("csmc.viewmodel.shine"),
                        (button, value) -> settings.shine = value));
        int buttonWidth = (sliderWidth - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("csmc.viewmodel.reset"), button -> {
            settings.reset();
            for (SettingSlider slider : sliders) slider.refresh();
        }).bounds(x, panelTop + 172, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("csmc.viewmodel.done"), button -> onClose())
                .bounds(x + buttonWidth + 4, panelTop + 172, sliderWidth - buttonWidth - 4, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Do not call Screen's background: its blur/dim obscures the real held-gun preview.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(MARGIN, panelTop, MARGIN + panelWidth, panelTop + PANEL_HEIGHT, 0xC0101014);
        graphics.drawString(font, title, MARGIN + 8, panelTop + 10, 0xFFFFFFFF);
        graphics.drawString(font, tip, MARGIN + 8, panelTop + 26, 0xFFCCCCCC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    /** Runs on every close path, including disconnects and game exit, so settings are never lost. */
    @Override
    public void removed() {
        settings.save();
    }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String translationKey;
        private final double min;
        private final double max;
        private final double step;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        private SettingSlider(int x, int y, int width, String translationKey, double min, double max,
                              double step, DoubleSupplier getter, DoubleConsumer setter) {
            super(x, y, width, 20, Component.empty(), (getter.getAsDouble() - min) / (max - min));
            this.translationKey = translationKey;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
            setTooltip(Tooltip.create(Component.translatable(translationKey + ".tooltip")));
            updateMessage();
        }

        private void refresh() {
            value = Mth.clamp((getter.getAsDouble() - min) / (max - min), 0.0, 1.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(translationKey, String.format(Locale.ROOT, "%.2f", getter.getAsDouble())));
        }

        @Override
        protected void applyValue() {
            double snapped = min + Math.round(value * (max - min) / step) * step;
            double current = Mth.clamp(snapped, min, max);
            setter.accept(Math.abs(current) < step / 2 ? 0.0 : current);
            // Keep the unsnapped thumb value so keyboard arrows can accumulate sub-step movement.
        }
    }
}
