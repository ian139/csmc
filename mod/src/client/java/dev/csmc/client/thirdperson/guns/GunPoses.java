package dev.csmc.client.thirdperson.guns;

import dev.csmc.CsmcMod;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.client.thirdperson.ActionTimeline;
import dev.csmc.client.thirdperson.ThirdPersonPoses;
import dev.csmc.data.CachePaths;
import dev.csmc.data.Cs2WeaponImporter;
import dev.csmc.data.WeaponData;
import dev.csmc.item.GunItem;
import dev.csmc.thirdperson.guns.GunPoseMath;
import dev.csmc.viewmodel.ViewmodelCue;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.Item;

/** Observes the existing event-driven renderer, without replacing receivers or touching first-person state. */
public final class GunPoses {
    private static final Int2ObjectOpenHashMap<Observed> OBSERVED = new Int2ObjectOpenHashMap<>();
    private static final Map<String, WeaponData> TIMINGS = new HashMap<>();

    private GunPoses() {}

    public static void initialize() {
        ThirdPersonPoses.register(new GunPoseProvider());
        var file = CachePaths.root().resolve("data/weapons.vdata");
        if (Files.isRegularFile(file)) {
            try {
                Cs2WeaponImporter.load(file, Map.of()).forEach((id, json) ->
                        TIMINGS.put(id, WeaponData.fromJson(CsmcMod.id(id), json)));
            } catch (IOException | RuntimeException error) {
                CsmcMod.LOGGER.warn("[csmc third person] Cannot read local weapon timings; using class timings", error);
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            for (AbstractClientPlayer player : client.level.players()) synchronize(player);
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> OBSERVED.remove(entity.getId()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> OBSERVED.clear());
    }

    /** Also called before posing, so cancel/replacement is reflected on the very next rendered frame. */
    public static void synchronize(AbstractClientPlayer player) {
        int entity = player.getId();
        if (!(player.getMainHandItem().getItem() instanceof GunItem gun)) {
            if (OBSERVED.remove(entity) != null) {
                ActionTimeline.Entry current = ActionTimeline.current(entity);
                if (current != null && (current.action() == ActionTimeline.Action.FIRE
                        || current.action() == ActionTimeline.Action.RELOAD
                        || current.action() == ActionTimeline.Action.DRAW
                        || current.action() == ActionTimeline.Action.INSPECT)) {
                    ActionTimeline.start(entity, ActionTimeline.Action.NONE, 0, 0, 0);
                }
            }
            return;
        }
        TimedGunCue cue = CachedGunRendering.thirdPersonCue(entity, gun.weaponId());
        Observed old = OBSERVED.get(entity);
        if (old == null) {
            old = new Observed();
            OBSERVED.put(entity, old);
        }
        int slot = player.isLocalPlayer() ? player.getInventory().selected : -1;
        if (old.item == gun && old.slot == slot && old.cue == cue) return;
        old.item = gun;
        old.slot = slot;
        old.cue = cue;
        String id = gun.weaponId().getPath();
        GunPoseMath.Grip grip = GunPoseMath.grip(id);
        WeaponData timing = TIMINGS.get(id);
        double cycle = timing == null ? grip.cycle : timing.activeCycleTimeS(ActionTimeline.scopeLevel(entity) > 0);
        double deploy = timing == null ? 1 : timing.deployTimeS();
        update(entity, cue, cue == null ? ActionTimeline.now() : cue.started() * 1.0E-9, cycle, deploy);
    }

    /** A removed reload/inspect cue means NONE immediately, not a timer running on after cancellation. */
    static void update(int entity, ViewmodelCue cue, double now, double cycle, double deploy) {
        GunPoseMath.Action selected = GunPoseMath.action(cue == null ? null : cue.action());
        ActionTimeline.Action action = switch (selected) {
            case FIRE -> ActionTimeline.Action.FIRE;
            case RELOAD -> ActionTimeline.Action.RELOAD;
            case DRAW -> ActionTimeline.Action.DRAW;
            case INSPECT -> ActionTimeline.Action.INSPECT;
            case NONE -> ActionTimeline.Action.NONE;
        };
        double duration = GunPoseMath.duration(selected, cue == null ? 0 : cue.duration(), cycle, deploy);
        ActionTimeline.start(entity, action, now, duration, cue == null ? 0 : cue.rounds());
    }

    private static final class Observed {
        Item item;
        int slot;
        ViewmodelCue cue;
    }
}
