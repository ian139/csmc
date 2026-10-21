package dev.csmc.client.grenade;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.csmc.CsmcMod;
import dev.csmc.client.grenade.mixin.GrenadeSoundEngineAccessor;
import dev.csmc.client.mixin.SoundEngineAccessor;
import dev.csmc.client.mixin.SoundManagerAccessor;
import dev.csmc.client.sound.CachedSoundFiles;
import dev.csmc.data.CachePaths;
import dev.csmc.grenade.GrenadeCloud;
import dev.csmc.grenade.GrenadeKind;
import dev.csmc.network.GrenadeSoundPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * Grenade audio from the local export cache ({@code sounds/<kind id>/manifest.json} {@code actions}), with vanilla
 * fallbacks when the cache lacks a file. Positional at the grenade, linearly attenuated by event. Fire pools carry
 * an entity-bound loop that stops when the cloud is removed, the world changes or the player disconnects.
 *
 * <p>Decoy reports are plain positional sounds: they never touch gun animation, recoil, reload or ammo state.
 */
final class GrenadeSounds {
    static final String THROW = "throw";
    static final String BOUNCE = "bounce";
    static final String DETONATE = "detonate";
    static final String LOOP = "loop";
    static final String EXTINGUISH = "extinguish";
    static final String DECOY = "decoy";
    /** Pin-pull aliases a manifest may use for the owner-local prime sound, in preference order. */
    static final List<String> PIN = List.of("pin", "pullpin", "pin_pull");
    private static final String[] ACTIONS = {THROW, BOUNCE, DETONATE, LOOP, EXTINGUISH, DECOY, "pin", "pullpin", "pin_pull"};
    private static final Pattern FILE = Pattern.compile("[a-z0-9_][a-z0-9_.-]*\\.ogg");
    private static final int LOOP_RANGE = 24;
    /** Events further than this from the camera are ignored outright (bounded work for hostile coordinates). */
    private static final double MAX_DISTANCE = 256;
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<GrenadeKind, Map<String, List<String>>> MANIFESTS = new EnumMap<>(GrenadeKind.class);
    private static final Map<Integer, FireLoop> LOOPS = new HashMap<>();
    /** Ended loops whose engine bookkeeping is not yet released; retried every tick until {@link #forget} completes. */
    private static final List<FireLoop> RETIRING = new ArrayList<>();
    /** Cloud id → client tick before which no new loop is attempted (muted category, missing buffer, reload). */
    private static final Map<Integer, Long> RETRY_AT = new HashMap<>();
    /** Ticks a freshly played loop may take to appear in the sound engine before it counts as not playing. */
    private static final int START_GRACE_TICKS = 3;
    private static final int RETRY_TICKS = 20;
    private static long tick;

    private GrenadeSounds() {}

    /**
     * Resource reload drops vanilla's decoded buffers and channels; re-read manifests so cache edits apply, and stop
     * and forget every loop so the next tick restarts loops for still-live fire clouds.
     */
    static void reload() {
        MANIFESTS.clear();
        stopLoops();
    }

    static void clear() {
        stopLoops();
    }

    private static void stopLoops() {
        for (FireLoop loop : LOOPS.values()) retire(loop);
        LOOPS.clear();
        RETRY_AT.clear();
    }

    /** Ends a loop and keeps ownership of its engine bookkeeping until {@link #forget} completes. */
    private static void retire(FireLoop loop) {
        if (!forget(loop)) RETIRING.add(loop);
    }

    private static GrenadeSoundEngineAccessor engine() {
        return (GrenadeSoundEngineAccessor) ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).csmc$soundEngine();
    }

    /** Whether the engine still owns a channel for this loop (vanilla {@code isActive} misses muted-category drops). */
    private static boolean playing(FireLoop loop) {
        return engine().csmc$instanceToChannel().containsKey(loop);
    }

    /**
     * Stops a loop for good; true once no engine bookkeeping refers to it. With a live channel vanilla's own tick
     * releases it (false: retry next tick, since a muted category may drop the channel before that cleanup); without
     * one vanilla never cleans up, so remove its orphaned delete-time, source and ticking entries here.
     */
    private static boolean forget(FireLoop loop) {
        loop.end();
        Minecraft.getInstance().getSoundManager().stop(loop);
        GrenadeSoundEngineAccessor engine = engine();
        if (engine.csmc$instanceToChannel().containsKey(loop)) return false;
        engine.csmc$soundDeleteTime().remove(loop);
        engine.csmc$instanceBySource().remove(loop.getSource(), loop);
        engine.csmc$tickingSounds().remove(loop);
        return true;
    }

    /** Existence-checked file names per action for one grenade; reads the manifest once per reload. */
    static Map<String, List<String>> prepare(GrenadeKind kind) {
        Map<String, List<String>> cached = MANIFESTS.get(kind);
        if (cached != null) return cached;
        Map<String, List<String>> actions = new HashMap<>();
        Path dir = CachePaths.root().resolve("sounds").resolve(kind.id());
        Path file = dir.resolve("manifest.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject listed = root.has("actions") && root.get("actions").isJsonObject() ? root.getAsJsonObject("actions") : new JsonObject();
                for (String action : ACTIONS) {
                    List<String> files = new ArrayList<>();
                    if (listed.has(action) && listed.get(action).isJsonArray()) {
                        for (JsonElement name : listed.getAsJsonArray(action)) {
                            if (name.isJsonPrimitive() && FILE.matcher(name.getAsString()).matches()
                                    && Files.isRegularFile(dir.resolve(name.getAsString()))) {
                                files.add(name.getAsString());
                            }
                        }
                    }
                    if (!files.isEmpty()) actions.put(action, List.copyOf(files));
                }
            } catch (IOException | RuntimeException e) {
                CsmcMod.LOGGER.warn("[csmc grenade sound] Cannot read {}; using vanilla placeholders", file, e);
                actions.clear();
            }
        }
        MANIFESTS.put(kind, Map.copyOf(actions));
        if (!actions.isEmpty()) {
            CsmcMod.LOGGER.info("[csmc grenade sound] {}: {}", kind.id(), actions);
            // Start decoding now so the first detonation is not late.
            var buffers = ((SoundEngineAccessor) ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager())
                    .csmc$soundEngine()).csmc$soundBuffers();
            for (List<String> files : actions.values()) {
                for (String name : files) buffers.getCompleteBuffer(Sound.SOUND_LISTER.idToFile(location(kind, name)));
            }
        }
        return MANIFESTS.get(kind);
    }

    private static ResourceLocation location(GrenadeKind kind, String file) {
        return ResourceLocation.fromNamespaceAndPath(CachedSoundFiles.NAMESPACE,
                kind.id() + "/" + file.substring(0, file.length() - ".ogg".length()));
    }

    static void handle(GrenadeSoundPayload payload) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        GrenadeKind kind = payload.grenade() == null ? null : GrenadeKind.byId(payload.grenade());
        String event = payload.event();
        double x = payload.x(), y = payload.y(), z = payload.z();
        if (level == null || kind == null || event == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        if (client.gameRenderer.getMainCamera().getPosition().distanceToSqr(x, y, z) > MAX_DISTANCE * MAX_DISTANCE) return;
        float volume = Float.isFinite(payload.volume()) ? Mth.clamp(payload.volume(), 0, 4) : 1;
        float pitch = Float.isFinite(payload.pitch()) ? Mth.clamp(payload.pitch(), 0.5f, 2) : 1;
        if (volume <= 0) return;
        // Fire loops are owned by tracked GrenadeCloud entities (see tick); the server keepalive carries no identity.
        if (LOOP.equals(event)) return;
        if (!THROW.equals(event) && !BOUNCE.equals(event) && !DETONATE.equals(event)
                && !EXTINGUISH.equals(event) && !DECOY.equals(event)) return;
        // The burst itself is visible to everyone in view; blinding is the server-targeted GrenadeFlashPayload.
        if (DETONATE.equals(event) && kind == GrenadeKind.FLASH) level.addParticle(ParticleTypes.FLASH, x, y, z, 0, 0, 0);
        // EXTINGUISH is a one-shot at the event position; the burning pool's loop stops when its cloud is removed.
        List<String> files = prepare(kind).get(event);
        if (files != null) {
            play(kind, files.get(RANDOM.nextInt(files.size())), x, y, z, volume, pitch, range(kind, event));
        } else {
            placeholder(level, kind, event, x, y, z, volume, pitch);
        }
    }

    private static int range(GrenadeKind kind, String event) {
        return switch (event) {
            case THROW, BOUNCE -> 24;
            case EXTINGUISH -> 32;
            case DECOY -> 96;
            default -> switch (kind) {
                case HE -> 128;
                case FLASH -> 96;
                case MOLOTOV, INCENDIARY -> 64;
                default -> 48;
            };
        };
    }

    private static void placeholder(ClientLevel level, GrenadeKind kind, String event, double x, double y, double z, float volume, float pitch) {
        SoundEvent sound;
        float base = 1;
        float tone = 1;
        switch (event) {
            case THROW -> { sound = SoundEvents.SNOWBALL_THROW; base = 0.6f; tone = 0.8f; }
            case BOUNCE -> { sound = kind == GrenadeKind.MOLOTOV ? SoundEvents.GLASS_HIT : SoundEvents.CHAIN_HIT; base = 0.7f; tone = 1.4f; }
            case EXTINGUISH -> sound = SoundEvents.FIRE_EXTINGUISH;
            // A harmless gun-like report; deliberately the same placeholder as uncached gunfire, nothing else.
            case DECOY -> { sound = SoundEvents.FIREWORK_ROCKET_BLAST; base = 3; tone = 1.5f + RANDOM.nextFloat() * 0.2f; }
            default -> {
                switch (kind) {
                    case HE -> { sound = SoundEvents.GENERIC_EXPLODE.value(); base = 4; }
                    case FLASH -> { sound = SoundEvents.FIREWORK_ROCKET_LARGE_BLAST; base = 4; tone = 1.3f; }
                    case SMOKE -> { sound = SoundEvents.FIRE_EXTINGUISH; base = 1.5f; tone = 0.6f; }
                    case MOLOTOV -> { sound = SoundEvents.GLASS_BREAK; base = 2; }
                    case INCENDIARY -> { sound = SoundEvents.FIRECHARGE_USE; base = 2; }
                    default -> { sound = SoundEvents.CHAIN_HIT; base = 0.5f; }
                }
            }
        }
        level.playLocalSound(x, y, z, sound, SoundSource.PLAYERS, base * volume, tone * pitch, false);
    }

    /**
     * Owner-local one-shot (e.g. the pin pull): the first cached file among {@code actions} from the grenade's
     * manifest, else the vanilla {@code fallback}.
     */
    static void local(GrenadeKind kind, List<String> actions, net.minecraft.world.phys.Vec3 at, SoundEvent fallback,
            float volume, float pitch) {
        Map<String, List<String>> manifest = prepare(kind);
        for (String action : actions) {
            List<String> files = manifest.get(action);
            if (files != null) {
                play(kind, files.get(RANDOM.nextInt(files.size())), at.x, at.y, at.z, 1, 1, 16);
                return;
            }
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) level.playLocalSound(at.x, at.y, at.z, fallback, SoundSource.PLAYERS, volume, pitch, false);
    }

    private static void play(GrenadeKind kind, String file, double x, double y, double z, float volume, float pitch, int range) {
        Sound sound = new Sound(location(kind, file), ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, false, false, range);
        Minecraft.getInstance().getSoundManager().play(new CachedSound(sound, x, y, z, volume, pitch));
    }

    /** Retired loops whose channel was still live: finish their cleanup once vanilla or a mute drops the channel. */
    static void finishRetiring() {
        if (!RETIRING.isEmpty()) RETIRING.removeIf(GrenadeSounds::forget);
    }

    /** Client tick: make sure each live fire pool has its loop; loops end themselves when their cloud does. */
    static void tick() {
        tick++;
        var loops = LOOPS.entrySet().iterator();
        while (loops.hasNext()) {
            var entry = loops.next();
            FireLoop loop = entry.getValue();
            if (loop.isStopped()) {
                retire(loop);
                loops.remove();
            } else if (tick - loop.startedTick > START_GRACE_TICKS && !playing(loop)) {
                // Dropped by the engine (reload, muted category, failed buffer): forget it and retry later.
                retire(loop);
                loops.remove();
                RETRY_AT.put(entry.getKey(), tick + RETRY_TICKS);
            }
        }
        RETRY_AT.keySet().removeIf(id -> !trackedFire(id));
        for (GrenadeCloud cloud : GrenadeEntities.CLOUDS) {
            if (cloud.getKind() == GrenadeKind.MOLOTOV || cloud.getKind() == GrenadeKind.INCENDIARY) ensureLoop(cloud);
        }
    }

    private static boolean trackedFire(int id) {
        for (GrenadeCloud cloud : GrenadeEntities.CLOUDS) if (cloud.getId() == id) return true;
        return false;
    }

    private static void ensureLoop(GrenadeCloud cloud) {
        if (cloud.isRemoved() || LOOPS.containsKey(cloud.getId())) return;
        Long retry = RETRY_AT.get(cloud.getId());
        if (retry != null && tick < retry) return;
        RETRY_AT.remove(cloud.getId());
        List<String> files = prepare(cloud.getKind()).get(LOOP);
        FireLoop loop;
        if (files != null) {
            Sound sound = new Sound(location(cloud.getKind(), files.get(RANDOM.nextInt(files.size()))), ConstantFloat.of(1),
                    ConstantFloat.of(1), 1, Sound.Type.FILE, false, false, LOOP_RANGE);
            loop = new FireLoop(cloud, sound, tick);
        } else {
            loop = new FireLoop(cloud, null, tick);
        }
        LOOPS.put(cloud.getId(), loop);
        Minecraft.getInstance().getSoundManager().play(loop);
    }

    /** One-shot positional instance whose {@link Sound} is fixed instead of looked up in sounds.json. */
    private static final class CachedSound extends AbstractSoundInstance {
        private final WeighedSoundEvents events;

        CachedSound(Sound sound, double x, double y, double z, float volume, float pitch) {
            super(sound.getLocation(), SoundSource.PLAYERS, RANDOM);
            this.sound = sound;
            this.events = new WeighedSoundEvents(sound.getLocation(), null);
            this.x = x;
            this.y = y;
            this.z = z;
            this.volume = volume;
            this.pitch = pitch;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            return events;
        }
    }

    /** Looping burn sound pinned to a fire cloud; fades with the pool and stops when the cloud is gone. */
    private static final class FireLoop extends AbstractTickableSoundInstance {
        private final GrenadeCloud cloud;
        private final Sound cached;
        private final WeighedSoundEvents events;
        final long startedTick;

        FireLoop(GrenadeCloud cloud, Sound cached, long startedTick) {
            super(cached == null ? SoundEvents.FIRE_AMBIENT : SoundEvent.createVariableRangeEvent(cached.getLocation()),
                    SoundSource.PLAYERS, RANDOM);
            this.startedTick = startedTick;
            this.cloud = cloud;
            this.cached = cached;
            this.events = cached == null ? null : new WeighedSoundEvents(cached.getLocation(), null);
            if (cached != null) this.sound = cached;
            this.looping = true;
            this.delay = 0;
            this.volume = cached == null ? 1.5f : 1;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.x = cloud.getX();
            this.y = cloud.getY() + 0.5;
            this.z = cloud.getZ();
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            return cached == null ? super.resolve(manager) : events;
        }

        void end() {
            stop();
        }

        @Override
        public void tick() {
            Minecraft client = Minecraft.getInstance();
            if (cloud.isRemoved() || client.level == null || cloud.level() != client.level) {
                stop();
                return;
            }
            GrenadeEntities.CloudState state = GrenadeEntities.state(cloud);
            float life = state.life(state.age(client.level.getGameTime(), 0));
            volume = (cached == null ? 1.5f : 1) * Math.max(0.02f, life);
            x = cloud.getX();
            y = cloud.getY() + 0.5;
            z = cloud.getZ();
        }
    }
}
