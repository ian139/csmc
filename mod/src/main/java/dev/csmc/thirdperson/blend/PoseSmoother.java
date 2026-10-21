package dev.csmc.thirdperson.blend;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/** Render-thread-only, critically damped transitions. State belongs to an entity, never a shared player model. */
public final class PoseSmoother {
    private static final double FREQUENCY = 20.0;
    private static final int CHANNELS = 10;
    private final Int2ObjectOpenHashMap<State> entities = new Int2ObjectOpenHashMap<>();

    public void smooth(int entityId, double nowSeconds, PoseFrame target, PoseFrame vanilla, PoseFrame out) {
        State state = entities.get(entityId);
        if (state == null) {
            state = new State(nowSeconds, vanilla);
            entities.put(entityId, state);
        }
        double dt = Math.max(0.0, Math.min(0.1, nowSeconds - state.time));
        state.time = nowSeconds;
        boolean right = target.rightArmWeight > 0F;
        boolean left = target.leftArmWeight > 0F;
        double decay = Math.exp(-FREQUENCY * dt);
        step(state, 0, right ? target.rightArmX : state.target[0], dt, decay, true);
        step(state, 1, right ? target.rightArmY : state.target[1], dt, decay, true);
        step(state, 2, right ? target.rightArmZ : state.target[2], dt, decay, true);
        step(state, 3, left ? target.leftArmX : state.target[3], dt, decay, true);
        step(state, 4, left ? target.leftArmY : state.target[4], dt, decay, true);
        step(state, 5, left ? target.leftArmZ : state.target[5], dt, decay, true);
        step(state, 6, clamp(target.rightArmWeight), dt, decay, false);
        step(state, 7, clamp(target.leftArmWeight), dt, decay, false);
        step(state, 8, target.bodyYOffset, dt, decay, false);
        step(state, 9, target.headXOffset, dt, decay, false);
        out.rightArmX = (float) state.value[0];
        out.rightArmY = (float) state.value[1];
        out.rightArmZ = (float) state.value[2];
        out.leftArmX = (float) state.value[3];
        out.leftArmY = (float) state.value[4];
        out.leftArmZ = (float) state.value[5];
        out.rightArmWeight = clamp((float) state.value[6]);
        out.leftArmWeight = clamp((float) state.value[7]);
        out.bodyYOffset = (float) state.value[8];
        out.headXOffset = (float) state.value[9];
    }

    private static void step(State state, int channel, double target, double dt, double decay, boolean angle) {
        double current = state.value[channel];
        if (angle) target = current + Math.IEEEremainder(target - current, Math.PI * 2.0);
        state.target[channel] = target;
        if (dt == 0.0) return;
        // Exact solution of x'' + 2*w*x' + w*w*(x-target) = 0 for a constant frame target.
        double displacement = current - target;
        double term = state.velocity[channel] + FREQUENCY * displacement;
        double next = target + (displacement + term * dt) * decay;
        double velocity = (state.velocity[channel] - FREQUENCY * term * dt) * decay;
        // A changed target can inherit velocity pointing through it. Never cross the new endpoint.
        if ((target - current) * (target - next) <= 0.0) {
            next = target;
            velocity = 0.0;
        }
        state.value[channel] = next;
        state.velocity[channel] = velocity;
    }

    public void forget(int entityId) { entities.remove(entityId); }
    public void clear() { entities.clear(); }
    public int trackedEntities() { return entities.size(); }

    private static float clamp(float weight) { return Math.max(0F, Math.min(1F, weight)); }

    private static final class State {
        private final double[] value = new double[CHANNELS];
        private final double[] velocity = new double[CHANNELS];
        private final double[] target = new double[CHANNELS];
        private double time;

        private State(double now, PoseFrame vanilla) {
            time = now;
            value[0] = vanilla.rightArmX;
            value[1] = vanilla.rightArmY;
            value[2] = vanilla.rightArmZ;
            value[3] = vanilla.leftArmX;
            value[4] = vanilla.leftArmY;
            value[5] = vanilla.leftArmZ;
            System.arraycopy(value, 0, target, 0, CHANNELS);
        }
    }
}
