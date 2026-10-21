package dev.csmc.melee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

public class ViewmodelAdjustmentTest {
    private static final float[] DEFAULT = {-.1f, .15f, 0, -159.72f, -9.39f, -176.55f, 1.5f};
    private static final Vector3f ANCHOR = new Vector3f(.14f, -.16f, -.42f);

    private static Matrix4f pivoted(float[] configured, boolean left) {
        Matrix4f adjustment = ViewmodelAdjustment.adjustment(configured, DEFAULT, left, new Matrix4f(),
                new Quaternionf(), new Quaternionf());
        return new Matrix4f().translation(ANCHOR).mul(adjustment).translate(-ANCHOR.x, -ANCHOR.y, -ANCHOR.z);
    }

    @Test
    public void defaultIsIdentity() {
        assertTrue(pivoted(DEFAULT, false).equals(new Matrix4f(), 1e-5f));
        assertTrue(pivoted(DEFAULT, true).equals(new Matrix4f(), 1e-5f));
    }

    @Test
    public void scaleOnlyOverrideKeepsTheHandAnchorFixed() {
        float[] bigger = DEFAULT.clone();
        bigger[6] = 3f;
        Matrix4f m = pivoted(bigger, false);
        Vector3f anchor = m.transformPosition(new Vector3f(ANCHOR));
        assertEquals(0, anchor.distance(ANCHOR), 1e-5f);
        // And it scales by s_c / s_d = 2 about that anchor.
        Vector3f moved = m.transformPosition(new Vector3f(ANCHOR).add(.1f, 0, 0));
        assertEquals(.2f, moved.distance(ANCHOR), 1e-5f);
    }

    @Test
    public void translationOnlyOverrideMovesByTheDifferenceMirroredForLeftHand() {
        float[] shifted = DEFAULT.clone();
        shifted[0] += .05f;
        shifted[1] -= .02f;
        Vector3f right = pivoted(shifted, false).transformPosition(new Vector3f(ANCHOR)).sub(ANCHOR);
        Vector3f left = pivoted(shifted, true).transformPosition(new Vector3f(ANCHOR)).sub(ANCHOR);
        assertEquals(.05f, right.x, 1e-5f);
        assertEquals(-.02f, right.y, 1e-5f);
        assertEquals(-.05f, left.x, 1e-5f);
        assertEquals(-.02f, left.y, 1e-5f);
    }
}
