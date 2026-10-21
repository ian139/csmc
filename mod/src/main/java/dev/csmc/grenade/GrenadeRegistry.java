package dev.csmc.grenade;

import dev.csmc.CsmcMod;
import dev.csmc.item.GrenadeItem;
import dev.csmc.network.GrenadeFlashPayload;
import dev.csmc.network.GrenadeSoundPayload;
import dev.csmc.network.GrenadeThrowPayload;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * Grenade items, the in-flight {@link GrenadeProjectile} and the persistent smoke/fire {@link GrenadeCloud}.
 * Registration happens on class load; {@link #initialize()} (called once by mod init after
 * {@code Ballistics.register()}) forces it and registers the S2C payload types.
 *
 * <p>All state lives in entities (saved with their chunk, synced by vanilla tracking), so there is no static
 * per-world state to clean up on server stop or world unload. Entities in non-ticking chunks do not tick, so
 * fuses, smoke/fire ages and decoy timers pause while their chunk is unloaded and resume when it reloads.
 */
public final class GrenadeRegistry {
    /** Grenade items keyed by {@link GrenadeKind#id()}, in {@link GrenadeKind} order. Immutable. */
    public static final Map<String, GrenadeItem> ITEMS = registerItems();

    public static final EntityType<GrenadeProjectile> PROJECTILE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, CsmcMod.id("grenade"),
            EntityType.Builder.<GrenadeProjectile>of(GrenadeProjectile::new, MobCategory.MISC)
                    .sized(GrenadeTuning.PROJECTILE_SIZE, GrenadeTuning.PROJECTILE_SIZE)
                    .clientTrackingRange(4)
                    .updateInterval(1)
                    .build("csmc:grenade"));

    public static final EntityType<GrenadeCloud> CLOUD = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, CsmcMod.id("grenade_cloud"),
            EntityType.Builder.<GrenadeCloud>of(GrenadeCloud::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .fireImmune()
                    .clientTrackingRange(10)
                    .updateInterval(10)
                    .build("csmc:grenade_cloud"));

    private static boolean initialized;

    private GrenadeRegistry() {}

    private static Map<String, GrenadeItem> registerItems() {
        Map<String, GrenadeItem> items = new LinkedHashMap<>();
        for (GrenadeKind kind : GrenadeKind.values()) {
            items.put(kind.id(), Registry.register(BuiltInRegistries.ITEM, CsmcMod.id(kind.id()), new GrenadeItem(kind)));
        }
        return Collections.unmodifiableMap(items);
    }

    public static GrenadeItem item(GrenadeKind kind) {
        return ITEMS.get(kind.id());
    }

    /** Registers payload types and the throw receiver. Call once from mod init. */
    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        PayloadTypeRegistry.playS2C().register(GrenadeFlashPayload.TYPE, GrenadeFlashPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(GrenadeSoundPayload.TYPE, GrenadeSoundPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(GrenadeThrowPayload.TYPE, GrenadeThrowPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GrenadeThrowPayload.TYPE,
                (payload, context) -> GrenadeThrow.handle(context.player(), payload));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> GrenadeThrow.forget(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GrenadeThrow.forget(handler.player));
    }
}
