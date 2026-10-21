package dev.csmc.client.sound;

import dev.csmc.CsmcMod;
import dev.csmc.client.mixin.SoundEngineAccessor;
import dev.csmc.client.mixin.SoundManagerAccessor;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.data.CachePaths;
import dev.csmc.data.GunSoundManifest;
import dev.csmc.network.GunSoundPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * Plays server-confirmed {@link GunSoundPayload} events from this client's own CS2 export cache, falling back to
 * the vanilla placeholder sounds when the cache lacks a file. Everything runs on the client thread.
 */
public final class CachedGunSounds implements ClientModInitializer {
    /** Within this distance the close report plays; beyond it the exported distant layer, when present. */
    private static final double DISTANT_FROM = 24;
    private static final int NEAR_FIRE_RANGE = 48;
    private static final int DISTANT_FIRE_RANGE = 160;
    private static final int HANDLING_RANGE = 16;
    private static final Pattern WEAPON = Pattern.compile("[a-z0-9_]+");
    private static final Map<String, WeaponSounds> WEAPONS = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static long tick;

    /** Existence-checked files for one weapon; uncached draw/scope actions stay silent. */
    record WeaponSounds(String weapon, List<String> fireNear, List<String> fireDistant, List<String> draw,
                        List<String> zoomIn, List<String> zoomOut, List<GunSoundManifest.ReloadPart> reload) {
        static final WeaponSounds NONE = new WeaponSounds("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private record Pending(int shooter, long dueTick, String weapon, List<String> files, double x, double y, double z) {}

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(GunSoundPayload.TYPE, (payload, context) -> handle(payload));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tick++;
            if (client.level == null) {
                PENDING.clear();
                return;
            }
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                Pending pending = PENDING.get(i);
                if (pending.dueTick > tick) continue;
                PENDING.remove(i);
                play(pending.weapon, pick(pending.files), pending.x, pending.y, pending.z, HANDLING_RANGE);
            }
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return CsmcMod.id("cached_gun_sounds"); }
            // Vanilla's sound reload drops every decoded buffer; re-read manifests too so cache edits apply.
            @Override public void onResourceManagerReload(ResourceManager manager) {
                WEAPONS.clear();
                KnifeSounds.clear();
            }
        });
    }

    /** Lazily reads the weapon's sound manifest and starts decoding its files into vanilla's buffer cache. */
    public static WeaponSounds prepare(String weapon) {
        WeaponSounds cached = WEAPONS.get(weapon);
        if (cached != null) return cached;
        WeaponSounds sounds = WeaponSounds.NONE;
        if (WEAPON.matcher(weapon).matches()) {
            Path dir = CachePaths.root().resolve("sounds").resolve(weapon);
            try {
                GunSoundManifest manifest = GunSoundManifest.read(dir);
                List<GunSoundManifest.ReloadPart> reload = new ArrayList<>();
                for (GunSoundManifest.ReloadPart part : manifest.reloadParts()) {
                    List<String> files = existing(dir, part.files());
                    if (!files.isEmpty()) reload.add(new GunSoundManifest.ReloadPart(part.event(), files, part.phase()));
                }
                sounds = new WeaponSounds(weapon, existing(dir, manifest.fireNear()), existing(dir, manifest.fireDistant()),
                        existing(dir, manifest.draw()), existing(dir, manifest.zoomIn()), existing(dir, manifest.zoomOut()),
                        List.copyOf(reload));
            } catch (IOException | RuntimeException e) {
                CsmcMod.LOGGER.warn("[csmc sound] Cannot read sound manifest for {}; using placeholder sounds", weapon, e);
            }
        }
        WEAPONS.put(weapon, sounds);
        if (sounds != WeaponSounds.NONE) {
            CsmcMod.LOGGER.info("[csmc sound] {}: fire={} distant={} draw={} zoomIn={} zoomOut={} reload={}", weapon, sounds.fireNear,
                    sounds.fireDistant, sounds.draw, sounds.zoomIn, sounds.zoomOut,
                    sounds.reload.stream().map(p -> p.files().get(0) + "@" + p.phase()).toList());
            for (String file : allFiles(sounds)) buffer(CachedSoundFiles.location(weapon, file));
        }
        return sounds;
    }

    /** Dev smoke: resolves every cached file of a weapon through vanilla's buffer library and logs each result. */
    public static void devResolve(String weapon) {
        CachedSoundFiles.verbose = true;
        WeaponSounds sounds = prepare(weapon);
        if (sounds == WeaponSounds.NONE || allFiles(sounds).isEmpty()) {
            CsmcMod.LOGGER.warn("[csmc sound] {}: no cached sounds; events use vanilla placeholders", weapon);
            return;
        }
        for (String file : allFiles(sounds)) {
            ResourceLocation id = CachedSoundFiles.location(weapon, file);
            buffer(id).whenComplete((buffer, error) -> {
                if (error != null) CsmcMod.LOGGER.warn("[csmc sound] {} did not resolve", id, error);
                else CsmcMod.LOGGER.info("[csmc sound] Resolved {} via SoundBufferLibrary -> {}", id,
                        CachedSoundFiles.file(Sound.SOUND_LISTER.idToFile(id)));
            });
        }
    }

    /** Requests (or returns the cached) decoded buffer through vanilla's {@code SoundBufferLibrary}. */
    static CompletableFuture<?> buffer(ResourceLocation sound) {
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        var buffers = ((SoundEngineAccessor) ((SoundManagerAccessor) manager).csmc$soundEngine()).csmc$soundBuffers();
        return buffers.getCompleteBuffer(Sound.SOUND_LISTER.idToFile(sound));
    }

    static List<String> allFiles(WeaponSounds sounds) {
        List<String> all = new ArrayList<>(sounds.fireNear);
        all.addAll(sounds.fireDistant);
        all.addAll(sounds.draw);
        all.addAll(sounds.zoomIn);
        all.addAll(sounds.zoomOut);
        for (GunSoundManifest.ReloadPart part : sounds.reload) all.addAll(part.files());
        return all;
    }

    private static List<String> existing(Path dir, List<String> files) {
        List<String> out = new ArrayList<>(files.size());
        for (String file : files) if (Files.isRegularFile(dir.resolve(file))) out.add(file);
        return List.copyOf(out);
    }

    private static void handle(GunSoundPayload payload) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || !WEAPON.matcher(payload.weapon()).matches()) return;
        WeaponSounds sounds = prepare(payload.weapon());
        // Any newer event from this shooter supersedes the rest of an in-progress reload; the server's
        // completion may arrive before the last scheduled part because of latency, so it keeps them.
        if (payload.event() != GunSoundPayload.RELOAD_END) PENDING.removeIf(pending -> pending.shooter == payload.shooter());
        double x = payload.x(), y = payload.y(), z = payload.z();
        switch (payload.event()) {
            case GunSoundPayload.FIRE -> {
                CachedGunRendering.serverAnimation(payload.shooter(), CsmcMod.id(payload.weapon()), "fire", 0,
                        payload.sequence(), payload.magazine(), 0);
                double distance = Math.sqrt(client.gameRenderer.getMainCamera().getPosition().distanceToSqr(x, y, z));
                if (distance > DISTANT_FROM && !sounds.fireDistant.isEmpty()) {
                    play(payload.weapon(), pick(sounds.fireDistant), x, y, z, DISTANT_FIRE_RANGE);
                } else if (!sounds.fireNear.isEmpty()) {
                    play(payload.weapon(), pick(sounds.fireNear), x, y, z, NEAR_FIRE_RANGE);
                } else {
                    placeholder(level, x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, 3.0f, 1.5f + RANDOM.nextFloat() * 0.2f);
                }
            }
            case GunSoundPayload.RELOAD_START -> {
                CachedGunRendering.serverAnimation(payload.shooter(), CsmcMod.id(payload.weapon()), "reload",
                        Math.max(1, payload.durationTicks()) / 20f, 0, payload.magazine(), payload.rounds());
                if (sounds.reload.isEmpty()) {
                    placeholder(level, x, y, z, SoundEvents.IRON_TRAPDOOR_OPEN, 0.5f, 1.2f);
                } else {
                    for (GunSoundManifest.ReloadPart part : sounds.reload) {
                        long due = tick + Math.round(part.phase() * Math.max(1, payload.durationTicks()));
                        PENDING.add(new Pending(payload.shooter(), due, payload.weapon(), part.files(), x, y, z));
                    }
                }
            }
            case GunSoundPayload.RELOAD_END -> {
                CachedGunRendering.stopServerAnimation(payload.shooter(), "reload");
                if (sounds.reload.isEmpty()) placeholder(level, x, y, z, SoundEvents.IRON_TRAPDOOR_CLOSE, 0.5f, 1.4f);
            }
            case GunSoundPayload.DRAW -> {
                CachedGunRendering.serverAnimation(payload.shooter(), CsmcMod.id(payload.weapon()), "draw", 0);
                // There was no vanilla draw placeholder; an uncached draw stays silent.
                if (!sounds.draw.isEmpty()) play(payload.weapon(), pick(sounds.draw), x, y, z, HANDLING_RANGE);
            }
            case GunSoundPayload.RELOAD_CANCEL -> CachedGunRendering.stopServerAnimation(payload.shooter(), "reload");
            case GunSoundPayload.ZOOM_IN -> {
                if (!sounds.zoomIn.isEmpty()) play(payload.weapon(), pick(sounds.zoomIn), x, y, z, HANDLING_RANGE);
            }
            case GunSoundPayload.ZOOM_OUT -> {
                if (!sounds.zoomOut.isEmpty()) play(payload.weapon(), pick(sounds.zoomOut), x, y, z, HANDLING_RANGE);
            }
            default -> { }
        }
    }


    private static String pick(List<String> files) {
        return files.get(RANDOM.nextInt(files.size()));
    }

    private static void placeholder(ClientLevel level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
        level.playLocalSound(x, y, z, sound, SoundSource.PLAYERS, volume, pitch, false);
    }

    static void play(String weapon, String file, double x, double y, double z, int range) {
        ResourceLocation id = CachedSoundFiles.location(weapon, file);
        Sound sound = new Sound(id, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, false, false, range);
        Minecraft.getInstance().getSoundManager().play(new CachedSoundInstance(sound, x, y, z));
    }

    /** Positional, linearly attenuated instance whose {@link Sound} is fixed instead of looked up in sounds.json. */
    private static final class CachedSoundInstance extends AbstractSoundInstance {
        private final WeighedSoundEvents events;

        CachedSoundInstance(Sound sound, double x, double y, double z) {
            super(sound.getLocation(), SoundSource.PLAYERS, RANDOM);
            this.sound = sound;
            this.events = new WeighedSoundEvents(sound.getLocation(), null);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            return events;
        }
    }
}
