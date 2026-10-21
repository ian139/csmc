package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class EquipmentSoundPlanTest {
    private static final String CACHE = """
            {"weapons": {"c4": {"kind": "equipment",
              "viewmodel_animations": {"use": "plant_c4", "draw": "draw_c4"},
              "viewmodel_clips": {"plant_c4": {"duration_seconds": 4.0}, "draw_c4": {"duration_seconds": 1.2333}},
              "viewmodel_markers": {"plant_c4": [
                {"type": "CNmSoundEvent", "name": "c4.keypressquiet", "time_seconds": 0.666668},
                {"type": "CNmIDEvent", "name": "WPN_BOMB_STAGE", "time_seconds": 0.3},
                {"type": "CNmSoundEvent", "name": "c4.initiate", "time_seconds": 0.1},
                {"type": "CNmSoundEvent", "name": "Draw.Gear", "time_seconds": 0.0}]}}}}
            """;
    private static final String SOUNDS = """
            {"events": {
              "c4.draw": {"action": "draw", "files": ["draw.ogg"]},
              "c4.plant": {"action": "plant", "files": ["plant_plant.ogg"]},
              "c4.keypressquiet": {"action": "plant", "files": ["plant_keypressquiet.ogg", "../escape.ogg"]},
              "c4.initiate": {"action": "initiate", "files": ["initiate_initiate.ogg"]},
              "C4.PlantSound": {"action": "beep", "files": ["missing.ogg"]}}}
            """;

    private static EquipmentSoundPlan plan() {
        return EquipmentSoundPlan.parse("c4", CACHE, SOUNDS, file -> !file.equals("missing.ogg"));
    }

    @Test
    public void markersMatchManifestEventsInTimeOrder() {
        List<EquipmentSoundPlan.Cue> cues = plan().cues("use", 0);
        assertEquals(2, cues.size());
        assertEquals(0.1f, cues.get(0).seconds(), 1e-6);
        assertEquals(List.of("initiate_initiate.ogg"), cues.get(0).files());
        assertEquals(List.of("plant_keypressquiet.ogg"), cues.get(1).files());
    }

    @Test
    public void markersScaleToServerDuration() {
        List<EquipmentSoundPlan.Cue> cues = plan().cues("use", 3);
        assertEquals(0.075f, cues.get(0).seconds(), 1e-6);
        assertEquals(0.5f, cues.get(1).seconds(), 1e-5);
    }

    @Test
    public void drawWithoutMarkersFallsBackToManifestDraw() {
        List<EquipmentSoundPlan.Cue> cues = plan().cues("draw", 0);
        assertEquals(1, cues.size());
        assertEquals(0f, cues.get(0).seconds(), 0);
        assertEquals(List.of("draw.ogg"), cues.get(0).files());
    }

    @Test
    public void eventActionsUseFirstEventAndSkipMissingFiles() {
        EquipmentSoundPlan plan = plan();
        assertEquals(List.of("plant_plant.ogg"), plan.files("plant"));
        assertTrue(plan.files("beep").isEmpty());
        assertTrue(EquipmentSoundPlan.parse("c4", null, null, file -> true).cues("use", 3).isEmpty());
    }
}
