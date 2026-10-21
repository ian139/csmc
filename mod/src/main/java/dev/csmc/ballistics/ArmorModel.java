package dev.csmc.ballistics;

import dev.csmc.item.KevlarArmor;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;

/** Pure CS-style coverage/absorption. Vanilla enchantments and durability processing happen after this step. */
public final class ArmorModel {
    private ArmorModel() {}
    // Reuse the accumulator on the damage thread rather than allocate a callback/buffer for every pellet.
    private static final ThreadLocal<ArmorContribution> ARMOR_CONTRIBUTION = ThreadLocal.withInitial(ArmorContribution::new);

    /** Damage is in CS units, before conversion to Minecraft health. Wear is unenchanted durability. */
    public record Absorption(double healthDamage, int armorWear, EquipmentSlot slot, boolean protectedHit) {}

    /** Bullet protection per armor tier: the share of CS kevlar's absorption that the tier provides. */
    public static final double KEVLAR_PROTECTION = 1.0;
    public static final double NETHERITE_PROTECTION = 0.8;
    public static final double DIAMOND_PROTECTION = 0.65;
    public static final double IRON_PROTECTION = 0.5;
    /** Chainmail, gold and turtle. */
    public static final double CHAIN_PROTECTION = 0.35;
    /** Leather, unrecognized modded materials and non-ArmorItem pieces with an armor value. */
    public static final double LEATHER_PROTECTION = 0.2;

    /**
     * Pure absorption math for a covered, unbroken piece: CS health damage left after armor of the given
     * {@code protection} tier. Kevlar ({@code protection} 1) is CS's {@code D × clamp(armorRatio × 0.5, 0, 1)}.
     */
    public static double healthDamage(double csDamage, double protection, double weaponArmorRatio) {
        double ratio = Mth.clamp(weaponArmorRatio * 0.5, 0.0, 1.0);
        return csDamage * (1.0 - protection * (1.0 - ratio));
    }

    public static Absorption absorb(LivingEntity target, TraceResult.HitGroup group, double csDamage,
                                     double weaponArmorRatio, double armorBonus) {
        EquipmentSlot slot = switch (group) {
            case HEAD -> EquipmentSlot.HEAD;
            case GENERIC, CHEST, STOMACH -> EquipmentSlot.CHEST;
            case LEGS -> null;
        };
        ItemStack stack = slot == null ? ItemStack.EMPTY : target.getItemBySlot(slot);
        if (stack.isEmpty() || armorContribution(stack, slot) <= 0.0
                || (stack.getMaxDamage() > 0 && stack.getDamageValue() >= stack.getMaxDamage())) {
            return new Absorption(csDamage, 0, slot, false);
        }
        double protection = stack.getItem() instanceof ArmorItem armor ? protection(armor) : LEATHER_PROTECTION;
        double health = healthDamage(csDamage, protection, weaponArmorRatio);
        int wear = (int) Math.max(0.0, (csDamage - health) * armorBonus);
        return new Absorption(health, wear, slot, true);
    }

    private static double armorContribution(ItemStack stack, EquipmentSlot slot) {
        ArmorContribution contribution = ARMOR_CONTRIBUTION.get();
        contribution.add = 0.0;
        contribution.baseMultiplier = 0.0;
        contribution.totalMultiplier = 1.0;
        // Uses the same effective component/default/enchantment modifiers as vanilla equipment attributes.
        stack.forEachModifier(slot, contribution);
        return contribution.add * (1.0 + contribution.baseMultiplier) * contribution.totalMultiplier;
    }

    private static final class ArmorContribution implements BiConsumer<Holder<Attribute>, AttributeModifier> {
        double add;
        double baseMultiplier;
        double totalMultiplier;

        @Override
        public void accept(Holder<Attribute> attribute, AttributeModifier modifier) {
            if (!attribute.equals(Attributes.ARMOR)) return;
            switch (modifier.operation()) {
                case ADD_VALUE -> add += modifier.amount();
                case ADD_MULTIPLIED_BASE -> baseMultiplier += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> totalMultiplier *= 1.0 + modifier.amount();
            }
        }
    }

    private static double protection(ArmorItem armor) {
        var material = armor.getMaterial();
        if (material.equals(KevlarArmor.MATERIAL)) return KEVLAR_PROTECTION;
        if (material.equals(ArmorMaterials.NETHERITE)) return NETHERITE_PROTECTION;
        if (material.equals(ArmorMaterials.DIAMOND)) return DIAMOND_PROTECTION;
        if (material.equals(ArmorMaterials.IRON)) return IRON_PROTECTION;
        if (material.equals(ArmorMaterials.CHAIN) || material.equals(ArmorMaterials.GOLD)
                || material.equals(ArmorMaterials.TURTLE)) return CHAIN_PROTECTION;
        // Leather and unrecognized modded materials get the conservative lowest real-armor tier.
        return LEATHER_PROTECTION;
    }
}
