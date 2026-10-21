package dev.csmc.skin.gameplay;

import dev.csmc.CsmcMod;
import dev.csmc.skin.SkinData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.item.ItemStack;

/**
 * Dev-only smoke hook, inert unless {@code -Dcsmc.devSkin=<weapon>:<paint_kit>[:<wear>[:<seed>]]} is set: every
 * joining player's selected hotbar slot receives that skinned gun, so {@code -Dcsmc.devScreenshot} can capture it
 * without input. The gun takes the same path as a crafted one: a real gun item carrying the skin component.
 */
final class SkinDevHook {
    private SkinDevHook() {}

    static void register() {
        String property = System.getProperty("csmc.devSkin");
        if (property == null || property.isBlank()) return;
        ItemStack template = parse(property.trim());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.getPlayer();
            player.getInventory().setItem(player.getInventory().selected, template.copy());
            CsmcMod.LOGGER.info("[csmc dev] Gave {} {} with {}", player.getScoreboardName(),
                    template.getItem(), template.get(SkinGameplay.SKIN));
        });
    }

    static ItemStack parse(String property) {
        String[] parts = property.split(":");
        if (parts.length < 2 || parts.length > 4) {
            throw new IllegalArgumentException("csmc.devSkin must be <weapon>:<paint_kit>[:<wear>[:<seed>]]: " + property);
        }
        var gun = CsmcMod.SKINNABLE.get(parts[0]);
        if (gun == null) throw new IllegalArgumentException("csmc.devSkin names an unknown weapon: " + parts[0]);
        SkinData skin = new SkinData(Integer.parseInt(parts[1]),
                parts.length > 2 ? Float.parseFloat(parts[2]) : 0, parts.length > 3 ? Integer.parseInt(parts[3]) : 0);
        ItemStack stack = new ItemStack(gun);
        stack.set(SkinGameplay.SKIN, skin);
        return stack;
    }
}
