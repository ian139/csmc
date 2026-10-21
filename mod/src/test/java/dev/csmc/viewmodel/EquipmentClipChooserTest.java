package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class EquipmentClipChooserTest {
    private static final Map<String, Float> LENGTHS = Map.ofEntries(Map.entry("idle", 3f), Map.entry("draw", 1f),
            Map.entry("inject", 4f), Map.entry("look1", 2f), Map.entry("look2", 2.5f), Map.entry("look3", 3f));
    private static final ViewmodelClipChooser.Durations DURATIONS = clip -> LENGTHS.getOrDefault(clip, 0f);
    private static final Map<String, String> HEALTHSHOT = Map.of("idle", "idle", "draw", "draw", "use", "inject",
            "inspect", "look1", "inspect_alt", "look2", "inspect_alt2", "look3");
    private final Random random = new Random(1);
    private final ClipPlan plan = new ClipPlan();

    private static EquipmentClipChooser chooser(Map<String, String> animations) {
        return new EquipmentClipChooser(animations, LENGTHS.keySet());
    }

    private void plan(EquipmentClipChooser chooser, String action, float seconds, int magazine, int rounds) {
        chooser.plan(ViewmodelCue.of(action, seconds, magazine, rounds), DURATIONS, random, plan);
    }

    @Test
    public void choosesTheMappedClipPerAction() {
        EquipmentClipChooser chooser = chooser(Map.of("idle", "idle", "draw", "draw", "use", "inject"));
        for (var expected : Map.of("draw", "draw", "use", "inject").entrySet()) {
            plan(chooser, expected.getKey(), 0, -1, 0);
            assertEquals(1, plan.size());
            assertEquals(expected.getValue(), plan.clip(0));
        }
        assertEquals("idle", chooser.idle(0, -1));
        assertEquals("idle", chooser.idle(7, -1));
        plan(chooser, "slash", 0, -1, 0);
        assertTrue(plan.isEmpty());
    }

    @Test
    public void noFirearmRulesApply() {
        EquipmentClipChooser chooser = new EquipmentClipChooser(
                Map.of("draw", "draw", "fire", "draw", "reload", "inject", "use", "inject"), LENGTHS.keySet());
        plan(chooser, "fire", 0, 0, 0);
        assertTrue(plan.isEmpty());
        plan(chooser, "reload", 2.5f, 0, 17);
        assertTrue(plan.isEmpty());
        plan(chooser, "draw", 5f, 0, 17);
        assertEquals(1, plan.size());
        assertEquals("draw", plan.clip(0));
        assertEquals(1f, plan.total(), 1e-6);
    }

    @Test
    public void useScalesTheClipToTheServerDuration() {
        EquipmentClipChooser chooser = chooser(HEALTHSHOT);
        plan(chooser, "use", 3f, 0, 0);
        assertEquals(1, plan.size());
        assertEquals("inject", plan.clip(0));
        assertEquals(3f, plan.total(), 1e-6);
        assertEquals(1f, plan.clipTime(plan.segment(0.75f), 0.75f), 1e-5);
        assertEquals(2f, plan.clipTime(plan.segment(1.5f), 1.5f), 1e-5);
        assertEquals(4f, plan.clipTime(plan.segment(3f), 3f), 1e-5);
    }

    @Test
    public void useWithoutServerDurationPlaysNaturalSpeed() {
        EquipmentClipChooser chooser = chooser(HEALTHSHOT);
        plan(chooser, "use", 0, -1, 0);
        assertEquals(1, plan.size());
        assertEquals(4f, plan.total(), 1e-6);
    }

    @Test
    public void drawIsNaturalSpeed() {
        EquipmentClipChooser chooser = chooser(HEALTHSHOT);
        plan(chooser, "draw", 5f, -1, 0);
        assertEquals(1, plan.size());
        assertEquals("draw", plan.clip(0));
        assertEquals(1f, plan.total(), 1e-6);
    }

    @Test
    public void inspectChoosesAmongLoadedInspectClips() {
        EquipmentClipChooser chooser = chooser(HEALTHSHOT);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            plan(chooser, "inspect", 0, -1, 0);
            assertEquals(1, plan.size());
            seen.add(plan.clip(0));
            assertEquals(LENGTHS.get(plan.clip(0)), plan.total(), 1e-6);
        }
        assertEquals(Set.of("look1", "look2", "look3"), seen);
    }

    @Test
    public void inspectSkipsUnloadedClips() {
        EquipmentClipChooser chooser = new EquipmentClipChooser(
                Map.of("inspect", "missing", "inspect_alt", "look2"), LENGTHS.keySet());
        for (int i = 0; i < 20; i++) {
            plan(chooser, "inspect", 0, -1, 0);
            assertEquals(1, plan.size());
            assertEquals("look2", plan.clip(0));
        }
    }

    @Test
    public void inspectWithoutLoadedClipsPlansNothing() {
        EquipmentClipChooser chooser = new EquipmentClipChooser(
                Map.of("inspect", "missing", "inspect_alt2", "missing2"), LENGTHS.keySet());
        plan(chooser, "inspect", 0, -1, 0);
        assertTrue(plan.isEmpty());
    }

    @Test
    public void missingClipIsIgnored() {
        EquipmentClipChooser chooser = new EquipmentClipChooser(
                Map.of("idle", "idle", "draw", "draw", "use", "inject"), List.of("idle", "inject"));
        plan(chooser, "draw", 0, -1, 0);
        assertTrue(plan.isEmpty());
        plan(chooser, "use", 3f, -1, 0);
        assertEquals(1, plan.size());
        assertEquals("inject", plan.clip(0));
        assertEquals(3f, plan.total(), 1e-6);
        assertEquals("idle", chooser.idle(0, -1));
        plan(chooser, "inspect", 0, -1, 0);
        assertTrue(plan.isEmpty());
    }

    @Test
    public void idleIsNullWithoutLoadedIdleClip() {
        EquipmentClipChooser chooser = new EquipmentClipChooser(Map.of("idle", "missing"), LENGTHS.keySet());
        assertNull(chooser.idle(0, -1));
    }
}
