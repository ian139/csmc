package dev.csmc.melee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class KnifeStatsTest {
    private static final String VALID = """
            {"slash_damage": 34, "slash_followup_damage": 22, "stab_damage": 56, "slash_backstab_damage": 80,
             "stab_backstab_damage": 170, "slash_range": 42, "stab_range": 28, "slash_cooldown": 0.38,
             "slash_followup_cooldown": 0.3, "stab_cooldown": 0.8, "armor_ratio": 1.6}""";

    @Test
    public void parsesEveryField() {
        KnifeStats stats = KnifeStats.parse(JsonParser.parseString(VALID).getAsJsonObject());
        assertEquals(new KnifeStats(34, 22, 56, 80, 170, 42, 28, 0.38, 0.3, 0.8, 1.6), stats);
    }

    @Test
    public void malformedOrMissingFallsBackToBaseAndReports() {
        List<String> errors = new ArrayList<>();
        Map<String, KnifeStats> stats = KnifeStats.resolve(List.of("good", "missing_field", "negative", "absent"), id -> {
            JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
            switch (id) {
                case "missing_field" -> json.remove("stab_range");
                case "negative" -> json.addProperty("slash_damage", -1);
                case "absent" -> throw new IllegalArgumentException("missing absent.json");
                default -> { }
            }
            return json;
        }, (id, error) -> errors.add(id));
        assertEquals(List.of("good", "missing_field", "negative", "absent"), List.copyOf(stats.keySet()));
        assertEquals(34.0, stats.get("good").slashDamage(), 0.0);
        assertEquals(KnifeStats.BASE, stats.get("missing_field"));
        assertEquals(KnifeStats.BASE, stats.get("negative"));
        assertEquals(KnifeStats.BASE, stats.get("absent"));
        assertEquals(List.of("missing_field", "negative", "absent"), errors);
    }

    @Test
    public void unknownKnifeUsesBase() {
        assertTrue(KnifeStats.get("no_such_knife") == KnifeStats.BASE);
    }
}
