package dev.csmc.balance;

import dev.csmc.ballistics.ArmorModel;
import dev.csmc.ballistics.Ballistics;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRole;

/**
 * Pure expected time-to-kill model for the balance harness (docs/balance.md, "Model").
 *
 * <p>Per hit it reproduces the server pipeline: range falloff {@code rangeModifier^(units/500)}, hit-group
 * multiplier (head = weapon multiplier, body = chest 1.0), {@link ArmorModel#healthDamage} for a covering
 * unbroken piece, then {@link Ballistics#CS_TO_MC_HEALTH}. Each pellet lands inside an aim circle with the
 * probability given by the two-disc spread of {@code Ballistics.applySpread}, standing still, stand inaccuracy
 * plus the accumulated fire penalty. Recoil is assumed to be compensated by the player.
 *
 * <p>Firing follows the server tick by tick ({@code GunSystem.tick}, {@code ShooterState}, {@code Ballistics.fire}):
 * a shot needs {@code tick + 1e-6 >= nextShot}; firing advances {@code nextShot} by
 * {@code max(1, cycle × 20)} fractional ticks, re-anchored to the current tick when more than one tick late. The
 * fire penalty decays every tick with the recovery time of the current spray index, also during reloads; the
 * spray index restarts after more than {@code ceil(cycle × 20) + 4} quiet ticks. An emptied magazine starts its
 * reload on the next tick (the reload request arrives after the firing tick) and finishes after
 * {@code max(1, ceil(reload × 20))} ticks; the cooldown runs concurrently, so the next shot is at the later of the
 * two. The shooter waits at least 0..{@link #TAP_DELAYS} ticks between shots, whichever kills soonest (spray up
 * close, tap far). TTK is the expected first-passage time: shot outcomes are random, and the kill happens at the
 * first shot whose cumulative hits reach the hits needed (first shot at t = 0).
 */
public final class BalanceModel {
    private BalanceModel() {}

    /** Engagement distances in blocks. */
    public enum Band {
        CLOSE(4.0), MEDIUM(16.0), LONG(48.0);

        public final double blocks;

        Band(double blocks) {
            this.blocks = blocks;
        }
    }

    public enum Zone { BODY, HEAD }

    /** Target health in Minecraft points and the bullet protection of its helmet and chest piece (0 = none). */
    public enum Target {
        ZOMBIE("Zombie", 20, 0, 0),
        SKELETON("Skeleton", 20, 0, 0),
        CREEPER("Creeper", 20, 0, 0),
        ENDERMAN("Enderman", 40, 0, 0),
        IRON_GOLEM("Iron golem", 100, 0, 0),
        WARDEN("Warden", 500, 0, 0),
        PLAYER("Player", 20, 0, 0),
        PLAYER_IRON("Player, iron", 20, ArmorModel.IRON_PROTECTION, ArmorModel.IRON_PROTECTION),
        PLAYER_DIAMOND("Player, diamond", 20, ArmorModel.DIAMOND_PROTECTION, ArmorModel.DIAMOND_PROTECTION),
        PLAYER_NETHERITE("Player, netherite", 20, ArmorModel.NETHERITE_PROTECTION, ArmorModel.NETHERITE_PROTECTION),
        PLAYER_KEVLAR("Player, kevlar+helmet", 20, ArmorModel.KEVLAR_PROTECTION, ArmorModel.KEVLAR_PROTECTION);

        public final String label;
        public final double health;
        final double helmet;
        final double vest;

        Target(String label, double health, double helmet, double vest) {
            this.label = label;
            this.health = health;
            this.helmet = helmet;
            this.vest = vest;
        }
    }

    /** Aim-circle radii in blocks: a torso (0.6 × 0.68 box) and the head group of a 1.8-block humanoid. */
    public static final double BODY_RADIUS = 0.3;
    public static final double HEAD_RADIUS = 0.25;
    /** Minimum ticks between shots a skilled shooter may choose (0 = as fast as the weapon allows). */
    static final int[] TAP_DELAYS = {0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 30};
    /** Netherite sword: 8 damage at 1.6 attacks per second, no criticals or sweep. */
    public static final double NETHERITE_SWORD_DAMAGE = 8.0;
    public static final double NETHERITE_SWORD_INTERVAL_S = 1.0 / 1.6;
    /** Give up after this many shots (≈ a full stack of ammunition per kill). */
    public static final int MAX_SHOTS = 300;
    /** Survival probability after {@link #MAX_SHOTS} above which the kill counts as impossible. */
    static final double MAX_SURVIVAL = 0.01;
    static final double TICK_S = 0.05;
    /** {@code Ballistics.SHOT_RESET_GRACE_TICKS}. */
    static final int SHOT_RESET_GRACE_TICKS = 4;

    /** Expected outcome of one weapon against one target; {@code shots} and {@code ttkS} are expectations. */
    public record Result(double hitsNeeded, double shots, double ttkS, double healthPerHit) {
        public boolean killable() {
            return Double.isFinite(ttkS);
        }
    }

    /** One simulated shot: game tick, spray index and total inaccuracy (stance + penalty). */
    record Shot(long tick, int sprayIndex, double inaccuracy) {}

    /** Seconds for a netherite sword to kill {@code health} (first swing at t = 0). */
    public static double swordTtk(double health) {
        return (Math.ceil(health / NETHERITE_SWORD_DAMAGE) - 1.0) * NETHERITE_SWORD_INTERVAL_S;
    }

    /** CS damage of one pellet at {@code blocks}, before hit group and armor; 0 beyond maximum range. */
    public static double damageAt(WeaponData weapon, double blocks) {
        if (blocks > weapon.rangeBlocks()) {
            return 0.0;
        }
        return weapon.damage() * Math.pow(weapon.rangeModifier(), blocks * WeaponData.UNITS_PER_BLOCK / 500.0);
    }

    /** Minecraft health removed by one pellet hit. */
    public static double healthPerHit(WeaponData weapon, Target target, Zone zone, double blocks) {
        double cs = damageAt(weapon, blocks) * (zone == Zone.HEAD ? weapon.headshotMultiplier() : 1.0);
        double protection = zone == Zone.HEAD ? target.helmet : target.vest;
        if (protection > 0.0) {
            cs = ArmorModel.healthDamage(cs, protection, weapon.armorRatio());
        }
        return cs * Ballistics.CS_TO_MC_HEALTH;
    }

    /** Scoped weapons use the scope beyond close range; hip fire up close. */
    public static boolean scoped(WeaponData weapon, Band band) {
        return weapon.zoom().enabled() && band != Band.CLOSE;
    }

    /** Best expected result over the {@link #TAP_DELAYS} cadences. */
    public static Result evaluate(WeaponData weapon, Target target, Zone zone, Band band) {
        Result best = null;
        for (int delay : TAP_DELAYS) {
            Result result = evaluate(weapon, target, zone, band, delay, null);
            if (best == null || result.ttkS() < best.ttkS()) {
                best = result;
            }
        }
        return best;
    }

    /**
     * Expected result when waiting at least {@code tapDelayTicks} between shots; when {@code trace} is non-null
     * it receives every simulated shot.
     */
    static Result evaluate(WeaponData weapon, Target target, Zone zone, Band band, int tapDelayTicks,
            java.util.List<Shot> trace) {
        double perHit = healthPerHit(weapon, target, zone, band.blocks);
        if (!(perHit > 0.0)) {
            return new Result(Double.POSITIVE_INFINITY, 0, Double.POSITIVE_INFINITY, 0.0);
        }
        int hitsNeeded = (int) Math.ceil(target.health / perHit - 1e-9);
        boolean scoped = scoped(weapon, band);
        double cycleS = weapon.activeCycleTimeS(scoped);
        double cycleTicks = Math.max(1.0, cycleS * 20.0);
        long quietTicks = (long) Math.ceil(cycleS * 20.0) + SHOT_RESET_GRACE_TICKS;
        long reloadTicks = Math.max(1, (long) Math.ceil(weapon.reloadTimeS() * 20));
        double stand = weapon.activeInaccuracy(scoped).stand();
        double fire = weapon.activeInaccuracy(scoped).fire();
        double spread = weapon.activeSpreadBase(scoped);
        double radius = (zone == Zone.HEAD ? HEAD_RADIUS : BODY_RADIUS) / band.blocks;
        int pellets = weapon.pellets();

        // alive[k]: probability the target is alive with k hits taken.
        double[] alive = new double[hitsNeeded];
        alive[0] = 1.0;
        double survival = 1.0;
        double expectedTtk = 0.0;
        double expectedShots = 0.0;
        double nextShot = 0.0;
        long lastShot = 0;
        long reloadDone = -1;
        int magazine = weapon.magazineSize();
        int spray = 0;
        double penalty = 0.0;
        long tick = 0;
        for (int shot = 1; shot <= MAX_SHOTS; shot++) {
            if (shot > 1) {
                long ready = Math.max(lastShot + Math.max(1, tapDelayTicks), (long) Math.ceil(nextShot - 1.0e-6));
                if (magazine == 0) {
                    // Reloading clears the scope and any pending bolt rezoom, and zoom requests are rejected until
                    // the reload finishes in the tick's firing pass; the zoom request is accepted on the next tick
                    // (zoom transition time is client presentation only), so scoped fire resumes one tick later.
                    ready = Math.max(ready, scoped ? reloadDone + 1 : reloadDone);
                    magazine = weapon.magazineSize();
                }
                tick = ready;
                double recovery = weapon.recovery().timeS(false, Math.max(1, spray));
                penalty = recovery > 0.0 ? penalty * Math.exp(-(tick - lastShot) * TICK_S * Math.log(10.0) / recovery)
                        : 0.0;
                if (tick - lastShot > quietTicks) {
                    spray = 0;
                }
            }
            spray++;
            double inaccuracy = stand + penalty;
            if (trace != null) {
                trace.add(new Shot(tick, spray, inaccuracy));
            }
            double p = HitProbability.of(inaccuracy, spread, radius);
            double killed = advance(alive, pellets, p);
            expectedTtk += killed * tick * TICK_S;
            expectedShots += killed * shot;
            survival -= killed;
            penalty += fire;
            if (tick - nextShot > 1.0) {
                nextShot = tick;
            }
            nextShot += cycleTicks;
            lastShot = tick;
            if (--magazine == 0) {
                reloadDone = tick + 1 + reloadTicks;
            }
            if (survival < 1.0e-9) {
                break;
            }
        }
        if (survival > MAX_SURVIVAL) {
            return new Result(hitsNeeded, MAX_SHOTS, Double.POSITIVE_INFINITY, perHit);
        }
        // Negligible tail: charge it the last simulated shot.
        expectedTtk += Math.max(0.0, survival) * tick * TICK_S;
        expectedShots += Math.max(0.0, survival) * MAX_SHOTS;
        return new Result(hitsNeeded, expectedShots, expectedTtk, perHit);
    }

    /** Applies one shot of {@code pellets} independent pellets hitting with {@code p}; returns the kill probability. */
    private static double advance(double[] alive, int pellets, double p) {
        int n = alive.length;
        double[] binomial = new double[pellets + 1];
        for (int j = 0; j <= pellets; j++) {
            binomial[j] = choose(pellets, j) * Math.pow(p, j) * Math.pow(1.0 - p, pellets - j);
        }
        double[] next = new double[n];
        double killed = 0.0;
        for (int k = 0; k < n; k++) {
            if (alive[k] == 0.0) {
                continue;
            }
            for (int j = 0; j <= pellets; j++) {
                double mass = alive[k] * binomial[j];
                if (k + j >= n) {
                    killed += mass;
                } else {
                    next[k + j] += mass;
                }
            }
        }
        System.arraycopy(next, 0, alive, 0, n);
        return killed;
    }

    private static double choose(int n, int k) {
        double result = 1.0;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }

    /** Expected rounds (shells for shotguns) per kill divided by the recipe yield: crafts per kill. */
    public static double craftsPerKill(Result result, int recipeYield) {
        return result.killable() ? result.shots() / (double) recipeYield : Double.POSITIVE_INFINITY;
    }

    /** Role band where the weapon is meant to win. */
    public static Band roleBand(WeaponRole role) {
        return switch (role) {
            case PISTOL, SMG, SHOTGUN, ZEUS -> Band.CLOSE;
            case RIFLE, MACHINE_GUN -> Band.MEDIUM;
            case SNIPER, MARKSMAN -> Band.LONG;
        };
    }
}
