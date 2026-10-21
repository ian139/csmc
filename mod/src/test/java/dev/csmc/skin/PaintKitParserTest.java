package dev.csmc.skin;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class PaintKitParserTest {
    @Test public void parsesRepeatedSectionsStylesRangesAndDeduplicatedWeaponPairs() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream("/paintkits-fixture.txt")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var kits = PaintKitParser.parse(source);
        assertEquals(2, kits.size());
        var custom = kits.get(41);
        assertEquals("cu_practice", custom.name());
        assertEquals(7, custom.style());
        assertEquals(.1f, custom.wearMin(), 0);
        assertEquals(.7f, custom.wearMax(), 0);
        assertTrue(custom.legacyModel());
        assertEquals(Set.of("practice", "trainer"), custom.weapons());
        assertEquals(.06f, kits.get(42).wearMin(), 0);
        assertEquals(.8f, kits.get(42).wearMax(), 0);
        assertEquals("weapons/paints/self_authored/example.vcompmat", kits.get(42).compositeMaterial());
    }

    @Test public void malformedBraceOrWearRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PaintKitParser.parse("\"items_game\" {"));
        assertThrows(IllegalArgumentException.class, () -> PaintKitParser.parse("\"items_game\" {} }"));
        assertThrows(IllegalArgumentException.class, () -> PaintKitParser.parse("\"items_game\" { \"paint_kits\" { \"1\" { \"name\" \"x\" \"style\" \"1\" \"wear_remap_max\" \"NaN\" } } }"));
    }
}
