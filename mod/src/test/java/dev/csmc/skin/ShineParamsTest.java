package dev.csmc.skin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ShineParamsTest {
    @Test
    public void parsesExporterDocument() {
        ShineParams shine = ShineParams.parse(
                "{\"shine_schema\":1,\"roughness\":0.25,\"metalness\":0.8,\"pearlescence\":0.5,\"style\":5}");
        assertEquals(new ShineParams(0.25f, 0.8f, 0.5f), shine);
        assertEquals(0.25f, shine.roughness(), 0f);
        assertEquals(0.5f, shine.pearlescence(), 0f);
    }

    @Test
    public void oldOrUnknownCachesFallBackToMatte() {
        assertNull(ShineParams.parse(null));
        assertNull(ShineParams.parse(""));
        assertNull(ShineParams.parse("{\"roughness\":0.2}"));
        assertNull(ShineParams.parse("{\"shine_schema\":2,\"roughness\":0.2}"));
        assertNull(ShineParams.parse("[1,2]"));
        assertNull(ShineParams.parse("{not json"));
    }

    @Test
    public void clampsQuantizesAndDefaultsFields() {
        ShineParams shine = ShineParams.parse("{\"shine_schema\":1,\"roughness\":-3,\"metalness\":7,\"pearlescence\":\"x\"}");
        assertEquals(0.04f, shine.roughness(), 0f);
        assertEquals(1f, shine.metalness(), 0f);
        assertEquals(0f, shine.pearlescence(), 0f);
        assertEquals(new ShineParams(0.501f, 0.3f, 0f), new ShineParams(0.5f, 0.3f, 0f));
        assertEquals(0.6f, ShineParams.parse("{\"shine_schema\":1}").roughness(), 1 / 64f);
    }
}
