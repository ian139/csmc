package dev.csmc.data;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Balance roles tune values only; placeholder mechanics keep their CS weapon-class predicates. */
public class WeaponPlaceholdersTest {
    @Test
    public void onlyBoltActionSnipersAreScopedAndUnzoom() {
        for (String id : WeaponRole.all().keySet()) {
            JsonObject json = WeaponPlaceholders.create(id);
            boolean bolt = id.equals("awp") || id.equals("ssg08");
            assertEquals(id + " scope", bolt, json.has("zoom"));
            assertEquals(id + " scoped mode", bolt, json.has("scoped"));
            if (bolt) {
                assertEquals(true, json.getAsJsonObject("zoom").get("unzoom_after_shot").getAsBoolean());
            }
        }
    }

    @Test
    public void sniperClassMechanicsUnchangedByRoles() {
        Set<String> automatic = Set.of("scar20", "g3sg1");
        for (String id : Set.of("ssg08", "awp", "m14", "scar20", "g3sg1")) {
            JsonObject json = WeaponPlaceholders.create(id);
            assertEquals(id, automatic.contains(id), json.get("automatic").getAsBoolean());
            assertEquals(id, automatic.contains(id) ? 0.25 : 1.2, json.get("cycle_time_s").getAsDouble(), 0);
            assertEquals(id, automatic.contains(id) ? 20 : 10, json.get("magazine_size").getAsInt());
            assertEquals(id, 200.0, json.get("max_speed").getAsDouble(), 0);
        }
        Map<String, Double> stand = Map.of("awp", 0.08, "ssg08", 0.08, "m14", 0.004, "scar20", 0.004);
        stand.forEach((id, value) -> assertEquals(id, value,
                WeaponPlaceholders.create(id).getAsJsonObject("inaccuracy").get("stand").getAsDouble(), 0));
    }
}
