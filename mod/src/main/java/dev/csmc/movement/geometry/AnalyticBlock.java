package dev.csmc.movement.geometry;

/**
 * A block whose solids are also emitted by a {@link MovementGeometry} provider. The shared movement collision
 * iterator omits its vanilla boxes for analytic entities; direct shape queries and ray clips remain solid.
 */
public interface AnalyticBlock {}
