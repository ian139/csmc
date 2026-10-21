package dev.csmc.movement.physics;

/** Result of a hull sweep, filled in place by {@link CsCollisionWorld#traceHull}. Units, world coordinates. */
public final class CsTrace {
    /** Fraction of the requested move completed, 0..1; 1 means nothing was hit. */
    public double fraction;
    /** Hull origin at {@code fraction}, pulled back from the surface by the trace epsilon. */
    public final CsVec end = new CsVec();
    /** Unit normal of the surface hit; zero when nothing was hit. */
    public final CsVec normal = new CsVec();
    /** The hull started inside a solid. */
    public boolean startSolid;
    /** The hull was inside a solid for the whole move. */
    public boolean allSolid;

    public boolean hit() {
        return fraction < 1.0;
    }
}
