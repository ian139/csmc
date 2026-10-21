package dev.csmc.melee;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Picks which exported CS2 viewmodel clip plays for a knife action. The manifest maps each action to one clip
 * ({@code animations}, e.g. slash → {@code light_miss1_butterfly}); its variants are the knife's other clips of the
 * same family and suffix ({@code light_miss2_butterfly}, ...). Draw also takes CS2's {@code lookat01_draw} variant.
 * Consecutive picks of an action never repeat a variant while another exists; idles alternate per loop.
 * Not thread-safe: one instance per knife, used from the render thread.
 */
public final class KnifeClipChooser implements dev.csmc.viewmodel.ViewmodelClipChooser {
    /** Actions with clips, in load order. */
    public static final List<String> ACTIONS = List.of("idle", "draw", "inspect", "slash", "stab", "slash_hit",
            "stab_hit", "slash_backstab", "backstab");
    private static final Map<String, String> FAMILY = Map.of("idle", "idle", "draw", "draw", "inspect", "lookat",
            "slash", "light_miss", "stab", "heavy_miss", "slash_hit", "light_hit", "stab_hit", "heavy_hit",
            "slash_backstab", "light_backstab", "backstab", "heavy_backstab");

    private final Map<String, List<String>> variants = new HashMap<>();
    private final Map<String, Integer> last = new HashMap<>();

    public KnifeClipChooser(Map<String, String> animations, Collection<String> clips) {
        for (String action : ACTIONS) {
            List<String> list = variants(FAMILY.get(action), animations.get(action), clips);
            if ("draw".equals(action)) {
                String mapped = animations.get(action);
                String suffix = mapped == null ? null : suffix("draw", mapped);
                if (suffix != null) {
                    for (String clip : sorted(clips)) {
                        if (matches(clip, "lookat", "_draw" + suffix) && !list.contains(clip)) list.add(clip);
                    }
                }
            }
            if (!list.isEmpty()) variants.put(action, List.copyOf(list));
        }
    }

    /** Clip names that may play for {@code action}; empty when the knife has none. */
    public List<String> variants(String action) {
        return variants.getOrDefault(action, List.of());
    }

    /** One variant of the cue's action (see {@link #pick}) at its natural speed; knife clips keep their length. */
    @Override
    public void plan(dev.csmc.viewmodel.ViewmodelCue cue, Durations durations, RandomGenerator random,
                     dev.csmc.viewmodel.ClipPlan out) {
        out.clear();
        String clip = pick(cue.action(), random);
        if (clip != null) out.add(clip, durations.duration(clip));
    }

    /** Knives have no magazine: {@link #idle(int)}. */
    @Override
    public String idle(int loop, int magazine) {
        return idle(loop);
    }

    /**
     * A variant for a new {@code action} playback, never the previous pick when another exists. Without variants,
     * a slash backstab falls back to the backstab clip and a hit to its plain swing; null if none.
     */
    public String pick(String action, RandomGenerator random) {
        List<String> list = variants(action);
        if (list.isEmpty()) {
            String fallback = switch (action) {
                case "slash_backstab" -> "backstab";
                case "slash_hit" -> "slash";
                case "stab_hit" -> "stab";
                default -> null;
            };
            return fallback == null ? null : pick(fallback, random);
        }
        int previous = last.getOrDefault(action, -1);
        int index;
        if (list.size() == 1) index = 0;
        else if (previous < 0) index = random.nextInt(list.size());
        else {
            index = random.nextInt(list.size() - 1);
            if (index >= previous) index++;
        }
        last.put(action, index);
        return list.get(index);
    }

    /** Idle clip for loop number {@code loop} (0, 1, ...): idle variants alternate in order; null if none. */
    public String idle(int loop) {
        List<String> list = variants("idle");
        return list.isEmpty() ? null : list.get(Math.floorMod(loop, list.size()));
    }

    /** The hit variant of a swing ({@code slash → slash_hit}, {@code stab → stab_hit}); others unchanged. */
    public static String hitAction(String action) {
        return switch (action) {
            case "slash" -> "slash_hit";
            case "stab" -> "stab_hit";
            default -> action;
        };
    }

    /** The procedural (third-person) motion for a clip action: hit/backstab variants swing like their base. */
    public static String baseAction(String action) {
        return switch (action) {
            case "slash_hit", "slash_backstab" -> "slash";
            case "stab_hit" -> "stab";
            default -> action;
        };
    }

    private static List<String> variants(String family, String mapped, Collection<String> clips) {
        List<String> list = new ArrayList<>();
        String suffix = mapped == null ? null : suffix(family, mapped);
        if (suffix != null) {
            for (String clip : sorted(clips)) if (matches(clip, family, suffix)) list.add(clip);
        }
        if (list.isEmpty() && mapped != null && clips.contains(mapped)) list.add(mapped);
        return list;
    }

    /** {@code mapped} without its family prefix and variant digits ({@code light_miss1_x → _x}), or null. */
    private static String suffix(String family, String mapped) {
        if (!mapped.startsWith(family)) return null;
        int i = family.length();
        while (i < mapped.length() && Character.isDigit(mapped.charAt(i))) i++;
        String rest = mapped.substring(i);
        return rest.isEmpty() || rest.startsWith("_") ? rest : null;
    }

    private static boolean matches(String clip, String family, String suffix) {
        if (!clip.startsWith(family) || !clip.endsWith(suffix) || clip.length() < family.length() + suffix.length()) {
            return false;
        }
        for (int i = family.length(); i < clip.length() - suffix.length(); i++) {
            if (!Character.isDigit(clip.charAt(i))) return false;
        }
        return true;
    }

    private static List<String> sorted(Collection<String> clips) {
        List<String> list = new ArrayList<>(clips);
        list.sort(null);
        return list;
    }
}
