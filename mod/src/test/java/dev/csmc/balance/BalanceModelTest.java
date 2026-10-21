package dev.csmc.balance;

import static org.junit.Assert.assertEquals;

import dev.csmc.balance.BalanceModel.Band;
import dev.csmc.balance.BalanceModel.Result;
import dev.csmc.balance.BalanceModel.Shot;
import dev.csmc.balance.BalanceModel.Target;
import dev.csmc.balance.BalanceModel.Zone;
import com.google.gson.JsonParser;
import dev.csmc.data.WeaponData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

/**
 * Model/server correspondence fixtures. Expected values are worked out by hand from the server rules
 * ({@code GunSystem.tick}, {@code startReload}, {@code ShooterState}, {@code Ballistics.fire}), not from the model.
 * All fixtures shoot an unarmored player's body at 4 blocks with no falloff, so damage D needs ceil(20 / (0.2 D)) hits.
 */
public class BalanceModelTest {
    private static WeaponData weapon(String extra) {
        return WeaponData.fromJson(ResourceLocation.fromNamespaceAndPath("csmc", "fixture"), JsonParser.parseString(
                "{\"range_modifier\": 1.0, \"automatic\": true, " + extra + "}"));
    }

    private static List<Long> ticks(WeaponData weapon, int tapDelay) {
        List<Shot> trace = new ArrayList<>();
        BalanceModel.evaluate(weapon, Target.PLAYER, Zone.BODY, Band.CLOSE, tapDelay, trace);
        return trace.stream().map(Shot::tick).toList();
    }

    private static double ttk(WeaponData weapon, int tapDelay) {
        return BalanceModel.evaluate(weapon, Target.PLAYER, Zone.BODY, Band.CLOSE, tapDelay, null).ttkS();
    }

    /** cycle 0.01 s is 0.2 ticks: the one-tick minimum gives ticks 0, 1, 2 for three hits. */
    @Test
    public void oneTickMinimumBetweenShots() {
        WeaponData w = weapon("\"damage\": 35, \"magazine_size\": 30, \"cycle_time_s\": 0.01");
        assertEquals(List.of(0L, 1L, 2L), ticks(w, 0));
        assertEquals(0.10, ttk(w, 0), 1e-9);
    }

    /** cycle 0.075 s = 1.5 ticks: deadlines 1.5, 3, 4.5, 6 give ticks 0, 2, 3, 5, 6. */
    @Test
    public void fractionalFiringDeadlines() {
        WeaponData w = weapon("\"damage\": 20, \"magazine_size\": 30, \"cycle_time_s\": 0.075");
        assertEquals(List.of(0L, 2L, 3L, 5L, 6L), ticks(w, 0));
        assertEquals(0.30, ttk(w, 0), 1e-9);
    }

    /** Magazine 2, cycle 10 ticks: reload starts at tick 11 and overlaps the cooldown to tick 20. */
    @Test
    public void reloadOverlapsCooldown() {
        WeaponData shortReload = weapon("\"damage\": 35, \"magazine_size\": 2, \"cycle_time_s\": 0.5, "
                + "\"reload_time_s\": 0.26");
        assertEquals(List.of(0L, 10L, 20L), ticks(shortReload, 0)); // 0.26 s rounds up to 6 ticks: done at 17
        assertEquals(1.0, ttk(shortReload, 0), 1e-9);
        WeaponData longReload = weapon("\"damage\": 35, \"magazine_size\": 2, \"cycle_time_s\": 0.5, "
                + "\"reload_time_s\": 1.0");
        assertEquals(List.of(0L, 10L, 31L), ticks(longReload, 0)); // 20 ticks from tick 11
        assertEquals(1.55, ttk(longReload, 0), 1e-9);
    }

    /** A 0.01 s reload still takes one tick: start at 1, done at 2. */
    @Test
    public void reloadRoundsUpToWholeTicks() {
        WeaponData w = weapon("\"damage\": 35, \"magazine_size\": 1, \"cycle_time_s\": 0.05, \"reload_time_s\": 0.01");
        assertEquals(List.of(0L, 2L, 4L), ticks(w, 0));
    }

    /**
     * Magazine 1, 1-tick cycle, 0.25 s (5-tick) reload from tick 1 to 6. Hip fire resumes at tick 6; scoped fire
     * (medium band) needs a fresh zoom, accepted only from tick 7 because the reload clears the scope and rejects
     * zoom requests until it finishes in tick 6's firing pass.
     */
    @Test
    public void scopedFireWaitsForRezoomAfterReload() {
        WeaponData w = weapon("\"damage\": 35, \"magazine_size\": 1, \"cycle_time_s\": 0.05, \"reload_time_s\": 0.25, "
                + "\"zoom\": {\"levels\": [40], \"unzoom_after_shot\": true}");
        assertEquals(List.of(0L, 6L, 12L), ticks(w, 0));
        List<Shot> scoped = new ArrayList<>();
        BalanceModel.evaluate(w, Target.PLAYER, Zone.BODY, Band.MEDIUM, 0, scoped);
        assertEquals(List.of(0L, 7L, 14L), scoped.stream().map(Shot::tick).toList());
    }

    /** cycle 2 ticks: the spray resets after more than ceil(2) + 4 = 6 quiet ticks. */
    @Test
    public void sprayIndexResetsAfterQuietTicks() {
        WeaponData w = weapon("\"damage\": 35, \"magazine_size\": 30, \"cycle_time_s\": 0.1");
        List<Shot> taps = new ArrayList<>();
        BalanceModel.evaluate(w, Target.PLAYER, Zone.BODY, Band.CLOSE, 7, taps);
        assertEquals(List.of(1, 1, 1), taps.stream().map(Shot::sprayIndex).toList());
        List<Shot> burst = new ArrayList<>();
        BalanceModel.evaluate(w, Target.PLAYER, Zone.BODY, Band.CLOSE, 6, burst);
        assertEquals(List.of(1, 2, 3), burst.stream().map(Shot::sprayIndex).toList());
    }

    /** Fire penalty 0.1 with a 1 s recovery decays by 10× over the 20 ticks to the next shot, reload included. */
    @Test
    public void penaltyKeepsDecayingThroughReloads() {
        WeaponData w = weapon("\"damage\": 35, \"magazine_size\": 1, \"cycle_time_s\": 0.05, \"reload_time_s\": 0.95, "
                + "\"inaccuracy\": {\"fire\": 0.1}, \"recovery\": {\"stand\": 1.0}");
        List<Shot> trace = new ArrayList<>();
        BalanceModel.evaluate(w, Target.PLAYER, Zone.BODY, Band.CLOSE, 0, trace);
        assertEquals(List.of(0L, 20L, 40L), trace.stream().map(Shot::tick).toList());
        assertEquals(0.0, trace.get(0).inaccuracy(), 1e-12);
        assertEquals(0.01, trace.get(1).inaccuracy(), 1e-12);
        assertEquals(0.011, trace.get(2).inaccuracy(), 1e-12);
    }

    /**
     * One hit kills; spread s puts a fraction 0.075 / s of shots inside the 0.075-tangent body circle. With p per
     * 1 s shot the expected first-passage TTK is (1 − p) / p seconds and the expected shots 1 / p.
     */
    @Test
    public void expectedFirstPassageTtk() {
        Result half = BalanceModel.evaluate(weapon("\"damage\": 200, \"magazine_size\": 100, \"cycle_time_s\": 1.0, "
                + "\"spread_base\": 0.15"), Target.PLAYER, Zone.BODY, Band.CLOSE);
        assertEquals(1.0, half.ttkS(), 1e-6);
        assertEquals(2.0, half.shots(), 1e-6);
        Result threeQuarters = BalanceModel.evaluate(weapon("\"damage\": 200, \"magazine_size\": 100, "
                + "\"cycle_time_s\": 1.0, \"spread_base\": 0.1"), Target.PLAYER, Zone.BODY, Band.CLOSE);
        assertEquals(1.0 / 3.0, threeQuarters.ttkS(), 1e-6);
    }
}
