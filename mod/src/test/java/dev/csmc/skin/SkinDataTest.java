package dev.csmc.skin;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkinDataTest {
    @Test public void persistentAndNetworkRoundTrips() {
        SkinData skin = new SkinData(123, .234f, 1000);
        JsonElement json = SkinData.CODEC.encodeStart(JsonOps.INSTANCE, skin).getOrThrow();
        assertEquals(skin, SkinData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        var buffer = Unpooled.buffer();
        try {
            SkinData.STREAM_CODEC.encode(buffer, skin);
            assertEquals(skin, SkinData.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test public void wearTiersUseActualItemFloatBoundaries() {
        assertEquals("Factory New", new SkinData(1, 0, 0).wearTier());
        assertEquals("Factory New", new SkinData(1, .06999f, 0).wearTier());
        assertEquals("Minimal Wear", new SkinData(1, .07f, 0).wearTier());
        assertEquals("Field-Tested", new SkinData(1, .15f, 0).wearTier());
        assertEquals("Well-Worn", new SkinData(1, .38f, 0).wearTier());
        assertEquals("Battle-Scarred", new SkinData(1, .45f, 0).wearTier());
        assertEquals("Battle-Scarred", new SkinData(1, 1, 0).wearTier());
    }

    @Test public void rejectsNonFiniteAndOutOfBoundsState() {
        for (float wear : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f}) {
            assertThrows(IllegalArgumentException.class, () -> new SkinData(1, wear, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new SkinData(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SkinData(1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new SkinData(1, 0, 1001));
    }
}
