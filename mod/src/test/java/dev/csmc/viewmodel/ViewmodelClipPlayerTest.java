package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.csmc.melee.KnifeClipChooser;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class ViewmodelClipPlayerTest {
    private static final long S = 1_000_000_000L;
    private static final Map<String, Float> LENGTHS = Map.of("idle1_x", 2f, "idle2_x", 3f, "light_miss1_x", 1f,
            "light_miss2_x", 1f, "lookat01_x", 4f, "heavy_backstab_x", 1.5f);
    private static final ViewmodelClipChooser.Durations DURATIONS = clip -> LENGTHS.getOrDefault(clip, 0f);
    private static final Map<String, String> ANIMATIONS = Map.of("idle", "idle1_x", "slash", "light_miss1_x",
            "inspect", "lookat01_x", "backstab", "heavy_backstab_x", "slash_backstab", "light_backstab_x");

    private final Random random = new Random(3);

    private static KnifeClipChooser chooser(List<String> loaded) {
        return new KnifeClipChooser(ANIMATIONS, loaded);
    }

    private static ViewmodelCue cue(String action) {
        return ViewmodelCue.of(action, 0, -1, 0);
    }

    @Test
    public void idleLoopsAlternatingVariants() {
        KnifeClipChooser chooser = chooser(List.copyOf(LENGTHS.keySet()));
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        assertTrue(player.update(chooser, DURATIONS, null, 0, 0, -1, 0, random));
        assertEquals("idle1_x", player.clip());
        assertTrue(player.update(chooser, DURATIONS, null, 0, 0, -1, 2 * S + S / 2, random));
        assertEquals("idle2_x", player.clip());
        assertEquals(.5f, player.time(), 1e-3);
    }

    @Test
    public void missingIdleVariantNeverStallsWhenChooserUsesLoadedClips() {
        KnifeClipChooser chooser = chooser(List.of("idle1_x", "light_miss1_x"));
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        for (int second = 0; second < 10; second++) {
            assertTrue(player.update(chooser, DURATIONS, null, 0, 0, -1, second * S, random));
            assertEquals("idle1_x", player.clip());
        }
    }

    @Test
    public void actionPlaysThenReturnsToIdle() {
        KnifeClipChooser chooser = chooser(List.copyOf(LENGTHS.keySet()));
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        ViewmodelCue slash = cue("slash");
        assertTrue(player.update(chooser, DURATIONS, slash, .4f, .3f, -1, 0, random));
        assertTrue(player.clip().startsWith("light_miss"));
        assertEquals(.4f, player.time(), 1e-6);
        assertTrue(player.update(chooser, DURATIONS, slash, 1.2f, .3f, -1, S, random));
        assertTrue(player.clip().startsWith("idle"));
    }

    @Test
    public void consecutiveSlashesAlternate() {
        KnifeClipChooser chooser = chooser(List.copyOf(LENGTHS.keySet()));
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        player.update(chooser, DURATIONS, cue("slash"), 0, 0, -1, 0, random);
        String first = player.clip();
        player.update(chooser, DURATIONS, cue("slash"), 0, 0, -1, 0, random);
        assertNotEquals(first, player.clip());
    }

    @Test
    public void missingClipUsesFallbackWindowThenIdle() {
        KnifeClipChooser chooser = chooser(List.of("idle1_x"));
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        ViewmodelCue inspect = cue("inspect");
        assertFalse(player.update(chooser, DURATIONS, inspect, 1f, 2.5f, -1, 0, random));
        assertTrue(player.update(chooser, DURATIONS, inspect, 2.6f, 2.5f, -1, S, random));
        assertEquals("idle1_x", player.clip());
    }

    @Test
    public void slashBackstabFallsBackToBackstabClip() {
        KnifeClipChooser chooser = chooser(List.copyOf(LENGTHS.keySet()));
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        assertTrue(player.update(chooser, DURATIONS, cue("slash_backstab"), 0, 0, -1, 0, random));
        assertEquals("heavy_backstab_x", player.clip());
    }

    @Test
    public void handsKeepSeparateState() {
        KnifeClipChooser chooser = chooser(List.copyOf(LENGTHS.keySet()));
        ViewmodelClipPlayer main = new ViewmodelClipPlayer();
        ViewmodelClipPlayer off = new ViewmodelClipPlayer();
        ViewmodelCue inspect = cue("inspect");
        main.update(chooser, DURATIONS, inspect, 1, 0, -1, 0, random);
        off.update(chooser, DURATIONS, null, 0, 0, -1, 0, random);
        main.update(chooser, DURATIONS, inspect, 1.1f, 0, -1, 0, random);
        assertEquals("lookat01_x", main.clip());
        assertTrue(off.clip().startsWith("idle"));
    }

    @Test
    public void gunReloadScalesToServerDurationThenIdles() {
        GunClipChooser chooser = new GunClipChooser(Map.of("idle", "idle", "reload", "reload"),
                List.of("idle", "reload"), false);
        Map<String, Float> lengths = Map.of("idle", 4f, "reload", 2f);
        ViewmodelClipChooser.Durations durations = clip -> lengths.getOrDefault(clip, 0f);
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        ViewmodelCue reload = ViewmodelCue.of("reload", 3f, 4, 16);
        assertTrue(player.update(chooser, durations, reload, 1.5f, 3f, 4, 0, random));
        assertEquals("reload", player.clip());
        assertEquals(1f, player.time(), 1e-5);
        assertTrue(player.update(chooser, durations, reload, 2.99f, 3f, 4, 0, random));
        assertEquals(1.993f, player.time(), 1e-3);
        assertTrue(player.update(chooser, durations, reload, 3.01f, 3f, 20, 0, random));
        assertEquals("idle", player.clip());
    }

    @Test
    public void eachShotRestartsFire() {
        GunClipChooser chooser = new GunClipChooser(Map.of("idle", "idle", "fire", "shoot"), List.of("idle", "shoot"),
                false);
        ViewmodelClipChooser.Durations durations = clip -> 1f;
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        player.update(chooser, durations, ViewmodelCue.of("fire", 0, 9, 0), .09f, 1, 9, 0, random);
        assertEquals(.09f, player.time(), 1e-6);
        player.update(chooser, durations, ViewmodelCue.of("fire", 0, 8, 0), 0, 1, 8, 0, random);
        assertEquals("shoot", player.clip());
        assertEquals(0, player.time(), 1e-6);
    }

    @Test
    public void gunActionWithoutAClipKeepsTheCallersFallbackForItsWindow() {
        GunClipChooser chooser = new GunClipChooser(Map.of("idle", "idle"), List.of("idle"), false);
        ViewmodelClipChooser.Durations durations = clip -> 1f;
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        ViewmodelCue reload = ViewmodelCue.of("reload", 2.5f, 4, 16);
        // No viewmodel reload clip: the world-model reload runs for the server's 2.5 s instead of idle.
        assertFalse(player.update(chooser, durations, reload, 1f, 2.5f, 4, 0, random));
        assertTrue(player.update(chooser, durations, reload, 2.6f, 2.5f, 20, 0, random));
        assertEquals("idle", player.clip());
    }

    @Test
    public void emptyMagazineSwitchesTheIdlePose() {
        GunClipChooser chooser = new GunClipChooser(Map.of("idle", "idle", "idle_empty", "slide_back"),
                List.of("idle", "slide_back"), false);
        ViewmodelClipChooser.Durations durations = clip -> 0f;
        ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        assertTrue(player.update(chooser, durations, null, 0, 0, 3, 0, random));
        assertEquals("idle", player.clip());
        assertTrue(player.update(chooser, durations, null, 0, 0, 0, S, random));
        assertEquals("slide_back", player.clip());
    }
}
