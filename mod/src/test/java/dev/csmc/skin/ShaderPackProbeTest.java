package dev.csmc.skin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.function.BooleanSupplier;
import org.junit.Test;

public class ShaderPackProbeTest {
    /** Shape of Iris's {@code IrisApi}: a static {@code getInstance()} and an instance {@code isShaderPackInUse()}. */
    public static final class FakeApi {
        static volatile boolean inUse;
        static volatile boolean fail;
        private static final FakeApi INSTANCE = new FakeApi();

        public static FakeApi getInstance() {
            return INSTANCE;
        }

        public boolean isShaderPackInUse() {
            if (fail) throw new IllegalStateException("boom");
            return inUse;
        }
    }

    @Test public void missingApiHasNoProbe() {
        assertNull(ShaderPackProbe.of("net.irisshaders.iris.api.v0.NoSuchApi"));
        assertNull(ShaderPackProbe.of(String.class.getName()));
    }

    @Test public void readsTheApiAndTreatsFailureAsInUse() {
        BooleanSupplier probe = ShaderPackProbe.of(FakeApi.class.getName());
        FakeApi.inUse = false;
        assertFalse(probe.getAsBoolean());
        FakeApi.inUse = true;
        assertTrue(probe.getAsBoolean());
        FakeApi.inUse = false;
        FakeApi.fail = true;
        try {
            assertTrue(probe.getAsBoolean());
        } finally {
            FakeApi.fail = false;
        }
    }
}
