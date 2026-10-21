package dev.csmc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

/** Each balance file is accepted or rejected as a whole; syntax is strict JSON with nothing after the object. */
public class WeaponBalanceTest {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("csmc", path);
    }

    private static Map<ResourceLocation, JsonObject> parse(String text, List<String> errors) {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        files.put(id("ak47"), text);
        return WeaponBalance.parse(files, errors);
    }

    private static void assertRejected(String text, String reason) {
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, JsonObject> valid = parse(text, errors);
        assertTrue("rejected whole: " + text, valid.isEmpty());
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith("csmc:ak47: ") && errors.get(0).contains(reason));
    }

    @Test
    public void acceptsAnAllowedObjectAndDropsComments() {
        List<String> errors = new ArrayList<>();
        JsonObject override = parse("{\"_reason\": \"x\", \"damage\": 40, \"scoped\": {\"inaccuracy\": {\"stand\": 0.1}}}\n",
                errors).get(id("ak47"));
        assertEquals(List.of(), errors);
        assertEquals(40, override.get("damage").getAsInt());
        assertTrue(!override.has("_reason"));
        assertEquals(0.1, override.getAsJsonObject("scoped").getAsJsonObject("inaccuracy").get("stand").getAsDouble(), 0);
    }

    @Test
    public void trailingContentRejectsTheWholeFile() {
        assertRejected("{\"damage\": 99} {\"ammo_type\": \"rifle\"}", "malformed JSON");
        assertRejected("{\"damage\": 99} garbage", "malformed JSON");
        assertRejected("{\"damage\": 99}}", "malformed JSON");
    }

    @Test
    public void brokenSyntaxRejectsTheWholeFile() {
        assertRejected("{\"damage\": 99", "malformed JSON");
        assertRejected("{\"damage\": 99,}", "malformed JSON");
        assertRejected("{damage: 99}", "malformed JSON");
        assertRejected("// note\n{\"damage\": 99}", "malformed JSON");
        assertRejected("", "malformed JSON");
    }

    /** Gson 2.10.1 non-lenient mode accepts these; strict JSON does not. */
    @Test
    public void invalidStringSyntaxRejectsTheWholeFile() {
        assertRejected("{\"damage\": 99, \"_reason\": \"author\\'s note\"}", "invalid escape");
        assertRejected("{\"damage\": 99, \"_reason\": \"\\x41\"}", "invalid escape");
        assertRejected("{\"damage\": 99, \"_reason\": \"\\u12G4\"}", "invalid escape");
        assertRejected("{\"damage\": 99, \"_reason\": \"\\u12\"}", "invalid escape");
        assertRejected("{\"damage\": 99, \"_reason\": \"tab\there\"}", "control character");
        assertRejected("{\"damage\": 99, \"_reason\": \"line\nbreak\"}", "control character");
    }

    @Test
    public void validEscapesAreAccepted() {
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, JsonObject> valid = parse(
                "{\"_reason\": \"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9 \\uD83D\\uDE00\", \"damage\": 41}", errors);
        assertEquals(List.of(), errors);
        assertEquals(41, valid.get(id("ak47")).get("damage").getAsInt());
    }

    @Test
    public void anInvalidEscapeLeavesOtherFilesApplied() {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        files.put(id("ak47"), "{\"damage\": 99, \"_reason\": \"author\\'s note\"}");
        files.put(id("glock"), "{\"damage\": 2}");
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, JsonObject> valid = WeaponBalance.parse(files, errors);
        assertEquals(List.of(id("glock")), List.copyOf(valid.keySet()));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith("csmc:ak47: "));
    }

    @Test
    public void disallowedFieldsAndValuesRejectTheWholeFile() {
        assertRejected("{\"damage\": 50, \"ammo_type\": \"rifle\"}", "ammo_type");
        assertRejected("{\"damage\": \"lots\"}", "finite number");
        assertRejected("{\"magazine_size\": 7.5}", "integer");
        assertRejected("[1]", "JSON object");
        assertRejected("{\"_only\": \"comment\"}", "no balance fields");
    }

    @Test
    public void oneBadFileDoesNotAffectOthers() {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        files.put(id("ak47"), "{\"damage\": 1} {}");
        files.put(id("glock"), "{\"damage\": 2}");
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, JsonObject> valid = WeaponBalance.parse(files, errors);
        assertEquals(List.of(id("glock")), List.copyOf(valid.keySet()));
        assertEquals(1, errors.size());
    }
}
