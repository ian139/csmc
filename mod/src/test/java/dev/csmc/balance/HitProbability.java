package dev.csmc.balance;

import java.util.HashMap;
import java.util.Map;

/**
 * Probability that a bullet lands within {@code radius} (tangent units) of the aim point under
 * {@code Ballistics.applySpread}: the sum of two random offsets with uniform angle and radius uniform in
 * {@code [0, inaccuracy]} and {@code [0, spread]}. The relative angle is integrated in closed form and both radii
 * by a midpoint rule.
 */
final class HitProbability {
    private static final int STEPS = 100;
    private static final Map<String, Double> CACHE = new HashMap<>();

    private HitProbability() {}

    static double of(double inaccuracy, double spread, double radius) {
        String key = inaccuracy + "/" + spread + "/" + radius;
        return CACHE.computeIfAbsent(key, k -> compute(inaccuracy, spread, radius));
    }

    private static double compute(double r0, double r1, double t) {
        if (r0 + r1 <= t) {
            return 1.0;
        }
        int n0 = r0 > 0.0 ? STEPS : 1;
        int n1 = r1 > 0.0 ? STEPS : 1;
        double sum = 0.0;
        for (int i = 0; i < n0; i++) {
            double x = r0 * (i + 0.5) / n0;
            for (int j = 0; j < n1; j++) {
                double y = r1 * (j + 0.5) / n1;
                sum += within(x, y, t);
            }
        }
        return sum / (n0 * (double) n1);
    }

    /** Share of relative angles φ with x² + y² + 2xy·cos φ ≤ t². */
    private static double within(double x, double y, double t) {
        if (x + y <= t) {
            return 1.0;
        }
        if (Math.abs(x - y) > t) {
            return 0.0;
        }
        double c = (t * t - x * x - y * y) / (2.0 * x * y);
        return 1.0 - Math.acos(Math.max(-1.0, Math.min(1.0, c))) / Math.PI;
    }
}
