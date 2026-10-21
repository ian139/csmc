package dev.csmc.client.thirdperson.melee;

import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.viewmodel.ViewmodelCue;
import dev.csmc.client.thirdperson.ActionTimeline;
import dev.csmc.client.thirdperson.ThirdPersonPoses;
import dev.csmc.client.thirdperson.grenades.GrenadePoseProvider;
import dev.csmc.item.KnifeItem;
import dev.csmc.network.KnifeEventPayload;
import dev.csmc.thirdperson.melee.KnifePoseCurves;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

/** Bridges accepted knife events and existing inspect/draw cues without changing weapon playback. */
public final class MeleeAnimations {
    private static final Int2ObjectOpenHashMap<ViewmodelCue> CUES = new Int2ObjectOpenHashMap<>();

    private MeleeAnimations() {}

    public static void initialize() {
        ThirdPersonPoses.register(new KnifePoseProvider());
        GrenadePoseProvider.initialize();
        ButterflyNodeMotion.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            var players = client.level.players();
            for (int i = 0; i < players.size(); i++) sync(players.get(i));
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> CUES.remove(entity.getId()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CUES.clear());
    }

    private static void sync(AbstractClientPlayer player) {
        int id = player.getId();
        if (!player.isAlive() || !(player.getMainHandItem().getItem() instanceof KnifeItem knife)) {
            if (CUES.remove(id) != null) cancelKnife(id);
            return;
        }
        ViewmodelCue cue = CachedGunRendering.thirdPersonCue(id, knife.weaponId());
        ViewmodelCue previous = CUES.get(id);
        if (cue == previous) return;
        if (cue == null) {
            CUES.remove(id);
            cancelKnife(id);
            return;
        }
        CUES.put(id, cue);
        KnifePoseCurves.Action action = KnifePoseCurves.action(cue.action());
        start(id, action, cue.duration(), cue instanceof dev.csmc.client.thirdperson.guns.TimedGunCue timed
                ? timed.started() * 1.0E-9 : ActionTimeline.now());
    }

    /** Called after KnifeClient updates the cached world-model cue; hit feedback never restarts the pose. */
    public static void receive(KnifeEventPayload event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !(client.level.getEntity(event.entityId()) instanceof AbstractClientPlayer player)
                || !(player.getMainHandItem().getItem() instanceof KnifeItem knife)
                || !knife.weaponId().getPath().equals(event.knifeId())) return;
        ViewmodelCue cue = CachedGunRendering.thirdPersonCue(event.entityId(), knife.weaponId());
        if (cue != null) CUES.put(event.entityId(), cue);
        if (KnifeEventPayload.HIT.equals(event.event())) return;
        KnifePoseCurves.Action action = KnifePoseCurves.action(event.event());
        if (action != KnifePoseCurves.Action.IDLE) start(event.entityId(), action, 0, ActionTimeline.now());
    }

    private static void start(int id, KnifePoseCurves.Action action, double duration, double startSeconds) {
        if (action == KnifePoseCurves.Action.IDLE) return;
        ActionTimeline.Action timeline = switch (action) {
            case DRAW -> ActionTimeline.Action.KNIFE_DRAW;
            case INSPECT -> ActionTimeline.Action.KNIFE_INSPECT;
            case SLASH -> ActionTimeline.Action.KNIFE_SLASH;
            case STAB -> ActionTimeline.Action.KNIFE_STAB;
            case BACKSTAB -> ActionTimeline.Action.KNIFE_BACKSTAB;
            default -> ActionTimeline.Action.NONE;
        };
        ActionTimeline.start(id, timeline, startSeconds,
                duration > 0 ? duration : KnifePoseCurves.duration(action), 0);
    }


    private static void cancelKnife(int id) {
        ActionTimeline.Entry entry = ActionTimeline.current(id);
        if (entry != null && switch (entry.action()) {
            case KNIFE_DRAW, KNIFE_INSPECT, KNIFE_SLASH, KNIFE_STAB, KNIFE_BACKSTAB -> true;
            default -> false;
        }) ActionTimeline.start(id, ActionTimeline.Action.NONE, ActionTimeline.now(), 0, 0);
    }
}
