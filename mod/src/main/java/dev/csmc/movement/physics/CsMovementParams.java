package dev.csmc.movement.physics;

/**
 * Server movement variables, named after their CS:GO cvars; speeds in units/s, accelerations dimensionless as in
 * Source. {@code tickRate} is the simulated server tick rate: each 20 Hz Minecraft tick runs
 * {@code max(1, round(tickRate / 20))} substeps (approximation: 64 tick runs as 3 substeps of 1/60 s, 85.3 tick as 4
 * of 1/80 s, 128 tick as 6 of 1/120 s).
 *
 * <p>Every preset value and provenance limitation is cited in {@code docs/dev/movement-reference.md}.
 * {@link #COMPETITIVE} uses documented CS:GO cvar defaults; {@link #BHOP} and {@link #SURF} combine community
 * conventions with retained defaults and project-selected nominal tick rates, not complete third-party configs.
 * Exact cvar observations:
 * <a href="https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4130-L4558">CS:GO
 * 1.37.7.5 cvarlist capture</a>.
 * <a href="https://developer.valvesoftware.com/wiki/List_of_Counter-Strike:_Global_Offensive_console_commands_and_variables">VDC</a>
 * rounds some values; the pinned
 * <a href="https://github.com/SteamTracking/GameTracking-CS2/blob/e63fc7fdb8dfb4f1873f0db214c0cc16613d5beb/csgo/bin/linux64/server_client_strings.txt#L28606-L28950">SteamTracking
 * CS:GO inventory</a> records cvar names, not defaults.
 */
public record CsMovementParams(
        double gravity,
        double accelerate,
        double airAccelerate,
        double airMaxWishSpeed,
        double friction,
        double stopSpeed,
        double maxVelocity,
        double jumpImpulse,
        boolean enableBunnyhopping,
        boolean autoBunnyhopping,
        double staminaMax,
        double staminaJumpCost,
        double staminaLandCost,
        double staminaRecoveryRate,
        double duckSpeedModifier,
        double walkSpeedModifier,
        int tickRate) {

    /**
     * CS:GO 1.37.7.5 documented defaults, nominal 64 tick: sv_gravity 800, sv_accelerate 5.5, sv_airaccelerate 12,
     * sv_air_max_wishspeed 30, sv_friction 5.2, sv_stopspeed 80, sv_maxvelocity 3500, sv_jump_impulse 301.993377,
     * sv_enablebunnyhopping 0, sv_autobunnyhopping 0, sv_staminamax 80, sv_staminajumpcost .080,
     * sv_staminalandcost .050, sv_staminarecoveryrate 60 (capture linked above).
     * Duck x0.34 and walk x0.52 are community-documented approximations (no official Valve primary source).
     * The <a href="https://counterstrike.fandom.com/wiki/Movement">public movement documentation</a> describes
     * CS:GO walking slowdown but does not establish these exact ratios; see the explicit limitation in the docs.
     * Nominal 64/128 rates follow
     * <a href="https://github.com/zer0k-z/cs2-movement-issue-bhop/blob/58e80d1f462dcf31d14ca7b02847e72b3cb421bc/README.md#L53-L57">community
     * CS:GO matchmaking/community-server descriptions</a>; actual substep rates are approximate.
     */
    public static final CsMovementParams COMPETITIVE = new CsMovementParams(800, 5.5, 12, 30, 5.2, 80, 3500,
            301.993377, false, false, 80, 0.08, 0.05, 60, 0.34, 0.52, 64);
    /**
     * Community-inspired bunnyhop preset:
     * <a href="https://github.com/shavitush/bhoptimer/blob/2c87128446302f534e1fd0147090b83be3256a6e/addons/sourcemod/configs/shavit-styles.cfg#L15-L39">shavit
     * Normal</a> supplies sv_airaccelerate 1000 and enabled auto/bunnyhopping. Zero stamina max/costs adopt the
     * <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=1436068277">community skill-surf config</a>,
     * not shavit's different easybhop mechanism. Other values retain competitive defaults; nominal 128 is a project
     * selection, not a tick rate specified by shavit's config.
     */
    public static final CsMovementParams BHOP = new CsMovementParams(800, 5.5, 1000, 30, 5.2, 80, 3500,
            301.993377, true, true, 0, 0, 0, 60, 0.34, 0.52, 128);
    /**
     * Community-inspired surf preset:
     * <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=1436068277">mezz's skill-surf config</a>
     * supplies sv_airaccelerate 150, sv_accelerate 10, bunny/auto-bunnyhopping and zero stamina max/costs.
     * Other values retain competitive defaults (including inert stamina recovery 60; that guide uses 0).
     * Nominal 85 is a project integer approximation of the
     * <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=477486389">community 85.3-tick convention</a>,
     * not a verified SurfTimer global config or Valve default.
     */
    public static final CsMovementParams SURF = new CsMovementParams(800, 10, 150, 30, 5.2, 80, 3500,
            301.993377, true, true, 0, 0, 0, 60, 0.34, 0.52, 85);

    public int substepsPerTick() {
        return Math.max(1, (int) Math.round(tickRate / CsUnits.TICKS_PER_SECOND));
    }
}
