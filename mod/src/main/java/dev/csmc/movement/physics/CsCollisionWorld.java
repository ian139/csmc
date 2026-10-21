package dev.csmc.movement.physics;

/** Hull tracing for {@code CsGameMovement}. Coordinates are world Source units on Minecraft axes. */
public interface CsCollisionWorld {
    /**
     * Sweeps an axis-aligned box with horizontal half extent {@code halfWidth} and {@code height} (its origin at
     * the bottom centre) from {@code (sx, sy, sz)} to {@code (ex, ey, ez)} and writes the result into {@code out}.
     */
    void traceHull(double sx, double sy, double sz, double ex, double ey, double ez, double halfWidth, double height,
            CsTrace out);
}
