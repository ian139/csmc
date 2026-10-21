package dev.csmc.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Knife event sounds from {@code sounds/<knife>/manifest.json} {@code actions} (deploy, slash, stab, hit, hitwall,
 * backstab, inspect → OGG files in that directory), played positionally through the shared cache sound path. An
 * action the cache lacks uses a vanilla stand-in; an uncached inspect stays silent.
 */
public final class KnifeSounds {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern FILE = Pattern.compile("[a-z0-9_.-]+\\.ogg");
    private static final int RANGE = 16;
    private static final Map<String, Map<String, List<String>>> KNIVES = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private KnifeSounds() {}

    /** Drops parsed manifests (resource reload). */
    static void clear() {
        KNIVES.clear();
    }

    public static void play(ClientLevel level, String knife, String event, double x, double y, double z) {
        if (!ID.matcher(knife).matches()) return;
        // A slash backstab sounds like a backstab unless the knife authors its own.
        List<String> files = actions(knife).getOrDefault(event, List.of());
        if (files.isEmpty() && "slash_backstab".equals(event)) {
            event = "backstab";
            files = actions(knife).getOrDefault(event, List.of());
        }
        if (!files.isEmpty()) {
            CachedGunSounds.play(knife, files.get(RANDOM.nextInt(files.size())), x, y, z, RANGE);
            return;
        }
        SoundEvent fallback = switch (event) {
            case "deploy" -> SoundEvents.ARMOR_EQUIP_IRON.value();
            case "slash" -> SoundEvents.PLAYER_ATTACK_SWEEP;
            case "stab" -> SoundEvents.PLAYER_ATTACK_STRONG;
            case "hit" -> SoundEvents.PLAYER_ATTACK_KNOCKBACK;
            case "hitwall" -> SoundEvents.PLAYER_ATTACK_WEAK;
            case "backstab" -> SoundEvents.PLAYER_ATTACK_CRIT;
            default -> null;
        };
        if (fallback != null) {
            level.playLocalSound(x, y, z, fallback, SoundSource.PLAYERS, 0.8f, 0.95f + RANDOM.nextFloat() * 0.1f, false);
        }
    }

    /** Existence-checked files per action, parsed once and pre-buffered. */
    private static Map<String, List<String>> actions(String knife) {
        Map<String, List<String>> cached = KNIVES.get(knife);
        if (cached != null) return cached;
        Map<String, List<String>> actions = Map.of();
        Path dir = CachePaths.root().resolve("sounds").resolve(knife);
        Path file = dir.resolve("manifest.json");
        if (Files.isRegularFile(file)) {
            try {
                actions = parse(Files.readString(file, StandardCharsets.UTF_8), dir);
            } catch (IOException | RuntimeException e) {
                CsmcMod.LOGGER.warn("[csmc sound] Cannot read knife sound manifest for {}; using vanilla sounds", knife, e);
            }
        }
        KNIVES.put(knife, actions);
        for (List<String> files : actions.values()) {
            for (String name : files) CachedGunSounds.buffer(CachedSoundFiles.location(knife, name));
        }
        if (!actions.isEmpty()) CsmcMod.LOGGER.info("[csmc sound] {}: knife actions {}", knife, actions);
        return actions;
    }

    private static Map<String, List<String>> parse(String json, Path dir) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, List<String>> out = new HashMap<>();
        if (!root.has("actions") || !root.get("actions").isJsonObject()) return Map.of();
        for (var entry : root.getAsJsonObject("actions").entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            List<String> files = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                if (!element.isJsonPrimitive()) continue;
                String name = element.getAsString();
                if (FILE.matcher(name).matches() && Files.isRegularFile(dir.resolve(name))) files.add(name);
            }
            if (!files.isEmpty()) out.put(entry.getKey(), List.copyOf(files));
        }
        return Map.copyOf(out);
    }
}
