package dev.csmc.data;

import dev.csmc.CsmcMod;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.item.GloveItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

public final class RecipeGameTests implements FabricGameTest {
    // A skin needs a real paint kit: survival obtains it by opening a crafted weapon case.
    // See "Crafting recipes" in docs/gameplay.md. Never fabricate an unbound skin recipe.
    private static final Set<ResourceLocation> EXEMPTIONS = Set.of(CsmcMod.id("weapon_skin"));

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void everyRegisteredItemHasSurvivalRecipe(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = level.getServer().getRecipeManager();
        helper.assertTrue(!manager.hadErrorsLoading(), "all recipe JSON must load successfully");
        Set<ResourceLocation> results = new HashSet<>();
        for (var holder : manager.getRecipes()) {
            var result = holder.value().getResultItem(level.registryAccess());
            if (!result.isEmpty()) {
                results.add(BuiltInRegistries.ITEM.getKey(result.getItem()));
            }
        }
        List<ResourceLocation> missing = new ArrayList<>();
        for (var id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals("csmc") && !EXEMPTIONS.contains(id) && !results.contains(id)) {
                missing.add(id);
            }
        }
        missing.sort(ResourceLocation::compareTo);
        helper.assertTrue(missing.isEmpty(), "registered csmc items missing survival recipes: " + missing);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void shapedRecipesHaveUniqueIngredientGrids(GameTestHelper helper) {
        Map<String, ResourceLocation> grids = new HashMap<>();
        for (var holder : helper.getLevel().getServer().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof ShapedRecipe shaped)) {
                continue;
            }
            // Resolved ingredient sets, not JSON key letters; Minecraft also accepts horizontal mirrors.
            String normal = grid(shaped, false);
            String mirrored = grid(shaped, true);
            String canonical = normal.compareTo(mirrored) <= 0 ? normal : mirrored;
            ResourceLocation previous = grids.putIfAbsent(canonical, holder.id());
            helper.assertTrue(previous == null,
                    "identical shaped ingredient grids: " + previous + " and " + holder.id());
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void everyGloveHasExactlyOneShapedRecipe(GameTestHelper helper) {
        var level = helper.getLevel();
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (var holder : level.getServer().getRecipeManager().getRecipes()) {
            var result = holder.value().getResultItem(level.registryAccess());
            if (result.getItem() instanceof GloveItem glove) {
                helper.assertTrue(holder.value() instanceof ShapedRecipe, "shaped glove recipe " + holder.id());
                helper.assertValueEqual(result.getCount(), 1, "one glove per craft " + holder.id());
                counts.merge(glove.weaponId(), 1, Integer::sum);
            }
        }
        for (String id : CsmcMod.GLOVE_IDS) {
            helper.assertValueEqual(counts.get(CsmcMod.id(id)), 1, "recipes for " + id);
        }
        helper.assertValueEqual(counts.size(), CsmcMod.GLOVE_IDS.size(), "8 glove recipes");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void ammunitionBatchesMatchTheirDocumentedRecipes(GameTestHelper helper) {
        record Batch(String id, int width, int height, int yield, List<Item> grid) {}
        List<Batch> batches = List.of(
                new Batch("pistol_ammo", 3, 3, 16, List.of(
                        Items.AIR, Items.IRON_NUGGET, Items.AIR,
                        Items.IRON_NUGGET, Items.GUNPOWDER, Items.IRON_NUGGET,
                        Items.AIR, Items.IRON_NUGGET, Items.AIR)),
                new Batch("rifle_ammo", 3, 2, 16, List.of(
                        Items.IRON_NUGGET, Items.GUNPOWDER, Items.IRON_NUGGET,
                        Items.IRON_NUGGET, Items.GUNPOWDER, Items.IRON_NUGGET)),
                new Batch("sniper_ammo", 3, 2, 8, List.of(
                        Items.IRON_INGOT, Items.GUNPOWDER, Items.IRON_INGOT,
                        Items.IRON_INGOT, Items.IRON_NUGGET, Items.IRON_INGOT)),
                new Batch("shotgun_shells", 3, 2, 8, List.of(
                        Items.IRON_NUGGET, Items.PAPER, Items.IRON_NUGGET,
                        Items.IRON_NUGGET, Items.GUNPOWDER, Items.IRON_NUGGET)));
        var level = helper.getLevel();
        var manager = level.getServer().getRecipeManager();
        for (Batch batch : batches) {
            var holder = manager.byKey(CsmcMod.id(batch.id())).orElseThrow();
            helper.assertTrue(holder.value() instanceof ShapedRecipe, batch.id() + " shaped batch");
            ShapedRecipe recipe = (ShapedRecipe) holder.value();
            CraftingInput input = CraftingInput.of(batch.width(), batch.height(),
                    batch.grid().stream().map(ItemStack::new).toList());
            helper.assertTrue(recipe.matches(input, level), batch.id() + " documented ingredients");
            ItemStack output = recipe.assemble(input, level.registryAccess());
            helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(output.getItem()), CsmcMod.id(batch.id()),
                    batch.id() + " result");
            helper.assertValueEqual(output.getCount(), batch.yield(), batch.id() + " batch yield");
            long recipes = manager.getRecipes().stream().filter(candidate ->
                    candidate.value().getResultItem(level.registryAccess()).is(output.getItem())).count();
            helper.assertValueEqual(recipes, 1L, batch.id() + " has exactly one recipe");
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void everyEquipmentItemHasExactlyOneRecipe(GameTestHelper helper) {
        var level = helper.getLevel();
        List<Item> equipment = List.of(EquipmentRegistry.HEALTHSHOT, EquipmentRegistry.C4, EquipmentRegistry.DEFUSE_KIT);
        Map<Item, Integer> counts = new HashMap<>();
        for (var holder : level.getServer().getRecipeManager().getRecipes()) {
            var result = holder.value().getResultItem(level.registryAccess());
            if (equipment.contains(result.getItem())) {
                helper.assertValueEqual(result.getCount(), 1, "one item per craft " + holder.id());
                counts.merge(result.getItem(), 1, Integer::sum);
            }
        }
        for (Item item : equipment) {
            helper.assertValueEqual(counts.get(item), 1, "recipes for " + BuiltInRegistries.ITEM.getKey(item));
        }
        helper.succeed();
    }

    /** Shaped grids are covered above; an equipment shapeless recipe must not share its ingredient multiset. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void equipmentShapelessRecipesHaveUniqueIngredientSets(GameTestHelper helper) {
        var level = helper.getLevel();
        Set<Item> equipment = Set.of(EquipmentRegistry.HEALTHSHOT, EquipmentRegistry.C4, EquipmentRegistry.DEFUSE_KIT);
        Map<String, List<ResourceLocation>> byIngredients = new HashMap<>();
        List<String> equipmentKeys = new ArrayList<>();
        for (var holder : level.getServer().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof ShapelessRecipe shapeless)) {
                continue;
            }
            String key = shapeless.getIngredients().stream().map(RecipeGameTests::ingredient).sorted().toList().toString();
            byIngredients.computeIfAbsent(key, k -> new ArrayList<>()).add(holder.id());
            if (equipment.contains(shapeless.getResultItem(level.registryAccess()).getItem())) {
                equipmentKeys.add(key);
            }
        }
        for (String key : equipmentKeys) {
            helper.assertValueEqual(byIngredients.get(key).size(), 1,
                    "identical shapeless ingredients: " + byIngredients.get(key));
        }
        helper.succeed();
    }

    private static String ingredient(Ingredient ingredient) {
        return Arrays.stream(ingredient.getItems())
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .sorted().distinct().toList().toString();
    }
    private static String grid(ShapedRecipe recipe, boolean mirrored) {
        StringBuilder result = new StringBuilder().append(recipe.getWidth()).append('x')
                .append(recipe.getHeight()).append(':');
        for (int y = 0; y < recipe.getHeight(); y++) {
            for (int x = 0; x < recipe.getWidth(); x++) {
                int column = mirrored ? recipe.getWidth() - x - 1 : x;
                Ingredient ingredient = recipe.getIngredients().get(y * recipe.getWidth() + column);
                result.append(Arrays.stream(ingredient.getItems())
                        .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        .sorted().distinct().toList()).append(';');
            }
        }
        return result.toString();
    }
}
