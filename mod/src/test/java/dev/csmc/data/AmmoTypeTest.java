package dev.csmc.data;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AmmoTypeTest {
    @Test
    public void importedWeaponClassesSelectTheirAmmunition() {
        Map<String, String> classes = Map.of(
                "PISTOL", "pistol", "SUBMACHINEGUN", "pistol", "RIFLE", "rifle",
                "MACHINEGUN", "rifle", "SNIPER_RIFLE", "sniper", "SHOTGUN", "shotgun", "TASER", "pistol");
        for (var entry : classes.entrySet()) {
            String source = "{ weapon_sample = { m_iMaxClip1 = 7 m_WeaponType = \"WEAPONTYPE_"
                    + entry.getKey() + "\" } }";
            assertEquals(entry.getKey(), entry.getValue(), Cs2WeaponImporter.importText(source, Map.of())
                    .get("sample").get("ammo_type").getAsString());
        }
    }

    @Test
    public void placeholdersMatchEveryAmmunitionClassIncludingM14AndZeus() {
        Map<String, String> categories = Map.of(
                "pistol", "glock hkp2000 usp_silencer p250 fiveseven tec9 cz75a deagle elite revolver mac10 mp9 mp7 mp5sd ump45 p90 bizon taser",
                "rifle", "famas galilar ak47 m4a1 m4a1_silencer aug sg556 m14 m249 negev",
                "sniper", "ssg08 awp scar20 g3sg1",
                "shotgun", "nova xm1014 mag7 sawedoff");
        for (var entry : categories.entrySet()) {
            for (String id : entry.getValue().split(" ")) {
                assertEquals(id, entry.getKey(), WeaponPlaceholders.create(id).get("ammo_type").getAsString());
            }
        }
    }

    @Test
    public void importedClassWinsOverThePlaceholderGuessAndZeusGainsNoReserve() {
        String source = "{ weapon_awp = { m_iMaxClip1 = 7 m_WeaponType = \"WEAPONTYPE_RIFLE\" } "
                + "weapon_m14 = { m_iMaxClip1 = 20 m_WeaponType = \"WEAPONTYPE_RIFLE\" } }";
        var imported = Cs2WeaponImporter.importText(source, Map.of());
        assertEquals("rifle", imported.get("awp").get("ammo_type").getAsString());
        assertEquals("rifle", imported.get("m14").get("ammo_type").getAsString());
        var taser = WeaponPlaceholders.create("taser");
        assertEquals("pistol", taser.get("ammo_type").getAsString());
        assertEquals(0, taser.get("reserve_ammo").getAsInt());
        assertEquals(0.0, taser.get("reload_time_s").getAsDouble(), 0.0);
    }
}
