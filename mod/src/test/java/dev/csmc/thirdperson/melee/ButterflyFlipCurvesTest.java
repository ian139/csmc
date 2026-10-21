package dev.csmc.thirdperson.melee;

import static org.junit.Assert.*;
import dev.csmc.thirdperson.melee.KnifePoseCurves.Action;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

public class ButterflyFlipCurvesTest {
    private static final float[] OUT = new float[3];

    private static boolean sample(Action action, double progress) {
        double duration = KnifePoseCurves.duration(action);
        return ButterflyFlipCurves.sample(action, progress * duration, duration, OUT);
    }

    @Test public void onlyDrawAndInspectFlipTheParts() {
        for (Action action : Action.values()) {
            boolean flips = action == Action.DRAW || action == Action.INSPECT;
            assertEquals(action.name(), flips, sample(action, 0.5));
        }
    }

    @Test public void endpointsAreExactlyBind() {
        for (Action action : new Action[] {Action.DRAW, Action.INSPECT}) {
            for (double progress : new double[] {-0.1, 0, 1, 1.5}) {
                OUT[0] = OUT[1] = OUT[2] = 7;
                assertFalse(sample(action, progress));
                assertEquals(0f, OUT[ButterflyFlipCurves.FRONT], 0f);
                assertEquals(0f, OUT[ButterflyFlipCurves.REAR], 0f);
                assertEquals(0f, OUT[ButterflyFlipCurves.LOCK], 0f);
            }
            assertTrue(sample(action, 1e-4));
            assertEquals(0, OUT[ButterflyFlipCurves.FRONT], 1e-4);
            assertEquals(0, OUT[ButterflyFlipCurves.REAR], 0);
            assertEquals(0, OUT[ButterflyFlipCurves.LOCK], 0);
            assertTrue(sample(action, 1 - 1e-4));
            assertEquals(0, OUT[ButterflyFlipCurves.FRONT], 1e-4);
            assertEquals(0, OUT[ButterflyFlipCurves.REAR], 1e-3);
            assertEquals(0, OUT[ButterflyFlipCurves.LOCK], 1e-3);
        }
    }

    @Test public void drawSwingsFrontFullyOpenAtMidpointAndRearTrails() {
        assertTrue(sample(Action.DRAW, 0.5));
        assertEquals(Math.PI, OUT[ButterflyFlipCurves.FRONT], 1e-5);
        assertTrue(OUT[ButterflyFlipCurves.REAR] < 0);
        assertTrue(-OUT[ButterflyFlipCurves.REAR] < OUT[ButterflyFlipCurves.FRONT]);
        assertTrue(sample(Action.DRAW, ButterflyFlipCurves.REAR_LAG / 2));
        assertEquals(0, OUT[ButterflyFlipCurves.REAR], 0);
        assertEquals(0, OUT[ButterflyFlipCurves.LOCK], 0);
        assertTrue(OUT[ButterflyFlipCurves.FRONT] > 0);
    }

    @Test public void latchOpensWithTheRearHandle() {
        assertTrue(sample(Action.DRAW, 0.6));
        assertTrue(OUT[ButterflyFlipCurves.LOCK] > 1);
        assertEquals(ButterflyFlipCurves.LATCH_SWING / Math.PI,
                OUT[ButterflyFlipCurves.LOCK] / -OUT[ButterflyFlipCurves.REAR], 1e-5);
    }

    @Test public void inspectFlipsTwice() {
        assertTrue(sample(Action.INSPECT, 0.25));
        assertEquals(Math.PI, OUT[ButterflyFlipCurves.FRONT], 1e-5);
        assertTrue(sample(Action.INSPECT, 0.5));
        assertEquals(0, OUT[ButterflyFlipCurves.FRONT], 1e-5);
        assertTrue(sample(Action.INSPECT, 0.75));
        assertEquals(Math.PI, OUT[ButterflyFlipCurves.FRONT], 1e-5);
    }

    @Test public void hingeRotatesAboutItsPivotAndCarriesDescendants() {
        Vector3f axis = new Vector3f(1, 0, 0);
        Vector3f frontPin = new Vector3f(0, 0.05f, -0.004f);
        Vector3f rearPin = new Vector3f(0, 0.06f, 0.0135f);
        Matrix4f front = ButterflyFlipCurves.hinge(new Matrix4f(), frontPin, axis, (float) Math.PI, new Matrix4f());
        // The front pin stays put; a point one unit up the blade folds down past it.
        assertVector(frontPin, front.transformPosition(new Vector3f(frontPin)));
        assertVector(new Vector3f(0, 0.05f - 0.1f, -0.004f), front.transformPosition(new Vector3f(0, 0.15f, -0.004f)));
        // The rear pin is carried by the front swing; the rear handle then swings about where the pin went.
        Matrix4f rear = ButterflyFlipCurves.hinge(front, rearPin, axis, (float) -Math.PI / 2, new Matrix4f());
        Vector3f carried = front.transformPosition(new Vector3f(rearPin));
        assertVector(carried, rear.transformPosition(new Vector3f(rearPin)));
        // A zero angle leaves a descendant exactly on its parent's delta.
        Matrix4f still = ButterflyFlipCurves.hinge(front, rearPin, axis, 0, new Matrix4f());
        assertTrue(still.equals(front, 1e-6f));
        assertTrue(ButterflyFlipCurves.hinge(new Matrix4f(), frontPin, axis, 0, new Matrix4f())
                .equals(new Matrix4f(), 1e-7f));
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-6);
        assertEquals(expected.y, actual.y, 1e-6);
        assertEquals(expected.z, actual.z, 1e-6);
    }
}
