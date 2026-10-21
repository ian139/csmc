package dev.csmc.surf;

import dev.csmc.CsmcMod;
import dev.csmc.movement.geometry.MovementGeometry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** Surf ramp blocks, their analytic collision and surf map import. */
public final class Surf implements ModInitializer {
    public static final SurfRampBlock SURF_RAMP = Registry.register(BuiltInRegistries.BLOCK, CsmcMod.id("surf_ramp"),
            new SurfRampBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.STONE)
                    .noOcclusion()
                    .dynamicShape()
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)
                    .isRedstoneConductor((state, level, pos) -> false)
                    .pushReaction(PushReaction.BLOCK)));

    public static final BlockEntityType<SurfRampBlockEntity> SURF_RAMP_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, CsmcMod.id("surf_ramp"),
            BlockEntityType.Builder.of(SurfRampBlockEntity::new, SURF_RAMP).build(null));

    public static final SurfRampItem SURF_RAMP_ITEM = Registry.register(BuiltInRegistries.ITEM,
            CsmcMod.id("surf_ramp"), new SurfRampItem(SURF_RAMP, new Item.Properties()));

    @Override
    public void onInitialize() {
        MovementGeometry.register(SurfRampGeometryProvider.INSTANCE);
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> entries.accept(SURF_RAMP_ITEM));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SurfCommands.register(dispatcher, registryAccess));
        dev.csmc.surf.importer.SurfImport.initialize();
    }
}
