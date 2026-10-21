package dev.csmc;

import com.mojang.serialization.Codec;
import dev.csmc.ballistics.Ballistics;
import dev.csmc.data.AmmoType;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.grenade.GrenadeRegistry;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.gloves.GloveSlot;
import dev.csmc.item.GloveItem;
import dev.csmc.item.GunItem;
import dev.csmc.item.KnifeItem;
import dev.csmc.item.KevlarArmor;
import dev.csmc.melee.KnifeStats;
import dev.csmc.melee.MeleeSystem;
import dev.csmc.network.GunActionPayload;
import dev.csmc.network.GunInspectPayload;
import dev.csmc.network.GunRecoilPayload;
import dev.csmc.network.GunSoundPayload;
import dev.csmc.network.KnifeActionPayload;
import dev.csmc.network.KnifeEventPayload;
import dev.csmc.network.ScopeStatePayload;
import dev.csmc.network.AmmoHudPayload;
import dev.csmc.server.GunSystem;
import dev.csmc.skin.gameplay.SkinGameplay;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CsmcMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("csmc");
    public static final DataComponentType<Integer> MAGAZINE = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, id("magazine"),
            DataComponentType.<Integer>builder().persistent(Codec.intRange(0, 10000))
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());
    public static final Item PISTOL_AMMO = item(AmmoType.PISTOL.itemPath(), new Item(new Item.Properties()));
    public static final Item RIFLE_AMMO = item(AmmoType.RIFLE.itemPath(), new Item(new Item.Properties()));
    public static final Item SNIPER_AMMO = item(AmmoType.SNIPER.itemPath(), new Item(new Item.Properties()));
    public static final Item SHOTGUN_SHELLS = item(AmmoType.SHOTGUN.itemPath(), new Item(new Item.Properties()));

    /**
     * Every CS2 firearm, by canonical id: the CS2 {@code weapons.vdata} key without {@code weapon_}
     * (so the M4A4 is {@code m4a1} and the M4A1-S is {@code m4a1_silencer}). Each id is both the item id
     * {@code csmc:<id>} and the weapon definition {@code data/csmc/weapons/<id>.json}. Static so the
     * item registry is identical on server and client.
     */
    public static final List<String> FIREARM_IDS = List.of(
            // Pistols
            "glock", "hkp2000", "usp_silencer", "p250", "fiveseven", "tec9", "cz75a", "deagle", "elite", "revolver",
            // SMGs
            "mac10", "mp9", "mp7", "mp5sd", "ump45", "p90", "bizon",
            // Rifles
            "famas", "galilar", "ak47", "m4a1", "m4a1_silencer", "aug", "sg556",
            // Sniper rifles
            "ssg08", "awp", "scar20", "g3sg1", "m14",
            // Shotguns
            "nova", "xm1014", "mag7", "sawedoff",
            // Machine guns and taser
            "m249", "negev", "taser");

    /** Registered gun items keyed by canonical firearm id, in {@link #FIREARM_IDS} order. Immutable. */
    public static final Map<String, GunItem> GUNS = registerGuns();
    public static final GunItem GLOCK = GUNS.get("glock");
    public static final GunItem AK47 = GUNS.get("ak47");

    /**
     * Every CS2 knife type, by canonical id (the vdata key without {@code weapon_}; excludes {@code knifegg},
     * {@code knife_twinblade} and {@code default}). Item {@code csmc:<id>}, stats {@code data/csmc/knives/<id>.json}.
     */
    public static final List<String> KNIFE_IDS = List.of(
            "knife", "knife_t",
            "bayonet", "knife_css", "knife_flip", "knife_gut", "knife_karambit", "knife_m9_bayonet", "knife_tactical",
            "knife_falchion", "knife_survival_bowie", "knife_butterfly", "knife_push",
            "knife_cord", "knife_canis", "knife_ursus", "knife_gypsy_jackknife", "knife_outdoor", "knife_stiletto",
            "knife_widowmaker", "knife_skeleton", "knife_kukri");

    /** Registered knife items keyed by canonical knife id, in {@link #KNIFE_IDS} order. Immutable. */
    public static final Map<String, KnifeItem> KNIVES = registerKnives();

    /**
     * Every CS2 glove type, by its CS2 item name. Item {@code csmc:<id>}, worn from the glove slot
     * ({@link GloveSlot}); its finish drives the first-person arms' gloves.
     */
    public static final List<String> GLOVE_IDS = List.of(
            "studded_bloodhound_gloves", "sporty_gloves", "slick_gloves", "leather_handwraps", "motorcycle_gloves",
            "specialist_gloves", "studded_hydra_gloves", "studded_brokenfang_gloves");

    /** Registered glove items keyed by glove id, in {@link #GLOVE_IDS} order. Immutable. */
    public static final Map<String, GloveItem> GLOVES = registerGloves();

    /** Every item that carries a finish, guns then knives then gloves, keyed by canonical weapon id. Immutable. */
    public static final Map<String, Item> SKINNABLE = skinnable();

    public static final CreativeModeTab FIREARMS_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, id("firearms"),
            FabricItemGroup.builder()
                    .title(Component.translatable("itemGroup.csmc.firearms"))
                    .icon(() -> new ItemStack(AK47))
                    .displayItems((parameters, output) -> {
                        GUNS.values().forEach(output::accept);
                        output.accept(PISTOL_AMMO);
                        output.accept(RIFLE_AMMO);
                        output.accept(SNIPER_AMMO);
                        output.accept(SHOTGUN_SHELLS);
                        output.accept(KevlarArmor.VEST);
                        output.accept(KevlarArmor.HELMET);
                        output.accept(SkinGameplay.WEAPON_SKIN);
                        output.accept(SkinGameplay.WEAPON_CASE);
                        output.accept(SkinGameplay.INVENTORY_WEAPON_CASE);
                        GrenadeRegistry.ITEMS.values().forEach(output::accept);
                        output.accept(EquipmentRegistry.HEALTHSHOT);
                        output.accept(EquipmentRegistry.C4);
                        output.accept(EquipmentRegistry.DEFUSE_KIT);
                    })
                    .build());

    public static final CreativeModeTab KNIVES_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, id("knives"),
            FabricItemGroup.builder()
                    .title(Component.translatable("itemGroup.csmc.knives"))
                    .icon(() -> new ItemStack(KNIVES.get("knife_karambit")))
                    .displayItems((parameters, output) -> KNIVES.values().forEach(output::accept))
                    .build());

    public static final CreativeModeTab GLOVES_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, id("gloves"),
            FabricItemGroup.builder()
                    .title(Component.translatable("itemGroup.csmc.gloves"))
                    .icon(() -> new ItemStack(GLOVES.get("sporty_gloves")))
                    .displayItems((parameters, output) -> GLOVES.values().forEach(output::accept))
                    .build());

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("csmc", path);
    }

    private static <T extends Item> T item(String name, T item) {
        return Registry.register(BuiltInRegistries.ITEM, id(name), item);
    }

    private static Map<String, GunItem> registerGuns() {
        Map<String, GunItem> guns = new LinkedHashMap<>();
        for (String name : FIREARM_IDS) {
            guns.put(name, item(name, new GunItem(id(name))));
        }
        return Collections.unmodifiableMap(guns);
    }

    private static Map<String, KnifeItem> registerKnives() {
        Map<String, KnifeItem> knives = new LinkedHashMap<>();
        for (String name : KNIFE_IDS) {
            knives.put(name, item(name, new KnifeItem(id(name))));
        }
        return Collections.unmodifiableMap(knives);
    }

    private static Map<String, GloveItem> registerGloves() {
        Map<String, GloveItem> gloves = new LinkedHashMap<>();
        for (String name : GLOVE_IDS) {
            gloves.put(name, item(name, new GloveItem(id(name))));
        }
        return Collections.unmodifiableMap(gloves);
    }

    private static Map<String, Item> skinnable() {
        Map<String, Item> items = new LinkedHashMap<>(GUNS);
        items.putAll(KNIVES);
        items.putAll(GLOVES);
        return Collections.unmodifiableMap(items);
    }

    public static Item ammo(String type) {
        AmmoType category = AmmoType.fromDefinition(type);
        if (category != null) {
            return switch (category) {
                case PISTOL -> PISTOL_AMMO;
                case RIFLE -> RIFLE_AMMO;
                case SNIPER -> SNIPER_AMMO;
                case SHOTGUN -> SHOTGUN_SHELLS;
            };
        }
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.get(id);
        }
        LOGGER.warn("[csmc] Unknown ammo type '{}'; using rifle ammunition", type);
        return RIFLE_AMMO;
    }

    @Override
    public void onInitialize() {
        KevlarArmor.init();
        SkinGameplay.initialize();
        WeaponRegistry.register();
        Ballistics.register();
        GrenadeRegistry.initialize();
        EquipmentRegistry.initialize();
        GunSystem.register();
        PayloadTypeRegistry.playC2S().register(GunActionPayload.TYPE, GunActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(GunRecoilPayload.TYPE, GunRecoilPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(GunSoundPayload.TYPE, GunSoundPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(GunInspectPayload.TYPE, GunInspectPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ScopeStatePayload.TYPE, ScopeStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(AmmoHudPayload.TYPE, AmmoHudPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(KnifeActionPayload.TYPE, KnifeActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(KnifeEventPayload.TYPE, KnifeEventPayload.STREAM_CODEC);
        GloveSlot.register();
        KnifeStats.register(KNIFE_IDS);
        MeleeSystem.register();
        ServerPlayNetworking.registerGlobalReceiver(GunActionPayload.TYPE,
                (payload, context) -> GunSystem.handle(context.player(), payload.action()));
        ServerPlayNetworking.registerGlobalReceiver(KnifeActionPayload.TYPE,
                (payload, context) -> MeleeSystem.handle(context.player(), payload.action()));
    }
}
