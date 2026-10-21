package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class GunClipChooserTest {
    private static final Map<String, Float> LENGTHS = Map.ofEntries(Map.entry("idle", 3f), Map.entry("draw", 1f),
            Map.entry("shoot1", .5f), Map.entry("shoot_empty", .5f), Map.entry("shoot_left", .4f),
            Map.entry("shoot_right", .4f), Map.entry("shoot_leftlast", .4f), Map.entry("shoot_rightlast", .4f),
            Map.entry("bolt", .8f), Map.entry("reload", 2f), Map.entry("reload_empty", 2.2f),
            Map.entry("reload_start", .5f), Map.entry("reload_loop", .5f), Map.entry("reload_end", .5f),
            Map.entry("lookat", 5f), Map.entry("idle_slide_back", 0f), Map.entry("idle_leftempty", 0f),
            Map.entry("idle_bothempty", 0f));
    private static final ViewmodelClipChooser.Durations DURATIONS = clip -> LENGTHS.getOrDefault(clip, 0f);
    private static final Map<String, String> DUAL = Map.of("idle", "idle", "fire_right", "shoot_right",
            "fire_left", "shoot_left", "fire_right_last", "shoot_rightlast", "fire_left_last", "shoot_leftlast",
            "idle_left_empty", "idle_leftempty", "idle_empty", "idle_bothempty");
    private final Random random = new Random(1);
    private final ClipPlan plan = new ClipPlan();

    private static GunClipChooser chooser(Map<String, String> animations) {
        return new GunClipChooser(animations, LENGTHS.keySet(), false);
    }

    private void plan(GunClipChooser chooser, String action, float seconds, int magazine, int rounds) {
        chooser.plan(ViewmodelCue.of(action, seconds, magazine, rounds), DURATIONS, random, plan);
    }

    @Test
    public void choosesTheMappedClipPerAction() {
        GunClipChooser chooser = chooser(Map.of("idle", "idle", "draw", "draw", "fire", "shoot1", "inspect", "lookat"));
        for (var expected : Map.of("draw", "draw", "fire", "shoot1", "inspect", "lookat").entrySet()) {
            plan(chooser, expected.getKey(), 0, 5, 0);
            assertEquals(1, plan.size());
            assertEquals(expected.getValue(), plan.clip(0));
            assertEquals(LENGTHS.get(expected.getValue()), plan.total(), 1e-6);
        }
        assertEquals("idle", chooser.idle(0, 5));
        assertEquals("idle", chooser.idle(7, 5));
        plan(chooser, "slash", 0, 5, 0);
        assertTrue(plan.isEmpty());
    }

    @Test
    public void unloadedClipsAreIgnored() {
        GunClipChooser chooser = new GunClipChooser(Map.of("idle", "idle", "fire", "missing"), List.of("idle"), false);
        plan(chooser, "fire", 0, 5, 0);
        assertTrue(plan.isEmpty());
        assertNull(chooser.clip("fire"));
    }

    @Test
    public void reloadIsScaledToTheServerDuration() {
        GunClipChooser chooser = chooser(Map.of("reload", "reload"));
        plan(chooser, "reload", 2.5f, 3, 17);
        assertEquals(1, plan.size());
        assertEquals(2.5f, plan.total(), 1e-6);
        assertEquals(1f, plan.clipTime(plan.segment(1.25f), 1.25f), 1e-5);
        assertEquals(2f, plan.clipTime(plan.segment(2.5f), 2.5f), 1e-5);
    }

    @Test
    public void shotgunReloadLoopsOncePerShellTheServerLoads() {
        GunClipChooser chooser = chooser(Map.of("reload", "reload", "reload_start", "reload_start",
                "reload_loop", "reload_loop", "reload_end", "reload_end"));
        // Five shells in a 3.5 s reload: start + 5 loops + end, scaled to the server's duration.
        plan(chooser, "reload", 3.5f, 3, 5);
        assertEquals(7, plan.size());
        assertEquals("reload_start", plan.clip(0));
        for (int i = 1; i <= 5; i++) assertEquals("reload_loop", plan.clip(i));
        assertEquals("reload_end", plan.clip(6));
        assertEquals(3.5f, plan.total(), 1e-5);
        // One shell in the same 3.5 s: start + 1 loop + end, each slowed to fill the reload.
        plan(chooser, "reload", 3.5f, 7, 1);
        assertEquals(3, plan.size());
        assertEquals("reload_loop", plan.clip(1));
        assertEquals(3.5f, plan.total(), 1e-5);
    }

    @Test
    public void singleShellGunLoopsItsReloadClipPerShell() {
        GunClipChooser chooser = new GunClipChooser(Map.of("reload", "reload"), LENGTHS.keySet(), true);
        plan(chooser, "reload", 6f, 5, 3);
        assertEquals(3, plan.size());
        for (int i = 0; i < 3; i++) assertEquals("reload", plan.clip(i));
        assertEquals(6f, plan.total(), 1e-5);
        assertEquals(1f, plan.clipTime(1, 3f), 1e-5);
        plan(chooser, "reload", 6f, 7, 1);
        assertEquals(1, plan.size());
        assertEquals(6f, plan.total(), 1e-5);
        // Shell counts beyond the plan's capacity are bounded, never dropped to zero.
        plan(chooser, "reload", 6f, 0, 1000);
        assertEquals(ClipPlan.CAPACITY, plan.size());
    }

    @Test
    public void lastShotAndEmptyReloadComeFromTheEventsMagazine() {
        GunClipChooser chooser = chooser(Map.of("fire", "shoot1", "fire_last", "shoot_empty", "reload", "reload",
                "reload_empty", "reload_empty"));
        plan(chooser, "fire", 0, 1, 0);
        assertEquals("shoot1", plan.clip(0));
        plan(chooser, "fire", 0, 0, 0);
        assertEquals("shoot_empty", plan.clip(0));
        plan(chooser, "reload", 2f, 0, 20);
        assertEquals("reload_empty", plan.clip(0));
        plan(chooser, "reload", 2f, 4, 16);
        assertEquals("reload", plan.clip(0));
        // Without variants, the plain clips play.
        GunClipChooser plain = chooser(Map.of("fire", "shoot1", "reload", "reload"));
        plan(plain, "fire", 0, 0, 0);
        assertEquals("shoot1", plan.clip(0));
        plan(plain, "reload", 2f, 0, 20);
        assertEquals("reload", plan.clip(0));
    }

    @Test
    public void dualFireSideFollowsTheShotsMagazineNotRenderedPlans() {
        GunClipChooser chooser = chooser(DUAL);
        // Left fires the shots that leave an odd count, right the even ones: whatever order plans are rendered in.
        plan(chooser, "fire", 0, 29, 0);
        assertEquals("shoot_left", plan.clip(0));
        plan(chooser, "fire", 0, 27, 0); // shot 28 was never rendered
        assertEquals("shoot_left", plan.clip(0));
        plan(chooser, "fire", 0, 26, 0);
        assertEquals("shoot_right", plan.clip(0));
        plan(chooser, "fire", 0, 1, 0);
        assertEquals("shoot_leftlast", plan.clip(0));
        plan(chooser, "fire", 0, 0, 0);
        assertEquals("shoot_rightlast", plan.clip(0));
    }

    @Test
    public void emptyIdlesFollowTheMagazine() {
        GunClipChooser single = chooser(Map.of("idle", "idle", "idle_empty", "idle_slide_back"));
        assertEquals("idle", single.idle(0, 3));
        assertEquals("idle_slide_back", single.idle(0, 0));
        assertEquals("idle", single.idle(0, -1));
        GunClipChooser dual = chooser(DUAL);
        assertEquals("idle", dual.idle(0, 2));
        assertEquals("idle_leftempty", dual.idle(0, 1));
        assertEquals("idle_bothempty", dual.idle(0, 0));
        // Unmapped empty idles fall back to the ordinary idle.
        assertEquals("idle", chooser(Map.of("idle", "idle")).idle(0, 0));
    }

    @Test
    public void sniperFireIsFollowedByTheBoltCycle() {
        GunClipChooser chooser = chooser(Map.of("fire", "shoot1", "cycle", "bolt"));
        plan(chooser, "fire", 0, 4, 0);
        assertEquals(2, plan.size());
        assertEquals("shoot1", plan.clip(0));
        assertEquals("bolt", plan.clip(1));
        assertEquals(1.3f, plan.total(), 1e-6);
        assertEquals(.2f, plan.clipTime(plan.segment(.7f), .7f), 1e-5);
    }
}
