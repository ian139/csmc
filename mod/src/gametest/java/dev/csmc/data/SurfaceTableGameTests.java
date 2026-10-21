package dev.csmc.data;

import java.util.OptionalDouble;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Surface import (synthetic, self-authored KV text) and the shipped block → surface table. */
public final class SurfaceTableGameTests implements FabricGameTest {

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void importsKv1WithBaseInheritance(GameTestHelper helper) {
        String kv1 = """
                // synthetic test data
                "Wrapper"
                {
                    "Parent"
                    {
                        "bulletPenetrationDistanceModifier" "0.6"
                        "bulletPenetrationDamageModifier"   "0.7"
                    }
                    "child" [$WIN32]
                    {
                        "base" "parent"
                        "BulletPenetrationDamageModifier" "0.3"
                    }
                    "loop_a" { "base" "loop_b" }
                    "loop_b" { "base" "loop_a" }
                    "parent" { "bulletPenetrationDamageModifier" "0.75" }
                }
                """;
        SurfacePropertiesImporter.Overrides overrides = SurfacePropertiesImporter.parse(kv1);
        helper.assertTrue(overrides.contains("parent") && overrides.contains("child"), "surfaces " + overrides.surfaces().keySet());
        helper.assertValueEqual(overrides.distanceModifier("Child"), OptionalDouble.of(0.6), "child inherits distance");
        helper.assertValueEqual(overrides.damageModifier("Child"), OptionalDouble.of(0.3), "child overrides damage");
        helper.assertValueEqual(overrides.distanceModifier("parent"), OptionalDouble.of(0.6), "redefinition merges distance");
        helper.assertValueEqual(overrides.damageModifier("parent"), OptionalDouble.of(0.75), "redefinition merges damage, later wins");
        helper.assertValueEqual(overrides.distanceModifier("loop_a"), OptionalDouble.empty(), "base cycle yields no distance");
        helper.assertValueEqual(overrides.damageModifier("loop_a"), OptionalDouble.empty(), "base cycle yields no damage");
        helper.assertValueEqual(overrides.distanceModifier("absent"), OptionalDouble.empty(), "unknown surface has no distance");
        helper.assertValueEqual(overrides.damageModifier("absent"), OptionalDouble.empty(), "unknown surface has no damage");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void importsKv3SurfaceList(GameTestHelper helper) {
        String kv3 = """
                <!-- kv3 encoding:text:version{00000000-0000-0000-0000-000000000000} format:generic:version{00000000-0000-0000-0000-000000000000} -->
                {
                    SurfacePropertiesList =
                    [
                        { surfacePropertyName = "Alpha" bulletPenetrationDistanceModifier = 0.4 },
                        { bulletPenetrationDamageModifier = 0.8, surfacePropertyName = "beta", baseSurfaceProperty = "alpha", climbable = true },
                    ]
                }
                """;
        SurfacePropertiesImporter.Overrides overrides = SurfacePropertiesImporter.parse(kv3);
        helper.assertValueEqual(overrides.distanceModifier("alpha"), OptionalDouble.of(0.4), "alpha distance");
        helper.assertValueEqual(overrides.damageModifier("alpha"), OptionalDouble.empty(), "missing value stays absent");
        helper.assertValueEqual(overrides.distanceModifier("BETA"), OptionalDouble.of(0.4), "kv3 base chain distance");
        helper.assertValueEqual(overrides.damageModifier("BETA"), OptionalDouble.of(0.8), "kv3 beta damage");
        boolean rejected = false;
        try {
            SurfacePropertiesImporter.parse("{ SurfacePropertiesList = [ { surfacePropertyName = \"x\" bulletPenetrationDamageModifier = oops } ] }");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        helper.assertTrue(rejected, "non-numeric modifier must be rejected");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void shippedTableMapsBlocksToSurfaces(GameTestHelper helper) {
        MaterialTable.Material pane = MaterialTable.lookup(Blocks.GLASS_PANE.defaultBlockState());
        helper.assertValueEqual(pane.name(), "glass", "glass pane surface");
        helper.assertValueEqual(pane.breakMode(), MaterialTable.BreakMode.SHATTER, "glass pane break");

        MaterialTable.Material planks = MaterialTable.lookup(Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertValueEqual(planks.name(), "wood", "planks surface");
        helper.assertValueEqual(planks.breakMode(), MaterialTable.BreakMode.CUMULATIVE, "planks break");

        MaterialTable.Material stone = MaterialTable.lookup(Blocks.STONE.defaultBlockState());
        helper.assertValueEqual(stone.name(), "rock", "stone surface");
        helper.assertFalse(stone.stops(), "stone is penetrable (CS2 wallbangs)");
        helper.assertTrue(stone.distanceModifier() > 0.0 && stone.distanceModifier() <= 1.0, "stone distance modifier in (0, 1]");

        helper.assertTrue(MaterialTable.lookup(Blocks.OBSIDIAN.defaultBlockState()).stops(), "obsidian stops");
        helper.assertValueEqual(MaterialTable.lookup(Blocks.BEDROCK.defaultBlockState()), MaterialTable.UNBREAKABLE,
                "bedrock is unbreakable");
        helper.succeed();
    }
}
