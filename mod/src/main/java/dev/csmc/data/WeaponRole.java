package dev.csmc.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Design role of every firearm (docs/balance.md). Roles follow CS2 weapon classes except that the
 * semi-automatic SCAR-20 and G3SG1 form {@link #MARKSMAN}: only the bolt-action AWP, SSG 08 and M14 (a slow
 * single-shot rifle in weapons.vdata) carry the one-shot {@link #SNIPER} role. Used by no-cache placeholders and
 * the balance harness.
 */
public enum WeaponRole {
    /** Sidearm: cheap ammo, fast draw, weak against armor. */
    PISTOL("glock", "hkp2000", "usp_silencer", "p250", "fiveseven", "tec9", "cz75a", "deagle", "elite", "revolver"),
    /** Close range and mobility: high fire rate, fast movement, steep falloff, weak against armor. */
    SMG("mac10", "mp9", "mp7", "mp5sd", "ump45", "p90", "bizon"),
    /** All-round: strong at every band, best at medium range, never best everywhere. */
    RIFLE("famas", "galilar", "ak47", "m4a1", "m4a1_silencer", "aug", "sg556"),
    /** One-shot, slow: kills unarmored targets with one hit at long range; slow cycle, slow movement, poor hip fire. */
    SNIPER("ssg08", "awp", "m14"),
    /** Semi-automatic long-range rifles: rapid precise fire, no one-shot body guarantee. */
    MARKSMAN("scar20", "g3sg1"),
    /** Close burst: multi-pellet one-shots up close, collapses at long range through spread and falloff. */
    SHOTGUN("nova", "xm1014", "mag7", "sawedoff"),
    /** Suppressive: huge magazine and sustained fire, slow reload and movement. */
    MACHINE_GUN("m249", "negev"),
    /** Single-charge close-range utility kill. */
    ZEUS("taser");

    private static final Map<String, WeaponRole> BY_ID = byId();
    private final List<String> ids;

    WeaponRole(String... ids) {
        this.ids = List.of(ids);
    }

    /** Firearm ids with this role, in CsmcMod.FIREARM_IDS order. */
    public List<String> ids() {
        return ids;
    }

    /** Every firearm id with its role, grouped by role. */
    public static Map<String, WeaponRole> all() {
        return BY_ID;
    }

    /** Role of a firearm id; unknown ids are treated as rifles, like the importer's unknown-type fallback. */
    public static WeaponRole of(String id) {
        return BY_ID.getOrDefault(id, RIFLE);
    }

    private static Map<String, WeaponRole> byId() {
        Map<String, WeaponRole> map = new LinkedHashMap<>();
        for (WeaponRole role : values()) {
            for (String id : role.ids) {
                map.put(id, role);
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
