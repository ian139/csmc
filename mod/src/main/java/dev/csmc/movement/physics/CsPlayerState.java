package dev.csmc.movement.physics;

/**
 * Per-player CS movement state carried between substeps and ticks. Positions are world coordinates in Source
 * units ({@link CsUnits}); {@code origin} is the bottom centre of the collision box, as Minecraft's entity position.
 */
public final class CsPlayerState {
    public final CsVec origin = new CsVec();
    /** Units per second. */
    public final CsVec velocity = new CsVec();
    /** On a walkable surface (plane normal y >= 0.7) and not moving up faster than the non-jump velocity. */
    public boolean grounded;
    /** Normal y of the current ground plane; 1 on flat ground, meaningless while airborne. */
    public double groundNormalY = 1.0;
    /** Touched a non-walkable sloped plane (0 < normal y < 0.7) during the last step: surfing. */
    public boolean surfing;
    /** Jump input of the previous step, for fresh-press detection without auto-bunnyhopping. */
    public boolean jumpHeldLastStep;
    /** CS:GO stamina, 0..{@link CsMovementParams#staminaMax()}. */
    public double stamina;
    /** Set during the last step: the player left the ground by jumping. */
    public boolean jumpedThisStep;
    /** Set during the last step: the player became grounded after being airborne. */
    public boolean landedThisStep;
}
