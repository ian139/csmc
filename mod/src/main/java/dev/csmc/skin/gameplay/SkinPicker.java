package dev.csmc.skin.gameplay;

import dev.csmc.item.SkinnableWeapon;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Server side of the skin picker: an operator tool that writes a skin straight onto the held gun or knife, replacing any
 * previous one without returning it as an attachment. Validation mirrors {@code /csmc skin}.
 */
public final class SkinPicker {
    /** Minimum game ticks between evaluated requests from one player. */
    public static final int MIN_INTERVAL_TICKS = 5;
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    public enum Outcome {
        APPLIED("commands.csmc.skins.applied", true),
        REMOVED("commands.csmc.skins.removed", true),
        NOT_OPERATOR("commands.csmc.skins.not_operator", false),
        NOT_GUN("commands.csmc.skin.requires_gun", false),
        INVALID_ID("commands.csmc.skins.invalid_id", false),
        UNSUPPORTED("commands.csmc.skins.unsupported", false),
        INVALID_WEAR("commands.csmc.skin.invalid_wear", false),
        INVALID_SEED("commands.csmc.skin.invalid_seed", false),
        RATE_LIMITED("commands.csmc.skins.rate_limited", false);

        public final String key;
        public final boolean success;

        Outcome(String key, boolean success) {
            this.key = key;
            this.success = success;
        }
    }

    private SkinPicker() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(SkinApplyPayload.TYPE, SkinApplyPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SkinPickerPayload.TYPE, SkinPickerPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SkinApplyPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Outcome outcome = handle(player, payload, SkinCatalog.kits());
            context.responseSender().sendPacket(new SkinPickerPayload(
                    Optional.of(Component.translatable(outcome.key)), outcome.success));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> LAST_REQUEST.remove(handler.player.getUUID())));
    }

    /** Rate-limited, permission-checked entry point for one request (server thread). */
    public static Outcome handle(ServerPlayer player, SkinApplyPayload request, List<SkinCatalog.Kit> kits) {
        long now = player.serverLevel().getGameTime();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL_TICKS && now >= last) return Outcome.RATE_LIMITED;
        LAST_REQUEST.put(player.getUUID(), now);
        return apply(player, player.hasPermissions(2), kits, request.paintKit(), request.wear(), request.seed());
    }

    /**
     * Validates and applies one request to the main-hand gun in place. Known kits must support the weapon; numeric
     * kits missing from {@code kits} are applied as-is, like {@code /csmc skin}. Kit 0 clears the skin.
     */
    public static Outcome apply(ServerPlayer player, boolean operator, List<SkinCatalog.Kit> kits,
                                int paintKit, float wear, int seed) {
        if (!operator) return Outcome.NOT_OPERATOR;
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof SkinnableWeapon gun)) return Outcome.NOT_GUN;
        if (paintKit < 0) return Outcome.INVALID_ID;
        if (paintKit == 0) {
            held.remove(SkinGameplay.SKIN);
            held.remove(SkinGameplay.SKIN_WEAPON);
            return Outcome.REMOVED;
        }
        String weapon = gun.weaponId().getPath();
        for (SkinCatalog.Kit kit : kits) {
            if (kit.paintKit() == paintKit && !kit.supports(weapon)) return Outcome.UNSUPPORTED;
        }
        if (!Float.isFinite(wear) || wear < 0 || wear > 1) return Outcome.INVALID_WEAR;
        if (seed < 0 || seed > 1000) return Outcome.INVALID_SEED;
        held.set(SkinGameplay.SKIN, new SkinData(paintKit, wear, seed));
        held.set(SkinGameplay.SKIN_WEAPON, weapon);
        return Outcome.APPLIED;
    }
}
