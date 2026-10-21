package dev.csmc.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeaponZoomDataTest {
    private static final double EPSILON = 1.0E-9;

    private static WeaponData parse(String members) {
        return WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", "practice"),
                JsonParser.parseString("{\"damage\":30,\"magazine_size\":10,\"cycle_time_s\":0.2" + members + "}"));
    }

    private static WeaponData imported(String source, String id) {
        JsonObject json = Cs2WeaponImporter.importText(source, Map.of()).get(id);
        assertNotNull("imported " + id, json);
        return WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", id), json);
    }

    @Test
    public void maxSpeedIsBoundedForBothFireModes() {
        assertEquals(2_000, parse(",\"max_speed\":2000").maxSpeed(), 0);
        assertEquals(1_500, parse(",\"max_speed\":1500").maxSpeed(), 0);
        for (String members : List.of(",\"max_speed\":2000.0001", ",\"max_speed\":1000000",
                ",\"scoped\":{\"max_speed\":2000.0001}")) {
            assertThrows(JsonParseException.class, () -> parse(members));
        }
    }

    @Test
    public void absentOrNullScopePreservesUnscopedDefaults() {
        for (String members : List.of("", ",\"zoom\":null,\"scoped\":null")) {
            WeaponData weapon = parse(members);
            assertSame(WeaponData.Zoom.NONE, weapon.zoom());
            assertFalse(weapon.zoom().enabled());
            assertEquals(0, weapon.zoom().levelCount());
            assertEquals(weapon.spreadBase(), weapon.scoped().spreadBase(), EPSILON);
            assertSame(weapon.inaccuracy(), weapon.scoped().inaccuracy());
            assertEquals(weapon.maxSpeed(), weapon.scoped().maxSpeed(), EPSILON);
            assertEquals(weapon.cycleTimeS(), weapon.scoped().cycleTimeS(), EPSILON);
            assertEquals(weapon.spreadBase(), weapon.activeSpreadBase(true), EPSILON);
            assertSame(weapon.inaccuracy(), weapon.activeInaccuracy(true));
            assertEquals(weapon.maxSpeed(), weapon.activeMaxSpeed(true), EPSILON);
            assertEquals(weapon.cycleTimeS(), weapon.activeCycleTimeS(true), EPSILON);
        }
    }

    @Test
    public void zoomParsesHorizontalDegreesAndIndexedTransitionTimes() {
        WeaponData.Zoom zoom = parse("""
                ,"zoom":{"levels":[40,10],"time_s":0.08,"times_s":[0.03,0.05],"unzoom_after_shot":true}
                """).zoom();
        assertTrue(zoom.enabled());
        assertEquals(List.of(40.0, 10.0), zoom.levels());
        assertEquals(2, zoom.levelCount());
        assertEquals(40.0, zoom.fovDegrees(1), EPSILON);
        assertEquals(10.0, zoom.fovDegrees(2), EPSILON);
        assertEquals(0.08, zoom.timeS(), EPSILON);
        assertEquals(List.of(0.03, 0.05), zoom.timesS());
        assertEquals(0.03, zoom.transitionTimeS(0), EPSILON);
        assertEquals(0.05, zoom.transitionTimeS(1), EPSILON);
        assertEquals(0.08, zoom.transitionTimeS(2), EPSILON);
        assertTrue(zoom.unzoomAfterShot());
    }

    @Test
    public void omittedZoomTimesUseDefaultAndListsAreImmutable() {
        WeaponData.Zoom zoom = parse(",\"zoom\":{\"levels\":[45]}").zoom();
        assertEquals(WeaponData.Zoom.DEFAULT_TIME_S, zoom.timeS(), EPSILON);
        assertTrue(zoom.timesS().isEmpty());
        assertEquals(zoom.timeS(), zoom.transitionTimeS(0), EPSILON);
        assertEquals(zoom.timeS(), zoom.transitionTimeS(1), EPSILON);
        assertFalse(zoom.unzoomAfterShot());

        List<Double> levels = new ArrayList<>(List.of(40.0, 10.0));
        List<Double> times = new ArrayList<>(List.of(0.05));
        WeaponData.Zoom copied = new WeaponData.Zoom(levels, 0.1, times, false);
        levels.set(0, 80.0);
        times.set(0, 0.5);
        assertEquals(40.0, copied.fovDegrees(1), EPSILON);
        assertEquals(0.05, copied.transitionTimeS(0), EPSILON);
        try {
            copied.levels().add(20.0);
            fail("levels must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Immutable data shared between the firing and rendering paths.
        }
        try {
            copied.timesS().set(0, 1.0);
            fail("times must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Immutable data shared between the firing and rendering paths.
        }
    }

    @Test
    public void scopedJsonOverridesAllModeFieldsAndInaccuracyTerms() {
        WeaponData weapon = parse("""
                ,"spread_base":0.004,"max_speed":240,
                "inaccuracy":{"stand":0.08,"crouch":0.06,"move":0.17,"jump":0.14,"land":0.02,"ladder":0.15,"fire":0.05},
                "zoom":{"levels":[40]},
                "scoped":{"spread_base":0.0002,"max_speed":100,"cycle_time_s":0.25,
                    "inaccuracy":{"stand":0.002,"crouch":0.0015,"move":0.12,"jump":0.13,"land":0.0001,"ladder":0.14,"fire":0.04}}
                """);
        assertEquals(new WeaponData.Inaccuracy(0.002, 0.0015, 0.12, 0.13, 0.0001, 0.14, 0.04),
                weapon.scoped().inaccuracy());
        assertEquals(0.0002, weapon.activeSpreadBase(true), EPSILON);
        assertSame(weapon.scoped().inaccuracy(), weapon.activeInaccuracy(true));
        assertEquals(100.0, weapon.activeMaxSpeed(true), EPSILON);
        assertEquals(0.25, weapon.activeCycleTimeS(true), EPSILON);
        assertEquals(0.004, weapon.activeSpreadBase(false), EPSILON);
        assertSame(weapon.inaccuracy(), weapon.activeInaccuracy(false));
        assertEquals(240.0, weapon.activeMaxSpeed(false), EPSILON);
        assertEquals(0.2, weapon.activeCycleTimeS(false), EPSILON);
    }

    @Test
    public void scopedJsonInheritsEachOmittedOrNullMemberFromPrimary() {
        WeaponData weapon = parse("""
                ,"spread_base":0.004,"max_speed":240,
                "inaccuracy":{"stand":0.08,"crouch":0.06,"move":0.17,"jump":0.14,"land":0.02,"ladder":0.15,"fire":0.05},
                "zoom":{"levels":[40]},
                "scoped":{"spread_base":null,"max_speed":null,"cycle_time_s":null,"inaccuracy":{"stand":0.002,"crouch":null}}
                """);
        assertEquals(new WeaponData.Inaccuracy(0.002, 0.06, 0.17, 0.14, 0.02, 0.15, 0.05),
                weapon.activeInaccuracy(true));
        assertEquals(weapon.spreadBase(), weapon.activeSpreadBase(true), EPSILON);
        assertEquals(weapon.maxSpeed(), weapon.activeMaxSpeed(true), EPSILON);
        assertEquals(weapon.cycleTimeS(), weapon.activeCycleTimeS(true), EPSILON);

        WeaponData inherited = parse(",\"zoom\":{\"levels\":[40]},\"inaccuracy\":{\"stand\":0.08},\"scoped\":{\"inaccuracy\":null}");
        assertSame(inherited.inaccuracy(), inherited.activeInaccuracy(true));
    }

    @Test
    public void modeSelectionNeverEnablesScopeOnAWeaponWithoutZoomLevels() {
        WeaponData weapon = parse("""
                ,"scoped":{"spread_base":0.03,"max_speed":100,"cycle_time_s":0.3,"inaccuracy":{"stand":0.1}}
                """);
        assertEquals(0.03, weapon.scoped().spreadBase(), EPSILON);
        assertEquals(weapon.spreadBase(), weapon.activeSpreadBase(true), EPSILON);
        assertSame(weapon.inaccuracy(), weapon.activeInaccuracy(true));
        assertEquals(weapon.maxSpeed(), weapon.activeMaxSpeed(true), EPSILON);
        assertEquals(weapon.cycleTimeS(), weapon.activeCycleTimeS(true), EPSILON);
    }

    @Test
    public void rejectsMalformedZoomAndScopedJson() {
        for (String members : List.of(
                ",\"zoom\":[]",
                ",\"zoom\":{\"levels\":40}",
                ",\"zoom\":{\"levels\":[\"40\"]}",
                ",\"zoom\":{\"levels\":[null]}",
                ",\"zoom\":{\"levels\":[0]}",
                ",\"zoom\":{\"levels\":[180]}",
                ",\"zoom\":{\"levels\":[-10]}",
                ",\"zoom\":{\"levels\":[1e999]}",
                ",\"zoom\":{\"time_s\":-0.01}",
                ",\"zoom\":{\"times_s\":0.05}",
                ",\"zoom\":{\"times_s\":[-0.01]}",
                ",\"zoom\":{\"times_s\":[\"0.05\"]}",
                ",\"zoom\":{\"unzoom_after_shot\":1}",
                ",\"scoped\":[]",
                ",\"scoped\":{\"spread_base\":-0.01}",
                ",\"scoped\":{\"max_speed\":0}",
                ",\"scoped\":{\"cycle_time_s\":0}",
                ",\"scoped\":{\"inaccuracy\":[]}",
                ",\"scoped\":{\"inaccuracy\":{\"stand\":-0.01}}")) {
            try {
                parse(members);
                fail("malformed scope JSON must fail: " + members);
            } catch (JsonParseException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void importsInheritedRealShapedScalarCycleAndSecondaryArrays() {
        // Self-authored fixture with the scalar/CFiringModeFloat layout used by the AWP/SSG 08 exports.
        WeaponData weapon = imported("""
                {
                    weapon_awp_prefab = {
                        m_iMaxClip1 = 5 m_nDamage = 110 m_WeaponType = "WEAPONTYPE_SNIPER_RIFLE"
                        m_nZoomLevels = 2 m_nZoomFOV1 = 40 m_nZoomFOV2 = 10
                        m_flZoomTime0 = 0.03 m_flZoomTime1 = 0.05 m_flZoomTime2 = 0.07
                        m_bUnzoomsAfterShot = true
                        m_flCycleTime = 1.45 m_flMaxSpeed = [ 200.0, 100.0 ] m_flSpread = [ 0.003, 0.0003 ]
                        m_flInaccuracyStand = [ 0.08, 0.002 ] m_flInaccuracyCrouch = [ 0.06, 0.0015 ]
                        m_flInaccuracyMove = [ 0.17, 0.16 ] m_flInaccuracyJump = [ 0.14, 0.13 ]
                        m_flInaccuracyLand = [ 0.02, 0.0001 ] m_flInaccuracyLadder = [ 0.15, 0.14 ]
                        m_flInaccuracyFire = [ 0.05, 0.04 ]
                    }
                    weapon_awp = { _base = "weapon_awp_prefab" m_flCycleTime = 1.455 }
                }
                """, "awp");
        assertEquals(List.of(40.0, 10.0), weapon.zoom().levels());
        assertEquals(List.of(0.03, 0.05, 0.07), weapon.zoom().timesS());
        assertEquals(0.03, weapon.zoom().timeS(), EPSILON);
        assertTrue(weapon.zoom().unzoomAfterShot());
        assertEquals(1.455, weapon.cycleTimeS(), EPSILON);
        assertEquals(1.455, weapon.activeCycleTimeS(true), EPSILON);
        assertEquals(200.0, weapon.maxSpeed(), EPSILON);
        assertEquals(100.0, weapon.activeMaxSpeed(true), EPSILON);
        assertEquals(0.003, weapon.spreadBase(), EPSILON);
        assertEquals(0.0003, weapon.activeSpreadBase(true), EPSILON);
        assertEquals(new WeaponData.Inaccuracy(0.08, 0.06, 0.17, 0.14, 0.02, 0.15, 0.05), weapon.inaccuracy());
        assertEquals(new WeaponData.Inaccuracy(0.002, 0.0015, 0.16, 0.13, 0.0001, 0.14, 0.04),
                weapon.activeInaccuracy(true));
    }

    @Test
    public void anyCachedGunCanImportZoomAndArrayCycleWithInheritedScalars() {
        WeaponData weapon = imported("""
                {
                    optic_base = {
                        m_iMaxClip1 = 20 m_nDamage = 35 m_WeaponType = "WEAPONTYPE_RIFLE"
                        m_nZoomLevels = 1 m_nZoomFOV1 = 45
                        m_flCycleTime = [ 0.1, 0.15 ] m_flMaxSpeed = [ 240, 200 ]
                        m_flSpread = 0.002 m_flInaccuracyStand = [ 0.02, 0.01 ] m_flInaccuracyMove = 0.12
                    }
                    weapon_future_optic = { _prefab = "optic_base" m_flInaccuracyStand = [ 0.03, 0.005 ] }
                }
                """, "future_optic");
        assertEquals(List.of(45.0), weapon.zoom().levels());
        assertEquals(WeaponData.Zoom.DEFAULT_TIME_S, weapon.zoom().transitionTimeS(1), EPSILON);
        assertFalse(weapon.zoom().unzoomAfterShot());
        assertEquals(0.1, weapon.cycleTimeS(), EPSILON);
        assertEquals(0.15, weapon.activeCycleTimeS(true), EPSILON);
        assertEquals(200.0, weapon.activeMaxSpeed(true), EPSILON);
        assertEquals(weapon.spreadBase(), weapon.activeSpreadBase(true), EPSILON);
        assertEquals(0.03, weapon.inaccuracy().stand(), EPSILON);
        assertEquals(0.005, weapon.activeInaccuracy(true).stand(), EPSILON);
        assertEquals(0.12, weapon.activeInaccuracy(true).move(), EPSILON);
    }

    @Test
    public void canonicalScopeValuesOverridePrefabAndSparseTimesKeepTheirIndices() {
        WeaponData weapon = imported("""
                {
                    weapon_ssg08_prefab = {
                        m_iMaxClip1 = 10 m_nDamage = 90 m_WeaponType = "WEAPONTYPE_SNIPER_RIFLE"
                        m_nZoomLevels = 2 m_nZoomFOV1 = 40 m_nZoomFOV2 = 10
                        m_flZoomTime1 = 0.06 m_bUnzoomsAfterShot = false m_flCycleTime = 1.25
                    }
                    weapon_ssg08 = {
                        _base = "weapon_ssg08_prefab"
                        m_nZoomFOV2 = 15 m_flZoomTime2 = 0.08 m_bUnzoomsAfterShot = true
                    }
                }
                """, "ssg08");
        assertEquals(List.of(40.0, 15.0), weapon.zoom().levels());
        assertEquals(List.of(WeaponData.Zoom.DEFAULT_TIME_S, 0.06, 0.08), weapon.zoom().timesS());
        assertTrue(weapon.zoom().unzoomAfterShot());
    }

    @Test
    public void cachedScopeFieldsBeatAuthoredOverridesAndPreserveAbsentAuthoredMembers() {
        JsonObject authored = JsonParser.parseString("""
                {
                    "zoom":{"levels":[60],"time_s":0.3,"times_s":[0.3,0.3],"unzoom_after_shot":false},
                    "scoped":{"spread_base":0.01,"max_speed":123,"cycle_time_s":0.5,"inaccuracy":{"stand":0.02,"fire":0.01}}
                }
                """).getAsJsonObject();
        JsonObject json = Cs2WeaponImporter.importText("""
                { weapon_custom = {
                    m_iMaxClip1 = 15 m_nDamage = 40 m_WeaponType = "WEAPONTYPE_RIFLE"
                    m_nZoomLevels = 1 m_nZoomFOV1 = 45 m_bUnzoomsAfterShot = true
                    m_flZoomTime0 = 0.04 m_flZoomTime1 = 0.06 m_flCycleTime = 0.2
                    m_flSpread = [ 0.004, 0.0004 ] m_flInaccuracyStand = [ 0.08, 0.003 ]
                } }
                """, Map.of("custom", authored)).get("custom");
        WeaponData weapon = WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", "custom"), json);
        assertEquals(List.of(45.0), weapon.zoom().levels());
        assertEquals(List.of(0.04, 0.06, 0.04), weapon.zoom().timesS());
        assertEquals(0.04, weapon.zoom().timeS(), EPSILON);
        assertTrue(weapon.zoom().unzoomAfterShot());
        assertEquals(0.0004, weapon.activeSpreadBase(true), EPSILON);
        assertEquals(0.2, weapon.activeCycleTimeS(true), EPSILON);
        assertEquals(0.003, weapon.activeInaccuracy(true).stand(), EPSILON);
        assertEquals(123.0, weapon.activeMaxSpeed(true), EPSILON);
        assertEquals(0.01, weapon.activeInaccuracy(true).fire(), EPSILON);
        assertEquals(60.0, authored.getAsJsonObject("zoom").getAsJsonArray("levels").get(0).getAsDouble(), EPSILON);
    }

    @Test
    public void burstAndSilencerSecondaryModesDoNotCreateScopes() {
        WeaponData weapon = imported("""
                { weapon_nonoptic = {
                    m_iMaxClip1 = 30 m_nDamage = 30 m_WeaponType = "WEAPONTYPE_RIFLE"
                    m_nZoomLevels = 0 m_nZoomFOV1 = 90 m_nZoomFOV2 = 90
                    m_flZoomTime0 = 0.0 m_flZoomTime1 = 0.0 m_flZoomTime2 = 0.0
                    m_flCycleTime = [ 0.1, 0.2 ] m_flSpread = [ 0.004, 0.001 ]
                    m_flMaxSpeed = [ 230, 200 ] m_flInaccuracyStand = [ 0.02, 0.01 ]
                } }
                """, "nonoptic");
        assertFalse(weapon.zoom().enabled());
        assertEquals(0.1, weapon.activeCycleTimeS(true), EPSILON);
        assertEquals(0.004, weapon.activeSpreadBase(true), EPSILON);
        assertEquals(230.0, weapon.activeMaxSpeed(true), EPSILON);
        assertEquals(0.02, weapon.activeInaccuracy(true).stand(), EPSILON);
    }

    @Test
    public void boltSniperFallbacksHaveZoomAndClearlyDifferentStandingAccuracy() {
        for (String id : List.of("awp", "ssg08")) {
            WeaponData weapon = WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", id),
                    WeaponPlaceholders.create(id));
            assertEquals(id.equals("awp") ? List.of(40.0, 10.0) : List.of(40.0, 15.0), weapon.zoom().levels());
            assertEquals(List.of(0.05, 0.05, 0.05), weapon.zoom().timesS());
            assertTrue(weapon.zoom().unzoomAfterShot());
            assertTrue("large unscoped standing penalty", weapon.inaccuracy().stand() >= 0.06);
            assertTrue("scope substantially improves standing accuracy",
                    weapon.inaccuracy().stand() > weapon.activeInaccuracy(true).stand() * 10.0);
            assertTrue("scope substantially improves crouching accuracy",
                    weapon.inaccuracy().crouch() > weapon.activeInaccuracy(true).crouch() * 10.0);
            assertTrue(weapon.activeInaccuracy(true).stand() <= 0.003);
            assertTrue(weapon.activeMaxSpeed(true) <= weapon.maxSpeed());
        }
        WeaponData rifle = WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", "ak47"),
                WeaponPlaceholders.create("ak47"));
        assertFalse(rifle.zoom().enabled());
        assertEquals(0.008, rifle.inaccuracy().stand(), EPSILON);
        assertEquals(0.002, rifle.spreadBase(), EPSILON);
    }
}
