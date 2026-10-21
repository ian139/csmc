package dev.csmc.client.thirdperson.blend;

import dev.csmc.thirdperson.blend.PoseFrame;
import dev.csmc.client.thirdperson.PoseContext;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/** Subtle procedural CS-like upper-body movement; never writes the model's legs or body tilt. */
public final class MovementLayer {
    private static final Int2ObjectOpenHashMap<Landing> LANDINGS = new Int2ObjectOpenHashMap<>();

    private MovementLayer() {}

    public static void apply(PoseContext context, PoseFrame pose) {
        int id = context.player().getId();
        Landing landing = LANDINGS.get(id);
        if (landing == null) {
            landing = new Landing(context.airborne());
            LANDINGS.put(id, landing);
        }
        if (landing.airborne && !context.airborne()) landing.landedAt = context.nowSeconds();
        landing.airborne = context.airborne();
        // Remote entity delta movement is not authoritative; interpolate received positions instead.
        double verticalSpeed = context.player().getY() - context.player().yOld;
        dev.csmc.thirdperson.blend.MovementLayer.applyOffsets(pose, context.airborne(), context.crouching(), verticalSpeed,
                context.limbSwing(), context.limbSwingAmount(), context.nowSeconds() - landing.landedAt);
    }


    public static void forget(int entityId) { LANDINGS.remove(entityId); }
    public static void clear() { LANDINGS.clear(); }

    private static final class Landing {
        private boolean airborne;
        private double landedAt = Double.NEGATIVE_INFINITY;

        private Landing(boolean airborne) { this.airborne = airborne; }
    }
}
