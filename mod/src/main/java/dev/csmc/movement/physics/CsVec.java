package dev.csmc.movement.physics;

/** Mutable 3-vector in Source units on Minecraft axes: x east, y up, z south. Reused to avoid allocation. */
public final class CsVec {
    public double x;
    public double y;
    public double z;

    public CsVec() {}

    public CsVec(double x, double y, double z) {
        set(x, y, z);
    }

    public CsVec set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public CsVec set(CsVec other) {
        return set(other.x, other.y, other.z);
    }

    public double dot(CsVec other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
