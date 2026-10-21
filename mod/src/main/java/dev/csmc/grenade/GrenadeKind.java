package dev.csmc.grenade;

import org.jetbrains.annotations.Nullable;

/**
 * The six CS2 utility grenades, by canonical CS2 id (the {@code weapons.vdata} key without {@code weapon_}).
 * Gameplay numbers live in {@link GrenadeTuning}; they are authored defaults, not CS2 source values.
 */
public enum GrenadeKind {
    HE("hegrenade"),
    FLASH("flashbang"),
    SMOKE("smokegrenade"),
    MOLOTOV("molotov"),
    INCENDIARY("incgrenade"),
    DECOY("decoy");

    private static final GrenadeKind[] VALUES = values();

    private final String id;

    GrenadeKind(String id) {
        this.id = id;
    }

    /** Canonical id; also the item path ({@code csmc:<id>}) and the cache asset/sound folder. */
    public String id() {
        return id;
    }

    /** True for the two fire grenades, which leave a {@link GrenadeCloud} burning pool. */
    public boolean isFire() {
        return this == MOLOTOV || this == INCENDIARY;
    }

    /** True when the grenade must come to rest before its effect starts (smoke, decoy). */
    public boolean activatesAtRest() {
        return this == SMOKE || this == DECOY;
    }

    public static @Nullable GrenadeKind byId(String id) {
        for (GrenadeKind kind : VALUES) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return null;
    }

    /** Ordinal lookup for synced/saved data; out-of-range values fall back to {@link #HE}. */
    static GrenadeKind byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : HE;
    }
}
