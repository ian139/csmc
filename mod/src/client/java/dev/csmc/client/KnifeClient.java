package dev.csmc.client;

import dev.csmc.CsmcMod;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.client.sound.KnifeSounds;
import dev.csmc.item.KnifeItem;
import dev.csmc.melee.KnifeInput;
import dev.csmc.network.KnifeActionPayload;
import dev.csmc.network.KnifeEventPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * Knife input and feedback. Attack/use press edges arrive from {@code KnifeKeyEdgeMixin} in order and are sent once
 * per client tick as {@link KnifeActionPayload} SLASH/STAB; the server owns cooldown, trace and damage. Vanilla
 * punching, mining and item use are suppressed while a main-hand knife is held, except for spectators.
 * {@link KnifeEventPayload} drives the positional sound and the swing animation for the attacker and onlookers.
 */
public final class KnifeClient {
    private static final KnifeInput INPUT = new KnifeInput();
    private static final byte[] ACTIONS = new byte[KnifeInput.CAPACITY];

    private KnifeClient() {}

    static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(KnifeClient::tick);
        ClientPlayNetworking.registerGlobalReceiver(KnifeEventPayload.TYPE,
                (payload, context) -> context.client().execute(() -> event(context.client(), payload)));
    }

    /** True when a main-hand knife owns attack/use: vanilla mining, punching and item use are suppressed. */
    public static boolean ownsInput(Minecraft client) {
        LocalPlayer player = client.player;
        return player != null && KnifeInput.ownsInput(player.getMainHandItem().getItem() instanceof KnifeItem,
                player.isSpectator());
    }

    /** Attack/use edge from keyboard/mouse dispatch ({@code KeyMapping.set}), before vanilla applies it. */
    public static void keyEdge(KeyMapping mapping, boolean down) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) return;
        if (mapping == client.options.keyAttack) INPUT.edge(KnifeInput.ATTACK, down);
        else if (mapping == client.options.keyUse) INPUT.edge(KnifeInput.USE, down);
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.getConnection() == null) {
            INPUT.reset();
            return;
        }
        boolean holding = player.getMainHandItem().getItem() instanceof KnifeItem && client.screen == null
                && player.isAlive() && !player.isSpectator();
        int count = INPUT.tick(holding, client.isWindowActive(), client.options.keyAttack.isDown(),
                client.options.keyUse.isDown(), ACTIONS);
        if (count == 0) return;
        CachedGunRendering.cancelInspect();
        // Number-key selection is only synced by vanilla next tick; the server must judge this slot.
        client.getConnection().send(new ServerboundSetCarriedItemPacket(player.getInventory().selected));
        for (int i = 0; i < count; i++) ClientPlayNetworking.send(new KnifeActionPayload(ACTIONS[i]));
    }

    private static void event(Minecraft client, KnifeEventPayload payload) {
        if (client.level == null) return;
        KnifeSounds.play(client.level, payload.knifeId(), payload.event(), payload.x(), payload.y(), payload.z());
        boolean self = client.player != null && payload.entityId() == client.player.getId();
        String action = switch (payload.event()) {
            // The local draw already started on the slot change; a late server deploy would restart it.
            case "deploy" -> self ? null : "draw";
            // The server names the accepted swing: slash_backstab (light clip) or backstab (heavy clip).
            case "slash", "stab", "slash_backstab", "backstab" -> payload.event();
            default -> null;
        };
        if (action != null) {
            CachedGunRendering.serverAnimation(payload.entityId(), CsmcMod.id(payload.knifeId()), action, 0);
        } else if (KnifeEventPayload.HIT.equals(payload.event())) {
            // Follows its swing event in the same tick: same timing, hit clip variant.
            CachedGunRendering.knifeHit(payload.entityId());
        }
        dev.csmc.client.thirdperson.melee.MeleeAnimations.receive(payload);
    }
}
