package dev.csmc.client.viewmodel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.CsmcMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import java.nio.file.StandardCopyOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/** Client-local viewmodel adjustments, applied only by the first-person gun render path. */
public final class ViewmodelSettings {
    public static final float SCALE_DEFAULT = 1.0F;
    public static final float SCALE_MIN = 0.5F;
    public static final float SCALE_MAX = 4.0F;
    public static final float SCALE_STEP = 0.05F;
    public static final float OFFSET_DEFAULT = 0.0F;
    public static final float OFFSET_X_MIN = -1.0F;
    public static final float OFFSET_X_MAX = 1.0F;
    public static final float OFFSET_Y_MIN = -1.0F;
    public static final float OFFSET_Y_MAX = 1.0F;
    public static final float OFFSET_Z_MIN = -1.5F;
    public static final float OFFSET_Z_MAX = 1.0F;
    public static final float OFFSET_STEP = 0.01F;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ViewmodelSettings INSTANCE = new ViewmodelSettings();
    private static boolean openRequested;

    public float scale = SCALE_DEFAULT;
    public float offsetX = OFFSET_DEFAULT;
    public float offsetY = OFFSET_DEFAULT;
    /** Blocks along the view axis; negative values move the weapon forward. */
    public float offsetZ = OFFSET_DEFAULT;
    /** CS-style specular shine on skinned and default weapons and knives; absent in older config files. */
    public boolean shine = true;

    private ViewmodelSettings() {}

    public static ViewmodelSettings get() {
        return INSTANCE;
    }

    public static void load() {
        INSTANCE.reset();
        INSTANCE.shine = true;
        Path file = configPath();
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            ViewmodelSettings loaded = GSON.fromJson(reader, ViewmodelSettings.class);
            if (loaded == null) return;
            loaded.clampValues();
            INSTANCE.scale = loaded.scale;
            INSTANCE.offsetX = loaded.offsetX;
            INSTANCE.offsetY = loaded.offsetY;
            INSTANCE.offsetZ = loaded.offsetZ;
            INSTANCE.shine = loaded.shine;
        } catch (IOException | RuntimeException e) {
            CsmcMod.LOGGER.warn("Cannot read {}; using default viewmodel settings", file, e);
        }
    }

    public void save() {
        clampValues();
        Path file = configPath();
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "csmc-viewmodel", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            CsmcMod.LOGGER.warn("Cannot save viewmodel settings to {}", file, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the unpublished temp file.
                }
            }
        }
    }

    public void reset() {
        scale = SCALE_DEFAULT;
        offsetX = OFFSET_DEFAULT;
        offsetY = OFFSET_DEFAULT;
        offsetZ = OFFSET_DEFAULT;
    }

    /** Global offset. Call before the weapon transform so per-weapon translations are not scaled by it. */
    public static void applyTranslation(PoseStack poses) {
        ViewmodelSettings settings = INSTANCE;
        if (settings.offsetX != OFFSET_DEFAULT || settings.offsetY != OFFSET_DEFAULT
                || settings.offsetZ != OFFSET_DEFAULT) {
            poses.translate(settings.offsetX, settings.offsetY, settings.offsetZ);
        }
    }

    /** Global size. Call after the weapon transform and before inspect/hand normalization. */
    public static void applyScale(PoseStack poses) {
        float scale = INSTANCE.scale;
        if (scale != SCALE_DEFAULT) poses.scale(scale, scale, scale);
    }

    public static void register() {
        load();
        KeyMapping openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.csmc.viewmodel_settings", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), "category.csmc"));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof OptionsScreen)) return;
            Screens.getButtons(screen).add(Button.builder(Component.translatable("csmc.viewmodel.options_button"),
                    button -> client.setScreen(new ViewmodelSettingsScreen(screen))).bounds(width - 104, 6, 100, 20).build());
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("csmcclient")
                        .then(ClientCommandManager.literal("viewmodel").executes(context -> {
                            // Open on the next tick, after command submission has closed the chat screen.
                            openRequested = true;
                            return 1;
                        }))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean requested = openRequested;
            openRequested = false;
            while (openKey.consumeClick()) requested = true;
            if (!requested || client.player == null
                    || client.screen instanceof ViewmodelSettingsScreen) return;
            Screen parent = client.screen instanceof ChatScreen ? null : client.screen;
            client.setScreen(new ViewmodelSettingsScreen(parent));
        });
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("csmc-viewmodel.json");
    }

    private void clampValues() {
        scale = clamp(scale, SCALE_MIN, SCALE_MAX, SCALE_DEFAULT);
        offsetX = clamp(offsetX, OFFSET_X_MIN, OFFSET_X_MAX, OFFSET_DEFAULT);
        offsetY = clamp(offsetY, OFFSET_Y_MIN, OFFSET_Y_MAX, OFFSET_DEFAULT);
        offsetZ = clamp(offsetZ, OFFSET_Z_MIN, OFFSET_Z_MAX, OFFSET_DEFAULT);
    }

    private static float clamp(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
