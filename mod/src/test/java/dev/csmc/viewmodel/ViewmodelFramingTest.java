package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

public class ViewmodelFramingTest {
    @Test
    public void cs2DefaultViewmodelFovIsAbout47DegreesVertical() {
        assertEquals(60, ViewmodelFraming.VIEWMODEL_FOV, 0);
        assertEquals(46.82645, Math.toDegrees(ViewmodelFraming.verticalFov(60)), 1e-5);
        assertEquals(73.74, Math.toDegrees(ViewmodelFraming.verticalFov(90)), .01);
    }

    @Test
    public void projectionIgnoresPlayerFovAndPreservesAspectAndDepth() {
        for (float aspect : new float[]{4f / 3, 16f / 9, 21f / 9}) {
            for (float handFov : new float[]{30, 70, 110}) {
                Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(handFov), aspect, .05f, 1024f);
                float depthScale = projection.m22();
                float depthOffset = projection.m32();
                assertSame(projection, ViewmodelFraming.applyProjection(projection));
                assertEquals(46.82645, Math.toDegrees(2 * Math.atan(1 / projection.m11())), 1e-4);
                assertEquals(aspect, projection.m11() / projection.m00(), 1e-5);
                assertEquals(depthScale, projection.m22(), 0);
                assertEquals(depthOffset, projection.m32(), 0);
                assertEquals(-1, projection.m23(), 0);
            }
        }
    }

    @Test
    public void projectionStaysInViewAxesAtEveryCameraHeading() {
        Matrix4f projection = ViewmodelFraming.applyProjection(new Matrix4f().perspective(1.2f, 16f / 9, .05f, 1024));
        Matrix4f bob = new Matrix4f().rotationXYZ(.015f, -.02f, .01f).translate(.01f, -.02f, 0);
        Matrix4f expected = new Matrix4f(projection).mul(bob);
        for (float yaw : new float[]{0, 45, 90, 180, -90}) {
            for (float pitch : new float[]{-70, -10, 0, 10, 70}) {
                Matrix4f modelView = new Matrix4f().rotationXYZ((float) Math.toRadians(pitch),
                        (float) Math.toRadians(yaw), 0);
                // Minecraft starts the hand stack with inverse(camera). The entity shader applies camera again.
                Matrix4f handPose = new Matrix4f(modelView).invert().mul(bob);
                assertTrue("camera pitch=" + pitch + ", yaw=" + yaw,
                        new Matrix4f(projection).mul(modelView).mul(handPose).equals(expected, 1e-5f));
            }
        }
    }

    @Test
    public void exportedSourceAxesConvertToMinecraftViewExactlyOnce() {
        // Base rotation exported by VRF on root_motion and weapon: Source X/Y/Z → glTF Z/X/Y.
        Matrix4f exported = new Matrix4f().rotation(new Quaternionf(-.5f, -.5f, -.5f, .5f));
        Matrix4f toView = ViewmodelFraming.clipToView(false, new Matrix4f()).mul(exported);
        assertVector(0, 0, -1, toView.transformDirection(new Vector3f(1, 0, 0))); // forward
        assertVector(-1, 0, 0, toView.transformDirection(new Vector3f(0, 1, 0))); // left
        assertVector(0, 1, 0, toView.transformDirection(new Vector3f(0, 0, 1))); // up
        assertEquals(1, toView.determinant(), 1e-6);
    }

    @Test
    public void leftHandReflectsOnlyTheViewHorizontalAxis() {
        Matrix4f right = ViewmodelFraming.clipToView(false, new Matrix4f());
        Matrix4f left = ViewmodelFraming.clipToView(true, new Matrix4f());
        Vector3f r = right.transformPosition(new Vector3f(-.15f, -.1f, .45f));
        Vector3f l = left.transformPosition(new Vector3f(-.15f, -.1f, .45f));
        assertVector(-r.x, r.y, r.z, l);
        assertEquals(-1, left.determinant(), 1e-6); // reflected winding must be reversed when drawing
        assertEquals(.0254f, ViewmodelFraming.OFFSET_RIGHT, 0);
        assertEquals(.0254f, ViewmodelFraming.OFFSET_FORWARD, 0);
        assertEquals(-.0254f, ViewmodelFraming.OFFSET_UP, 0);
    }

    private static void assertVector(float x, float y, float z, Vector3f actual) {
        assertEquals(x, actual.x, 1e-6);
        assertEquals(y, actual.y, 1e-6);
        assertEquals(z, actual.z, 1e-6);
    }
}
