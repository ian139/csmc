package dev.csmc.skin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Player-facing kit names derived from the local index, without changing the stored catalog names. */
public final class SkinNames {
    /** Doppler-family internal names: optional gamma prefix, phase or gem, then optional weapon variant suffix. */
    private static final Pattern PHASE = Pattern.compile("am_(?:gamma_)?doppler_phase([1-4])(?:_[a-z0-9]+)?");
    private static final Pattern GEM = Pattern.compile("am_(ruby|sapphire|blackpearl|emerald)_marbleized(?:_[a-z0-9]+)?");

    private SkinNames() { }

    /**
     * Display name for a kit: the index display name unchanged, except Doppler and Gamma Doppler kits, whose shared
     * display name gains the phase or gem encoded in the internal name (e.g. {@code Doppler (Phase 2)},
     * {@code Gamma Doppler (Emerald)}).
     */
    public static String displayName(SkinCatalog.Kit kit) {
        String base = kit.displayName();
        if (!"Doppler".equals(base) && !"Gamma Doppler".equals(base)) return base;
        String suffix = suffix(kit.name());
        return suffix == null ? base : base + " (" + suffix + ")";
    }

    private static String suffix(String name) {
        if (name == null) return null;
        Matcher phase = PHASE.matcher(name);
        if (phase.matches()) return "Phase " + phase.group(1);
        Matcher gem = GEM.matcher(name);
        if (!gem.matches()) return null;
        return switch (gem.group(1)) {
            case "ruby" -> "Ruby";
            case "sapphire" -> "Sapphire";
            case "blackpearl" -> "Black Pearl";
            default -> "Emerald";
        };
    }
}
