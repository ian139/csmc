package dev.csmc.skin.gameplay;

import dev.csmc.CsmcMod;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.item.GloveItem;
import dev.csmc.item.SkinnableWeapon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class WeaponCaseItem extends Item {
    private static final String[] RARITIES = {"common", "uncommon", "rare", "mythical", "legendary", "ancient", "immortal"};

    /** Which guns a case may roll skins for. */
    public enum Pool {
        /** The Weapon Case: every registered gun. */
        ALL("screen.csmc.case_opening", "message.csmc.weapon_case.empty", "message.csmc.weapon_case.empty"),
        /**
         * The Loadout Case: only guns and knives anywhere in the opener's inventory (main, hotbar, armor, offhand).
         * Weapon-only: gloves never roll here.
         */
        LOADOUT("screen.csmc.case_opening.inventory_weapon_case", "message.csmc.inventory_weapon_case.no_guns",
                "message.csmc.inventory_weapon_case.empty");

        private final String openingTitle;
        private final String noWeaponsMessage;
        private final String noSkinsMessage;

        Pool(String openingTitle, String noWeaponsMessage, String noSkinsMessage) {
            this.openingTitle = openingTitle;
            this.noWeaponsMessage = noWeaponsMessage;
            this.noSkinsMessage = noSkinsMessage;
        }

        /** Translation key of the case-opening screen title, naming this kind of case. */
        public String openingTitle() {
            return openingTitle;
        }

        /**
         * Distinct canonical weapon ids this pool allows for {@code player}, in {@link CsmcMod#SKINNABLE} order:
         * every gun, knife and glove for {@link #ALL}; the carried guns and knives for {@link #LOADOUT}.
         */
        public List<String> weapons(Player player) {
            if (this == ALL) return List.copyOf(CsmcMod.SKINNABLE.keySet());
            Set<String> carried = new HashSet<>();
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).getItem() instanceof SkinnableWeapon gun && !(gun instanceof GloveItem)) {
                    carried.add(gun.weaponId().getPath());
                }
            }
            List<String> weapons = new ArrayList<>(carried.size());
            for (String weapon : CsmcMod.SKINNABLE.keySet()) {
                if (carried.contains(weapon)) weapons.add(weapon);
            }
            return weapons;
        }
    }

    private final Pool pool;

    public WeaponCaseItem(Properties properties, Pool pool) {
        super(properties);
        this.pool = pool;
    }

    public Pool pool() {
        return pool;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResultHolder.success(player.getItemInHand(hand));
        return open(player, hand, pool, SkinCatalog.kits(), level.getRandom(), SkinCatalog::rarityWeight);
    }

    /**
     * Server-side case opening over explicit kits, restricted to {@code pool}'s weapons for this player: consumes one
     * case only when an attachment is granted. The item is granted here, at roll time; the player then receives a
     * purely cosmetic {@link CaseOpeningPayload} whose filler is drawn from the same restricted pool.
     */
    public static InteractionResultHolder<ItemStack> open(Player player, InteractionHand hand, Pool pool,
                                                          List<SkinCatalog.Kit> kits, RandomSource random,
                                                          ToLongFunction<String> rarityWeight) {
        ItemStack cases = player.getItemInHand(hand);
        List<String> weapons = pool.weapons(player);
        Optional<Rolled> rolled = rollResult(kits, weapons, random, rarityWeight);
        if (rolled.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    weapons.isEmpty() ? pool.noWeaponsMessage : pool.noSkinsMessage), false);
            return InteractionResultHolder.fail(cases);
        }

        // Grant first, then consume, then the cosmetic reel. A last case (not in creative) becomes the attachment in
        // the same hand via the returned replacement rather than being inserted into the just-vacated slot.
        ItemStack skin = rolled.get().stack();
        boolean replacesHand = cases.getCount() == 1 && !player.hasInfiniteMaterials();
        if (!replacesHand) player.getInventory().placeItemBackInInventory(skin);
        cases.consume(1, player);
        if (player instanceof ServerPlayer serverPlayer && ServerPlayNetworking.canSend(serverPlayer, CaseOpeningPayload.TYPE)) {
            ServerPlayNetworking.send(serverPlayer,
                    CaseOpeningPayload.create(pool, rolled.get(), kits, weapons, random.nextLong(), rarityWeight));
        }
        return InteractionResultHolder.consume(replacesHand ? skin : cases);
    }

    /** One weighted weapon/paint-kit pair; {@code tier} indexes {@link SkinCatalog#RARITY_TIERS}. */
    public record Pick(SkinCatalog.Kit kit, String weapon, int tier) { }

    /** A finished roll: the pair plus its rolled wear and pattern seed. */
    public record Rolled(Pick pick, SkinData data) {
        public ItemStack stack() { return SkinGameplay.skin(pick.weapon(), data); }
    }

    /** Unrestricted roll over every registered gun and knife, as the Weapon Case draws. */
    public static Optional<ItemStack> roll(List<SkinCatalog.Kit> kits, RandomSource random,
                                           ToLongFunction<String> rarityWeight) {
        return rollResult(kits, CsmcMod.SKINNABLE.keySet(), random, rarityWeight).map(Rolled::stack);
    }

    /** Unrestricted roll over every registered gun and knife, as the Weapon Case draws. */
    public static Optional<Rolled> rollResult(List<SkinCatalog.Kit> kits, RandomSource random,
                                              ToLongFunction<String> rarityWeight) {
        return rollResult(kits, CsmcMod.SKINNABLE.keySet(), random, rarityWeight);
    }

    public static Optional<Rolled> rollResult(List<SkinCatalog.Kit> kits, Collection<String> weapons, RandomSource random,
                                              ToLongFunction<String> rarityWeight) {
        return pick(kits, weapons, random, rarityWeight).map(pick -> {
            SkinCatalog.Kit kit = pick.kit();
            // Double intermediates keep even narrow or equal bounds within the kit's actual range.
            float wear = (float) ((double) kit.wearMin()
                    + ((double) kit.wearMax() - kit.wearMin()) * random.nextFloat());
            return new Rolled(pick, new SkinData(kit.paintKit(), wear, random.nextInt(1001)));
        });
    }

    /**
     * Draws one weapon/paint-kit pair among {@code weapons} by rarity weight, then uniformly within the tier.
     * {@code weapons} must be registered gun or knife ids without duplicates; their order fixes the draw for a seed.
     * Knife and glove pairs always fall in the rarest (gold ★, {@code immortal}) tier, whatever the kit's own rarity.
     */
    public static Optional<Pick> pick(List<SkinCatalog.Kit> kits, Collection<String> weapons, RandomSource random,
                                      ToLongFunction<String> rarityWeight) {
        int[] pairCounts = new int[RARITIES.length];
        for (SkinCatalog.Kit kit : kits) {
            for (String weapon : weapons) {
                if (kit.supports(weapon) && kit.availableFor(weapon)) pairCounts[pairTier(kit, weapon)]++;
            }
        }

        // Weights are positive and at most 1e9, so seven of them sum well within both long
        // and the 2^53 range where doubles represent every whole ticket exactly.
        long[] weights = new long[RARITIES.length];
        long totalWeight = 0;
        for (int tier = 0; tier < pairCounts.length; tier++) {
            if (pairCounts[tier] == 0) continue;
            weights[tier] = rarityWeight.applyAsLong(RARITIES[tier]);
            totalWeight += weights[tier];
        }
        if (totalWeight == 0) return Optional.empty();

        // Rounding can lift the scaled ticket to the total itself; it belongs to the last whole ticket.
        long ticket = Math.min((long) (random.nextDouble() * totalWeight), totalWeight - 1);
        int selectedTier = 0;
        for (; selectedTier < pairCounts.length; selectedTier++) {
            ticket -= weights[selectedTier];
            if (ticket < 0) break;
        }

        int pair = random.nextInt(pairCounts[selectedTier]);
        for (SkinCatalog.Kit kit : kits) {
            for (String weapon : weapons) {
                if (pairTier(kit, weapon) != selectedTier) continue;
                if (!kit.supports(weapon) || !kit.availableFor(weapon)) continue;
                if (pair-- != 0) continue;
                return Optional.of(new Pick(kit, weapon, selectedTier));
            }
        }
        throw new IllegalStateException("Selected weapon skin pair was not found");
    }

    /** Tier of one weapon/kit pair: knives and gloves are always gold ★ ({@code immortal}); guns use the kit's rarity. */
    public static int pairTier(SkinCatalog.Kit kit, String weapon) {
        return SkinPickerFilter.pairTier(kit, CsmcMod.KNIVES.containsKey(weapon) || CsmcMod.GLOVES.containsKey(weapon));
    }

    static int rarityTier(String rarity) {
        return switch (rarity) {
            case "common" -> 0;
            case "uncommon" -> 1;
            case "mythical" -> 3;
            case "legendary" -> 4;
            case "ancient" -> 5;
            case "immortal" -> 6;
            default -> 2;
        };
    }
}

