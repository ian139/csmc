package dev.csmc.data;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared ammunition categories for CS2 imports, no-cache placeholders and Minecraft item lookup. */
public enum AmmoType {
    PISTOL("pistol", "pistol_ammo"),
    RIFLE("rifle", "rifle_ammo"),
    SNIPER("sniper", "sniper_ammo"),
    SHOTGUN("shotgun", "shotgun_shells");

    private static final Logger LOGGER = LoggerFactory.getLogger(AmmoType.class);
    private final String key;
    private final String itemPath;

    AmmoType(String key, String itemPath) {
        this.key = key;
        this.itemPath = itemPath;
    }

    public String key() {
        return key;
    }

    public String itemPath() {
        return itemPath;
    }

    public static AmmoType fromWeaponType(String id, String weaponType) {
        return switch (weaponType) {
            // Zeus keeps a consumable pistol-ammo charge; this does not grant a CS2 reserve.
            case "WEAPONTYPE_PISTOL", "WEAPONTYPE_SUBMACHINEGUN", "WEAPONTYPE_TASER" -> PISTOL;
            case "WEAPONTYPE_RIFLE", "WEAPONTYPE_MACHINEGUN" -> RIFLE;
            case "WEAPONTYPE_SNIPER_RIFLE" -> SNIPER;
            case "WEAPONTYPE_SHOTGUN" -> SHOTGUN;
            default -> {
                LOGGER.warn("[csmc] Weapon {} has unknown CS2 weapon type '{}'; using rifle ammo", id, weaponType);
                yield RIFLE;
            }
        };
    }

    /** Authored class facts for no-cache play; M14 is WEAPONTYPE_RIFLE in weapons.vdata. */
    public static AmmoType forPlaceholder(String id) {
        return switch (id) {
            case "glock", "hkp2000", "usp_silencer", "p250", "fiveseven", "tec9", "cz75a", "deagle",
                    "elite", "revolver", "mac10", "mp9", "mp7", "mp5sd", "ump45", "p90", "bizon", "taser" -> PISTOL;
            case "ssg08", "awp", "scar20", "g3sg1" -> SNIPER;
            case "nova", "xm1014", "mag7", "sawedoff" -> SHOTGUN;
            default -> RIFLE;
        };
    }

    /** Weapon definitions accept either a category key or its registered Minecraft item id. */
    public static @Nullable AmmoType fromDefinition(String type) {
        return switch (type) {
            case "pistol", "csmc:pistol_ammo" -> PISTOL;
            case "rifle", "csmc:rifle_ammo" -> RIFLE;
            case "sniper", "csmc:sniper_ammo" -> SNIPER;
            case "shotgun", "csmc:shotgun_shells" -> SHOTGUN;
            default -> null;
        };
    }
}
