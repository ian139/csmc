package dev.csmc.client.sound;

import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import dev.csmc.viewmodel.EquipmentSoundPlan;
import dev.csmc.viewmodel.SoundCueTimeline;
import dev.csmc.viewmodel.SoundCueTimeline.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/**
 * Healthshot, C4 and defuse-kit sounds from this client's CS2 export cache ({@link EquipmentSoundPlan}). Every
 * sound has an owner entity id (the actor or the planted bomb) and a scope: one hand's clip-marker timeline or a
 * one-shot event. {@link #stopTimeline(int, InteractionHand)} silences one hand's timeline only (a main-hand switch
 * keeps an off-hand injection audible); {@link #stop} drops everything the owner scheduled or plays. Files play as mono buffers
 * ({@link CachedSoundFiles#monoLocation}) so they are positional. A cue starts only once its buffer has decoded and
 * its owner's generation is still current ({@link SoundCueTimeline}), so vanilla never waits on a decode and a
 * cancelled cue cannot start later. Scheduling stands still while the game is paused. A missing cache file stays
 * silent. Client thread only; everything is cleared on {@link #clear} and resource reload.
 */
public final class CachedEquipmentSounds {
    public static final int HANDLING_RANGE = 16;
    public static final int BEEP_RANGE = 32;
    public static final int EXPLODE_RANGE = 160;
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<String, EquipmentSoundPlan> PLANS = new HashMap<>();
    private static final SoundCueTimeline<Request> CUES = new SoundCueTimeline<>();
    private static final List<OwnedSound> PLAYING = new ArrayList<>();

    /** One chosen file; hand-timeline cues take their owner's eye position when they start instead of x/y/z. */
    private record Request(String id, String file, double x, double y, double z, int range) {}

    private CachedEquipmentSounds() {}

    public static void initialize() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return CsmcMod.id("cached_equipment_sounds"); }
            @Override public void onResourceManagerReload(ResourceManager manager) {
                clear();
                PLANS.clear();
            }
        });
    }

    /** Lazily reads the equipment's manifests and starts decoding its files into vanilla's buffer cache. */
    public static EquipmentSoundPlan prepare(String id) {
        EquipmentSoundPlan cached = PLANS.get(id);
        if (cached != null) return cached;
        EquipmentSoundPlan plan = EquipmentSoundPlan.EMPTY;
        Path root = CachePaths.root();
        Path dir = root.resolve("sounds").resolve(id);
        try {
            Path manifest = root.resolve("manifest.json");
            Path sounds = dir.resolve("manifest.json");
            plan = EquipmentSoundPlan.parse(id,
                    Files.isRegularFile(manifest) ? Files.readString(manifest, StandardCharsets.UTF_8) : null,
                    Files.isRegularFile(sounds) ? Files.readString(sounds, StandardCharsets.UTF_8) : null,
                    file -> Files.isRegularFile(dir.resolve(file)));
        } catch (Exception e) {
            CsmcMod.LOGGER.warn("[csmc sound] Cannot read equipment sounds for {}; staying silent", id, e);
        }
        PLANS.put(id, plan);
        for (String file : plan.allFiles()) CachedGunSounds.buffer(CachedSoundFiles.monoLocation(id, file));
        return plan;
    }

    /**
     * Schedules a viewmodel action's authored sound markers for the owner's {@code hand} over {@code seconds}
     * ({@code <= 0}: clip timing), skipping cues before {@code progress}. Sounds follow the owner entity's eyes.
     */
    public static void timeline(int owner, InteractionHand hand, String id, String action, float seconds, float progress) {
        Scope scope = scope(hand);
        List<EquipmentSoundPlan.Cue> cues = prepare(id).cues(action, seconds);
        float skip = Math.max(0, Math.min(1, progress)) * Math.max(0, seconds);
        for (EquipmentSoundPlan.Cue cue : cues) {
            if (progress > 0 && cue.seconds() < skip) continue;
            long delay = Math.round((cue.seconds() - (progress > 0 ? skip : 0)) * 20);
            CUES.schedule(owner, scope, delay, new Request(id, pick(cue.files()), 0, 0, 0, HANDLING_RANGE));
        }
        dispatch(CUES.drain(paused()));
    }

    /** Plays one file of a server-event sound action at a fixed position. */
    public static void play(int owner, String id, String soundAction, double x, double y, double z, int range) {
        List<String> files = prepare(id).files(soundAction);
        if (files.isEmpty()) return;
        CUES.schedule(owner, Scope.EVENT, 0, new Request(id, pick(files), x, y, z, range));
        dispatch(CUES.drain(paused()));
    }

    /** Drops all the owner's scheduled cues and stops all its playing sounds (cancelled defuse, explosion). */
    public static void stop(int owner) {
        CUES.cancelAll(owner);
        stopPlaying(owner, null);
    }

    /**
     * Drops the owner's scheduled clip-marker cues for {@code hand} and stops that hand's marker sounds; the other
     * hand's timeline and one-shot event sounds (such as the healthshot's success) keep playing.
     */
    public static void stopTimeline(int owner, InteractionHand hand) {
        Scope scope = scope(hand);
        CUES.cancel(owner, scope);
        stopPlaying(owner, scope);
    }

    /** {@link #stopTimeline(int, InteractionHand)} for both hands. */
    public static void stopTimeline(int owner) {
        stopTimeline(owner, InteractionHand.MAIN_HAND);
        stopTimeline(owner, InteractionHand.OFF_HAND);
    }

    private static Scope scope(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? Scope.OFF_HAND : Scope.MAIN_HAND;
    }

    /** Stops the owner's playing sounds of {@code scope}, or all of them for null. */
    private static void stopPlaying(int owner, Scope scope) {
        if (PLAYING.isEmpty()) return;
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        for (int i = PLAYING.size() - 1; i >= 0; i--) {
            OwnedSound sound = PLAYING.get(i);
            if (sound.owner != owner || (scope != null && sound.scope != scope)) continue;
            PLAYING.remove(i);
            manager.stop(sound);
        }
    }

    public static void clear() {
        CUES.clear();
        if (!PLAYING.isEmpty()) {
            SoundManager manager = Minecraft.getInstance().getSoundManager();
            for (OwnedSound sound : PLAYING) manager.stop(sound);
            PLAYING.clear();
        }
    }

    /** Advances scheduled cues unless the game is paused; finished instances are forgotten either way. */
    public static void tick() {
        dispatch(CUES.advance(paused()));
        if (PLAYING.isEmpty()) return;
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        PLAYING.removeIf(sound -> !manager.isActive(sound));
    }

    private static boolean paused() {
        return Minecraft.getInstance().isPaused();
    }

    private static String pick(List<String> files) {
        return files.get(RANDOM.nextInt(files.size()));
    }

    private static void dispatch(List<SoundCueTimeline.Cue<Request>> cues) {
        for (SoundCueTimeline.Cue<Request> cue : cues) start(cue);
    }

    /**
     * Plays a due cue whose buffer is ready. An undecoded buffer defers the cue: on success it is requeued (and
     * dropped there if cancelled or cleared meanwhile), on failure it stays silent. Playing only completed buffers
     * makes vanilla attach the buffer synchronously, so a later {@link SoundManager#stop} always wins.
     */
    private static void start(SoundCueTimeline.Cue<Request> cue) {
        Minecraft client = Minecraft.getInstance();
        Request request = cue.payload();
        ResourceLocation id = CachedSoundFiles.monoLocation(request.id, request.file);
        CompletableFuture<?> buffer = CachedGunSounds.buffer(id);
        if (!buffer.isDone()) {
            buffer.whenCompleteAsync((decoded, error) -> {
                if (error != null) return;
                CUES.requeue(cue);
                dispatch(CUES.drain(paused()));
            }, client);
            return;
        }
        if (buffer.isCompletedExceptionally()) return;
        ClientLevel level = client.level;
        if (level == null) return;
        double x = request.x, y = request.y, z = request.z;
        boolean follow = cue.scope() != Scope.EVENT;
        if (follow) {
            Entity entity = level.getEntity(cue.owner());
            if (entity == null) return;
            x = entity.getX();
            y = entity.getEyeY();
            z = entity.getZ();
        }
        Sound sound = new Sound(id, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, false, false,
                request.range);
        OwnedSound instance = new OwnedSound(sound, cue.owner(), cue.scope(), x, y, z);
        client.getSoundManager().play(instance);
        PLAYING.add(instance);
    }

    /** Linearly attenuated cache sound; hand-timeline sounds track their owner's eyes until it is gone. */
    private static final class OwnedSound extends AbstractTickableSoundInstance {
        final int owner;
        final Scope scope;
        final WeighedSoundEvents events;

        OwnedSound(Sound sound, int owner, Scope scope, double x, double y, double z) {
            // Tickable instances only take a SoundEvent; resolve() below still supplies the fixed cache Sound.
            super(SoundEvent.createVariableRangeEvent(sound.getLocation()), SoundSource.PLAYERS, RANDOM);
            this.sound = sound;
            this.owner = owner;
            this.scope = scope;
            this.events = new WeighedSoundEvents(sound.getLocation(), null);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            return events;
        }

        @Override
        public void tick() {
            if (scope == Scope.EVENT) return;
            ClientLevel level = Minecraft.getInstance().level;
            Entity entity = level == null ? null : level.getEntity(owner);
            if (entity == null) return;
            x = entity.getX();
            y = entity.getEyeY();
            z = entity.getZ();
        }
    }
}
