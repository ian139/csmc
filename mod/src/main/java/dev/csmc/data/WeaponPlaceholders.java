package dev.csmc.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Self-authored, deliberately approximate class defaults when no local CS2 export exists. */
final class WeaponPlaceholders {
    private WeaponPlaceholders() {}

    static JsonObject create(String id) {
        // Mechanical class predicates (scope, unzoom, automatic fire, cadence, spread) are CS weapon classes and
        // deliberately independent of WeaponRole; the role only shapes damage and armor ratio values below.
        WeaponRole role = WeaponRole.of(id);
        boolean pistol = role == WeaponRole.PISTOL;
        boolean smg = role == WeaponRole.SMG;
        boolean sniper = id.equals("ssg08") || id.equals("awp") || id.equals("m14")
                || id.equals("scar20") || id.equals("g3sg1");
        boolean shotgun = role == WeaponRole.SHOTGUN;
        boolean machineGun = role == WeaponRole.MACHINE_GUN;
        boolean autoSniper = id.equals("scar20") || id.equals("g3sg1");
        boolean boltSniper = id.equals("awp") || id.equals("ssg08");
        boolean taser = role == WeaponRole.ZEUS;
        boolean automatic = (!pistol && !sniper && !shotgun && !taser)
                || id.equals("cz75a") || id.equals("xm1014") || autoSniper;
        int clip = taser ? 1 : machineGun ? 100 : shotgun ? 7 : autoSniper ? 20 : sniper ? 10 : pistol ? 16 : 30;
        double cycle = taser || pistol ? 0.15 : autoSniper ? 0.25 : sniper ? 1.2
                : id.equals("xm1014") ? 0.35 : shotgun ? 0.9 : smg || machineGun ? 0.08 : 0.1;
        JsonObject json = new JsonObject();
        json.addProperty("id", "csmc:" + id);
        json.addProperty("display_name", id.replace('_', ' '));
        // Class approximations shaped by WeaponRole (docs/balance.md): one-shot snipers kill an unarmored body,
        // marksman rifles do not; SMGs out-damage rifles up close; shotgun bursts one-shot kevlar up close.
        json.addProperty("damage", taser ? 300 : role == WeaponRole.SNIPER ? 115 : sniper ? 80 : shotgun ? 26
                : pistol ? 28 : smg ? 27 : 32);
        json.addProperty("magazine_size", clip);
        json.addProperty("cycle_time_s", cycle);
        json.addProperty("reserve_ammo", taser ? 0 : clip * 3);
        json.addProperty("reload_time_s", taser ? 0 : shotgun ? 4.0 : machineGun ? 5.5 : 2.5);
        json.addProperty("deploy_time_s", 1.0);
        json.addProperty("range", taser ? 128 : shotgun ? 2400 : pistol || smg ? 4000 : 8000);
        json.addProperty("range_modifier", shotgun ? 0.75 : pistol || smg ? 0.85 : 0.98);
        json.addProperty("penetration_power", taser ? 0 : sniper ? 2.5 : pistol || smg || shotgun ? 1 : 2);
        // SSG 08 1.7 (its CS2 value): a kevlar body survives it, keeping the AWP the only kevlar-piercing one-shot.
        json.addProperty("armor_ratio", taser || id.equals("awp") || id.equals("m14") ? 1.95 : id.equals("ssg08") ? 1.7
                : sniper ? 1.65 : pistol ? 1.0 : smg || shotgun ? 1.2 : 1.5);
        json.addProperty("headshot_multiplier", 4.0);
        json.addProperty("pellets", shotgun ? 8 : 1);
        json.addProperty("max_speed", sniper ? 200 : 230);
        json.addProperty("spread_base", shotgun ? 0.04 : boltSniper ? 0.0003 : 0.002);
        json.addProperty("automatic", automatic);
        json.addProperty("ammo_type", AmmoType.forPlaceholder(id).key());
        JsonObject inaccuracy = new JsonObject();
        // M14/SCAR-20/G3SG1 have no scope without an export, so their long-range role needs scope-like hip accuracy
        // (about their CS2 scoped values) instead of the old 0.06/0.04.
        inaccuracy.addProperty("stand", boltSniper ? 0.08 : sniper ? 0.004 : 0.008);
        inaccuracy.addProperty("crouch", boltSniper ? 0.06 : sniper ? 0.003 : 0.005);
        inaccuracy.addProperty("move", smg ? 0.03 : 0.12);
        inaccuracy.addProperty("jump", 0.15);
        inaccuracy.addProperty("land", 0.02);
        inaccuracy.addProperty("ladder", 0.15);
        inaccuracy.addProperty("fire", pistol ? 0.04 : 0.008);
        json.add("inaccuracy", inaccuracy);
        if (boltSniper) {
            JsonObject zoom = new JsonObject();
            JsonArray levels = new JsonArray();
            levels.add(40.0);
            levels.add(id.equals("awp") ? 10.0 : 15.0);
            zoom.add("levels", levels);
            zoom.addProperty("time_s", 0.05);
            JsonArray times = new JsonArray();
            for (int level = 0; level <= 2; level++) {
                times.add(0.05);
            }
            zoom.add("times_s", times);
            zoom.addProperty("unzoom_after_shot", true);
            json.add("zoom", zoom);
            JsonObject scoped = new JsonObject();
            scoped.addProperty("spread_base", 0.0003);
            scoped.addProperty("max_speed", id.equals("awp") ? 100.0 : 200.0);
            JsonObject scopedInaccuracy = inaccuracy.deepCopy();
            scopedInaccuracy.addProperty("stand", 0.002);
            scopedInaccuracy.addProperty("crouch", 0.0015);
            scopedInaccuracy.addProperty("land", 0.0001);
            scoped.add("inaccuracy", scopedInaccuracy);
            json.add("scoped", scoped);
        }
        JsonObject recovery = new JsonObject();
        recovery.addProperty("stand", 0.35);
        recovery.addProperty("crouch", 0.3);
        recovery.addProperty("stand_final", 0.5);
        recovery.addProperty("crouch_final", 0.4);
        recovery.addProperty("start_bullet", 3);
        recovery.addProperty("end_bullet", 10);
        json.add("recovery", recovery);
        JsonObject recoil = new JsonObject();
        recoil.addProperty("angle", 0);
        recoil.addProperty("angle_variance", 45);
        recoil.addProperty("magnitude", taser ? 0 : sniper || shotgun ? 60 : 25);
        recoil.addProperty("magnitude_variance", 3);
        recoil.addProperty("seed", id.hashCode());
        json.add("recoil", recoil);
        return json;
    }
}
