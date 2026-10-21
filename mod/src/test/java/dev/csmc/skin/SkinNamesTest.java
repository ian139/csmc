package dev.csmc.skin;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkinNamesTest {
    private static SkinCatalog.Kit kit(String name, String displayName) {
        return new SkinCatalog.Kit(1, name, displayName, 1, 0f, 1f, "ancient", Set.of("knife_karambit"),
                Set.of("knife_karambit"), false);
    }

    private static void assertName(String expected, String name, String displayName) {
        SkinCatalog.Kit kit = kit(name, displayName);
        assertEquals(name, expected, SkinNames.displayName(kit));
        assertEquals("stored name unchanged", displayName, kit.displayName());
    }

    /** Every Doppler-family knife kit in the local exported index (paint kits 415-421, 568-572, 617-619, 852-855). */
    @Test public void realKnifeKitsGainPhaseOrGem() {
        assertName("Doppler (Ruby)", "am_ruby_marbleized", "Doppler");
        assertName("Doppler (Sapphire)", "am_sapphire_marbleized", "Doppler");
        assertName("Doppler (Black Pearl)", "am_blackpearl_marbleized", "Doppler");
        assertName("Doppler (Phase 1)", "am_doppler_phase1", "Doppler");
        assertName("Doppler (Phase 2)", "am_doppler_phase2", "Doppler");
        assertName("Doppler (Phase 3)", "am_doppler_phase3", "Doppler");
        assertName("Doppler (Phase 4)", "am_doppler_phase4", "Doppler");
        assertName("Gamma Doppler (Emerald)", "am_emerald_marbleized", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 1)", "am_gamma_doppler_phase1", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 2)", "am_gamma_doppler_phase2", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 3)", "am_gamma_doppler_phase3", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 4)", "am_gamma_doppler_phase4", "Gamma Doppler");
        assertName("Doppler (Black Pearl)", "am_blackpearl_marbleized_b", "Doppler");
        assertName("Doppler (Phase 2)", "am_doppler_phase2_b", "Doppler");
        assertName("Doppler (Sapphire)", "am_sapphire_marbleized_b", "Doppler");
        assertName("Doppler (Phase 1)", "am_doppler_phase1_widow", "Doppler");
        assertName("Doppler (Phase 2)", "am_doppler_phase2_widow", "Doppler");
        assertName("Doppler (Phase 3)", "am_doppler_phase3_widow", "Doppler");
        assertName("Doppler (Phase 4)", "am_doppler_phase4_widow", "Doppler");
    }

    /** Glock Gamma Doppler variants (paint kits 1119-1123). */
    @Test public void glockVariantsGainPhaseOrGem() {
        assertName("Gamma Doppler (Emerald)", "am_emerald_marbleized_glock", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 1)", "am_gamma_doppler_phase1_glock", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 2)", "am_gamma_doppler_phase2_glock", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 3)", "am_gamma_doppler_phase3_glock", "Gamma Doppler");
        assertName("Gamma Doppler (Phase 4)", "am_gamma_doppler_phase4_glock", "Gamma Doppler");
    }

    @Test public void ordinaryAndUnknownKitsAreUnchanged() {
        assertName("Fade", "aa_fade", "Fade");
        assertName("Marble Fade", "am_marble_fade", "Marble Fade");
        assertName("Fixture Kit", "fixture_kit", "Fixture Kit");
        // Doppler-family display names without a recognised phase or gem keep the base name.
        assertName("Doppler", "am_doppler_phase5", "Doppler");
        assertName("Doppler", "am_doppler", "Doppler");
        assertName("Gamma Doppler", "fixture_gamma", "Gamma Doppler");
        assertName("Doppler", "am_ruby_marbleized_", "Doppler");
        // A phase-encoded internal name never relabels a non-Doppler display name.
        assertName("Ruby Fixture", "am_ruby_marbleized", "Ruby Fixture");
        assertName("Fixture", "am_doppler_phase2", "Fixture");
    }
}
