package dev.csmc.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;

public class Kv3ParserTest {
    private String fixture() throws IOException {
        try (var stream = getClass().getResourceAsStream("/weapons-fixture.vdata")) {
            assertNotNull("self-authored fixture exists", stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesCommentsArraysTypedStringsAndEscapes() throws IOException {
        JsonObject root = Kv3Parser.parse(fixture());
        JsonObject weapon = root.getAsJsonObject("weapon_practice");
        assertEquals("self-authored/practice.vmdl", weapon.get("m_szWorldModel").getAsString());
        assertEquals("Test.Practice", weapon.getAsJsonObject("m_aShootSounds").get("single").getAsString());
        assertEquals("A quote: \" and unicode A", weapon.get("description").getAsString());
        assertTrue(weapon.get("optional").isJsonNull());
        assertEquals(-0.0125, weapon.get("signed").getAsDouble(), 1e-9);
        assertEquals(3, weapon.getAsJsonArray("flags").size());
        assertEquals(0.46, root.getAsJsonObject("training_base").getAsJsonArray("m_flCycleTime").get(1).getAsDouble(), 1e-9);
    }

    @Test
    public void importsPrimaryModeInheritanceAndMissingFieldFallbacks() throws IOException {
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("spread_base", 0.003);
        Map<String, JsonObject> imported = Cs2WeaponImporter.importText(fixture(), Map.of("practice", placeholder));
        assertEquals(1, imported.size());
        JsonObject weapon = imported.get("practice");
        assertEquals(19, weapon.get("damage").getAsInt());
        assertEquals(11, weapon.get("magazine_size").getAsInt());
        assertEquals(0.23, weapon.get("cycle_time_s").getAsDouble(), 1e-9);
        assertEquals(1.3, weapon.get("armor_ratio").getAsDouble(), 1e-9);
        assertEquals(0.012, weapon.getAsJsonObject("inaccuracy").get("stand").getAsDouble(), 1e-9);
        assertEquals(12, weapon.getAsJsonObject("recoil").get("angle").getAsInt());
        assertEquals(4321, weapon.getAsJsonObject("recoil").get("seed").getAsInt());
        assertEquals(0.003, weapon.get("spread_base").getAsDouble(), 1e-9);
        assertEquals(33, weapon.get("reserve_ammo").getAsInt());
        assertEquals(2.5, weapon.get("reload_time_s").getAsDouble(), 1e-9);
        assertFalse("conversion must not mutate datapack JSON", placeholder.has("damage"));
    }

    @Test
    public void preservesAuthoredReloadReserveAndMeasuredPattern() {
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("reload_time_s", 1.75);
        placeholder.addProperty("reserve_ammo", 14);
        JsonObject recoil = Kv3Parser.parse("{ pattern = [ [1.0, -0.5] ] }");
        placeholder.add("recoil", recoil);
        JsonObject weapon = Cs2WeaponImporter.importText(
                "{ weapon_ak47 = { m_iMaxClip1 = 7 m_nDamage = 13 } }", Map.of("ak47", placeholder)).get("ak47");
        assertEquals(1.75, weapon.get("reload_time_s").getAsDouble(), 1e-9);
        assertEquals(14, weapon.get("reserve_ammo").getAsInt());
        assertEquals(-0.5, weapon.getAsJsonObject("recoil").getAsJsonArray("pattern").get(0)
                .getAsJsonArray().get(1).getAsDouble(), 1e-9);
    }

    @Test
    public void normalizesAmmoEnumsAndPreservesAuthoredItemMappings() {
        String source = "{ weapon_sample = { m_iMaxClip1 = 7 m_nDamage = 13 "
                + "m_WeaponType = \"WEAPONTYPE_PISTOL\" m_nPrimaryAmmoType = \"BULLET_PLAYER_9MM\" } }";
        assertEquals("pistol", Cs2WeaponImporter.importText(source, Map.of())
                .get("sample").get("ammo_type").getAsString());
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("ammo_type", "example:custom_ammo");
        assertEquals("example:custom_ammo", Cs2WeaponImporter.importText(source, Map.of("sample", placeholder))
                .get("sample").get("ammo_type").getAsString());
    }

    @Test
    public void rejectsSurfaceModifiersOutsideTheirUnitRange() {
        for (String value : new String[] {"-0.1", "1.1", "NaN", "Infinity"}) {
            try {
                SurfacePropertiesImporter.parse("\"synthetic\" { \"bulletPenetrationDistanceModifier\" \"" + value + "\" }");
                fail("Expected invalid surface modifier to fail: " + value);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("[0, 1]"));
            }
        }
    }

    @Test
    public void isolatesMalformedWeaponsAndCyclicPrefabs() {
        String source = """
                {
                    weapon_test = { _base = "loop" }
                    loop = { _base = "weapon_test" }
                    weapon_bad_clip = { m_iMaxClip1 = "bad" }
                    weapon_bad_bool_clip = { m_iMaxClip1 = true m_nDamage = 30 }
                    weapon_missing_base = { _base = "nowhere" m_iMaxClip1 = 30 }
                    weapon_good = { m_iMaxClip1 = 9 m_nDamage = 18 }
                }
                """;
        Map<String, JsonObject> imported = Cs2WeaponImporter.importText(source, Map.of());
        assertEquals(1, imported.size());
        assertEquals(9, imported.get("good").get("magazine_size").getAsInt());
    }

    /**
     * A gun that converts but violates the weapon contract (negative damage) is rejected on its own by
     * the per-gun validation the registry applies; its neighbours still produce valid definitions.
     */
    @Test
    public void invalidImportedGunFailsAloneAtValidation() {
        String source = "{ weapon_negative = { m_iMaxClip1 = 9 m_nDamage = -5 } "
                + "weapon_fine = { m_iMaxClip1 = 12 m_nDamage = 26 m_WeaponType = \"WEAPONTYPE_RIFLE\" } }";
        Map<String, JsonObject> imported = Cs2WeaponImporter.importText(source, Map.of());
        assertEquals(2, imported.size());
        try {
            WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", "negative"), imported.get("negative"));
            fail("negative damage must be rejected");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("damage"));
        }
        WeaponData fine = WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", "fine"), imported.get("fine"));
        assertEquals(26.0, fine.damage(), 1e-9);
        assertEquals("rifle", fine.ammoType());
    }

    /** Without an authored file the hand-authored supply facts beat the generated class placeholder. */
    @Test
    public void unauthoredGunsUseSupplyFactsThenClassPlaceholder() {
        Map<String, JsonObject> imported = Cs2WeaponImporter.importText(
                "{ weapon_m4a1 = { m_iMaxClip1 = 30 m_nDamage = 33 m_WeaponType = \"WEAPONTYPE_RIFLE\" } "
                        + "weapon_p90 = { m_iMaxClip1 = 50 m_nDamage = 26 m_WeaponType = \"WEAPONTYPE_SUBMACHINEGUN\" } "
                        + "weapon_cz75a = { m_iMaxClip1 = 12 m_nDamage = 31 m_WeaponType = \"WEAPONTYPE_PISTOL\" } }",
                Map.of());
        assertEquals(3.07, imported.get("m4a1").get("reload_time_s").getAsDouble(), 1e-9);
        assertEquals(90, imported.get("m4a1").get("reserve_ammo").getAsInt());
        assertEquals(150, imported.get("p90").get("reserve_ammo").getAsInt());
        assertEquals("pistol", imported.get("p90").get("ammo_type").getAsString());
        assertEquals("pistol", imported.get("cz75a").get("ammo_type").getAsString());
    }

    /** CS2's -1 "no final recovery" sentinel must import as the base time, not fail validation. */
    @Test
    public void negativeFinalRecoveryUsesBaseTime() {
        JsonObject weapon = Cs2WeaponImporter.importText("{ weapon_m14 = { m_iMaxClip1 = 20 m_nDamage = 50 "
                + "m_flRecoveryTimeStand = 0.4 m_flRecoveryTimeStandFinal = -1.0 "
                + "m_flRecoveryTimeCrouch = 0.3 m_flRecoveryTimeCrouchFinal = [ -1.0, -1.0 ] } }", Map.of()).get("m14");
        JsonObject recovery = weapon.getAsJsonObject("recovery");
        assertEquals(0.4, recovery.get("stand_final").getAsDouble(), 1e-9);
        assertEquals(0.3, recovery.get("crouch_final").getAsDouble(), 1e-9);
        WeaponData parsed = WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", "m14"), weapon);
        assertEquals(0.4, parsed.recovery().timeS(false, 30), 1e-9);
    }

    @Test
    public void unknownWeaponTypeFallsBackToRifleAmmo() {
        JsonObject weapon = Cs2WeaponImporter.importText(
                "{ weapon_odd = { m_iMaxClip1 = 5 m_nDamage = 10 m_WeaponType = \"WEAPONTYPE_FUTURE\" } }", Map.of())
                .get("odd");
        assertEquals("rifle", weapon.get("ammo_type").getAsString());
    }

    @Test(expected = JsonParseException.class)
    public void rejectsTrailingInput() {
        Kv3Parser.parse("{} trailing");
    }

    @Test(expected = JsonParseException.class)
    public void rejectsUnterminatedComments() {
        Kv3Parser.parse("{ /* unfinished");
    }

    @Test(expected = JsonParseException.class)
    public void rejectsUnterminatedStrings() {
        Kv3Parser.parse("{ key = \"unfinished }");
    }
}
