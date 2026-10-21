package dev.csmc.equipment;

import dev.csmc.CsmcMod;
import dev.csmc.item.C4Item;
import dev.csmc.item.DefuseKitItem;
import dev.csmc.item.HealthshotItem;
import dev.csmc.network.EquipmentActionPayload;
import dev.csmc.network.EquipmentEventPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameRules;

public final class EquipmentRegistry {
    public static final HealthshotItem HEALTHSHOT = Registry.register(BuiltInRegistries.ITEM,
            CsmcMod.id("healthshot"), new HealthshotItem());
    public static final C4Item C4 = Registry.register(BuiltInRegistries.ITEM, CsmcMod.id("c4"), new C4Item());
    public static final DefuseKitItem DEFUSE_KIT = Registry.register(BuiltInRegistries.ITEM,
            CsmcMod.id("defuse_kit"), new DefuseKitItem());
    public static final EntityType<PlantedC4Entity> PLANTED_C4 = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            CsmcMod.id("planted_c4"), EntityType.Builder.<PlantedC4Entity>of(PlantedC4Entity::new, MobCategory.MISC)
                    .sized(0.5F, 0.2F).fireImmune().clientTrackingRange(8).updateInterval(1).build("csmc:planted_c4"));
    public static final GameRules.Key<GameRules.BooleanValue> C4_BREAKING = GameRuleRegistry.register(
            "csmcC4Breaking", GameRules.Category.UPDATES, GameRuleFactory.createBooleanRule(false));

    private EquipmentRegistry() {}

    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(EquipmentActionPayload.TYPE, EquipmentActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(EquipmentEventPayload.TYPE, EquipmentEventPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(EquipmentActionPayload.TYPE,
                (payload, context) -> EquipmentSystem.handle(context.player(), payload));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerList().getPlayers()) { EquipmentSystem.tick(player); }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> EquipmentSystem.forget(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> EquipmentSystem.forget(handler.player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) { EquipmentSystem.cancel(player); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> EquipmentSystem.clear());
    }
}
