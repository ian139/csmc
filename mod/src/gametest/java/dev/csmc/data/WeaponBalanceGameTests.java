package dev.csmc.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.csmc.CsmcMod;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** The post-import balance layer: precedence over the CS2 export, validation, and {@code /reload}. */
public final class WeaponBalanceGameTests implements FabricGameTest {
    private static final ResourceLocation RELOAD_TARGET = ResourceLocation.fromNamespaceAndPath("csmc-test", "scope_two");
    private static final String RELOAD_PACK = "csmc_balance_reload_test";

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    /** Balance overrides win over both the authored datapack and the exported vdata; bad files are skipped. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void balanceOverridesWinOverExportedVdata(GameTestHelper helper) {
        Path vdata;
        try {
            vdata = Files.createTempFile("csmc-balance", ".vdata");
            Files.writeString(vdata, "{ weapon_ak47 = { m_iMaxClip1 = 31 m_nDamage = 37 m_flArmorRatio = 1.7 "
                    + "m_flCycleTime = 0.1 m_WeaponType = \"WEAPONTYPE_RIFLE\" } "
                    + "weapon_glock = { m_iMaxClip1 = 21 m_nDamage = 31 m_flCycleTime = 0.15 "
                    + "m_WeaponType = \"WEAPONTYPE_PISTOL\" } }");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Map<ResourceLocation, JsonElement> files = Map.of(CsmcMod.id("ak47"),
                    json("{\"damage\": 40, \"magazine_size\": 25, \"cycle_time_s\": 0.1, \"reload_time_s\": 2.4}"));
            Map<ResourceLocation, String> balance = new LinkedHashMap<>();
            balance.put(CsmcMod.id("ak47"), "{\"_reason\": \"test\", \"damage\": 99, "
                    + "\"inaccuracy\": {\"stand\": 0.25}, \"scoped\": {\"max_speed\": 120}}");
            balance.put(CsmcMod.id("glock"), "{\"damage\": 50, \"ammo_type\": \"rifle\"}");
            balance.put(CsmcMod.id("deagle"), "{\"damage\": -5}");
            balance.put(CsmcMod.id("p250"), "{\"magazine_size\": 7.5}");
            balance.put(CsmcMod.id("no_such_gun"), "{\"damage\": 5}");
            balance.put(CsmcMod.id("mp9"), "{\"damage\": 99} {\"ammo_type\": \"rifle\"}");
            balance.put(CsmcMod.id("mp7"), "{\"damage\": 99,");
            WeaponLayers.Build build = WeaponLayers.build(CsmcMod.FIREARM_IDS, files, balance, vdata);
            WeaponLayers.Build unbalanced = WeaponLayers.build(CsmcMod.FIREARM_IDS, files, Map.of(), vdata);
            helper.assertTrue(build.weapons() != null, "build rejected: " + build.errors());
            WeaponData ak = build.weapons().get(CsmcMod.id("ak47"));
            helper.assertValueEqual(ak.damage(), 99.0, "balance damage beats export 37 and datapack 40");
            helper.assertValueEqual(ak.inaccuracy().stand(), 0.25, "nested balance member");
            helper.assertValueEqual(ak.scoped().maxSpeed(), 120.0, "scoped balance member");
            helper.assertValueEqual(ak.magazineSize(), 31, "unbalanced field keeps the export");
            helper.assertValueEqual(ak.armorRatio(), 1.7, "unbalanced field keeps the export");
            helper.assertValueEqual(ak.reloadTimeS(), 2.4, "unbalanced authored field survives");
            WeaponData glock = build.weapons().get(CsmcMod.id("glock"));
            helper.assertValueEqual(glock.damage(), 31.0, "file with a disallowed field is ignored whole");
            helper.assertValueEqual(build.weapons().get(CsmcMod.id("deagle")).damage(),
                    unbalanced.weapons().get(CsmcMod.id("deagle")).damage(), "invalid value is ignored");
            for (String id : List.of("mp9", "mp7")) {
                helper.assertValueEqual(build.weapons().get(CsmcMod.id(id)).damage(),
                        unbalanced.weapons().get(CsmcMod.id(id)).damage(), id + " malformed JSON is ignored whole");
            }
            helper.assertValueEqual(build.balanced(), java.util.Set.of(CsmcMod.id("ak47")), "applied overrides");
            List<String> errors = build.balanceErrors();
            helper.assertValueEqual(errors.size(), 6, "one error per rejected override: " + errors);
            for (String id : List.of("csmc:glock", "csmc:deagle", "csmc:p250", "csmc:no_such_gun", "csmc:mp9",
                    "csmc:mp7")) {
                helper.assertTrue(errors.stream().anyMatch(e -> e.startsWith(id + ": ")), "error for " + id + ": " + errors);
            }
            helper.assertTrue(errors.stream().anyMatch(e -> e.contains("ammo_type")), "names the disallowed field");
        } finally {
            try {
                Files.deleteIfExists(vdata);
            } catch (IOException ignored) {
                // temp file
            }
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void speedOverrideMatchesMovementProtocolDomain(GameTestHelper helper) {
        ResourceLocation id = CsmcMod.id("ak47");
        Path vdata = Path.of("build", "absent-speed-fixture.vdata");
        WeaponLayers.Build baseline = WeaponLayers.build(CsmcMod.FIREARM_IDS, Map.of(), Map.of(), vdata);
        for (double speed : new double[] {1_500, 2_000}) {
            WeaponLayers.Build build = WeaponLayers.build(CsmcMod.FIREARM_IDS, Map.of(),
                    Map.of(id, "{\"max_speed\":" + speed + "}"), vdata);
            helper.assertValueEqual(build.weapons().get(id).maxSpeed(), speed, "valid speed override");
            helper.assertTrue(build.balanceErrors().isEmpty(), "valid boundary has no errors");
        }
        for (String override : List.of("{\"max_speed\":2000.0001}",
                "{\"scoped\":{\"max_speed\":2000.0001}}")) {
            WeaponLayers.Build build = WeaponLayers.build(CsmcMod.FIREARM_IDS, Map.of(), Map.of(id, override), vdata);
            helper.assertTrue(build.balanceErrors().size() == 1, "over-bound override is rejected whole");
            helper.assertValueEqual(build.weapons().get(id), baseline.weapons().get(id),
                    "invalid override keeps baseline weapon");
        }
        helper.succeed();
    }

    /** The bundled overrides are installed by the real reload listener. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void bundledBalanceOverridesAreInstalled(GameTestHelper helper) {
        helper.assertValueEqual(WeaponRegistry.lastBalanceErrors(), List.of(), "bundled override errors");
        for (String id : List.of("ssg08", "m14", "taser")) {
            helper.assertTrue(WeaponRegistry.balanced().contains(CsmcMod.id(id)), "override applied for " + id);
        }
        helper.assertValueEqual(WeaponRegistry.get(CsmcMod.id("ssg08")).damage(), 105.0, "SSG 08 damage");
        helper.assertValueEqual(WeaponRegistry.get(CsmcMod.id("taser")).reloadTimeS(), 30.0, "Zeus recharge");
        helper.succeed();
    }

    /**
     * A world datapack adding an override takes effect on {@code /reload}, a malformed one is reported and
     * ignored, and removing the pack reverts on the next {@code /reload}. Own batch: reloading is global.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_weapon_balance_reload", timeoutTicks = 600)
    public void reloadAppliesAndRevertsDatapackOverrides(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(RELOAD_PACK);
        int before = WeaponRegistry.get(RELOAD_TARGET).penetrationCount();
        ResourceLocation malformed = ResourceLocation.fromNamespaceAndPath("csmc-test", "scope_one");
        int malformedBefore = WeaponRegistry.get(malformed).penetrationCount();
        helper.assertTrue(before != 2 && malformedBefore != 3, "fixture already uses the test values");
        writePack(pack);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        WeaponRegistry.get(RELOAD_TARGET).penetrationCount(), 2, "override after /reload"))
                .thenExecute(() -> {
                    helper.assertTrue(WeaponRegistry.balanced().contains(RELOAD_TARGET), "reported as applied");
                    helper.assertValueEqual(WeaponRegistry.get(malformed).penetrationCount(), malformedBefore,
                            "a file with trailing content is not partially applied");
                    helper.assertTrue(WeaponRegistry.lastBalanceErrors().stream()
                                    .anyMatch(e -> e.startsWith("csmc-test:scope_one: ")),
                            "malformed override reported: " + WeaponRegistry.lastBalanceErrors());
                    helper.assertValueEqual(WeaponRegistry.get(CsmcMod.id("ssg08")).damage(), 105.0,
                            "bundled overrides survive /reload");
                    deletePack(pack);
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        WeaponRegistry.get(RELOAD_TARGET).penetrationCount(), before, "reverted after /reload"))
                .thenExecute(() -> helper.assertValueEqual(WeaponRegistry.lastBalanceErrors(), List.of(),
                        "errors cleared once the malformed file is gone"))
                .thenSucceed();
    }

    private static void writePack(Path pack) {
        try {
            Path dir = pack.resolve("data/csmc-test/weapon_balance");
            Files.createDirectories(dir);
            Files.writeString(pack.resolve("pack.mcmeta"),
                    "{\"pack\": {\"pack_format\": 48, \"description\": \"csmc balance reload test\"}}");
            Files.writeString(dir.resolve("scope_two.json"), "{\"penetration_count\": 2}");
            // Trailing content: vanilla's JSON scanner would apply the first object; the whole file must be rejected.
            Files.writeString(dir.resolve("scope_one.json"), "{\"penetration_count\": 3} {\"damage\": 1}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deletePack(Path pack) {
        try (Stream<Path> paths = Files.walk(pack)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
