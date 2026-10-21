package dev.csmc.client.mixin;

import dev.csmc.client.CsmcClient;
import dev.csmc.item.SkinnableWeapon;
import dev.csmc.equipment.EquipmentRegistry;
import java.util.Map;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** F inspects guns and knives without swapping them offhand, while retaining vanilla F for other items. */
@Mixin(KeyMapping.class)
abstract class InspectKeyMappingMixin {
    @Redirect(method = "click", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object csmc$routeInspectClick(Map<?, ?> bindings, Object key) {
        if (key.equals(CsmcClient.inspectKey())) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.screen == null
                    && (client.player.getMainHandItem().getItem() instanceof SkinnableWeapon
                    || client.player.getMainHandItem().is(EquipmentRegistry.C4))) {
                CsmcClient.queueInspect();
                return null;
            }
            // Vanilla stores only one binding per key. Preserve Swap Offhand outside gun use even if
            // the inspect mapping was the last binding registered/reset for the shared default key.
            if (key.equals(KeyBindingHelper.getBoundKeyOf(client.options.keySwapOffhand))) {
                return client.options.keySwapOffhand;
            }
        }
        return bindings.get(key);
    }
}
