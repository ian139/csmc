package dev.csmc.movement;

import dev.csmc.movement.physics.CsMovementParams;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Values of the {@code csmcMovement} gamerule. {@link #OFF} keeps vanilla movement; every other value selects the
 * matching {@link CsMovementParams} preset. Commands and the gamerule screen use the lower-case {@link #toString()}
 * form ({@code /gamerule csmcMovement bhop}); level.dat stores {@link #name()}.
 */
public enum MovementMode {
    OFF(null),
    COMPETITIVE(CsMovementParams.COMPETITIVE),
    BHOP(CsMovementParams.BHOP),
    SURF(CsMovementParams.SURF);

    @Nullable
    private final CsMovementParams params;

    MovementMode(@Nullable CsMovementParams params) {
        this.params = params;
    }

    /** Preset for this mode, or null for {@link #OFF}. */
    @Nullable
    public CsMovementParams params() {
        return params;
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
