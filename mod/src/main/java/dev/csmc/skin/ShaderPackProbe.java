package dev.csmc.skin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BooleanSupplier;

/**
 * Reflective, dependency-free reader of a shader-pack API shaped like Iris's {@code IrisApi}: a static
 * {@code getInstance()} and an instance {@code boolean isShaderPackInUse()}.
 */
public final class ShaderPackProbe {
    /** Iris's stable public API (v0). */
    public static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    private ShaderPackProbe() {
    }

    /**
     * Probe for {@code apiClass}, or {@code null} when that class or its methods are absent. The probe reports
     * {@code true} if the API throws, so callers fall back to their shader-pack-safe path.
     */
    public static BooleanSupplier of(String apiClass) {
        MethodHandle inUse;
        try {
            Class<?> type = Class.forName(apiClass, false, ShaderPackProbe.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle instance = lookup.findStatic(type, "getInstance", MethodType.methodType(type));
            MethodHandle query = lookup.findVirtual(type, "isShaderPackInUse", MethodType.methodType(boolean.class));
            inUse = MethodHandles.foldArguments(query, instance).asType(MethodType.methodType(boolean.class));
        } catch (ClassNotFoundException | LinkageError | NoSuchMethodException | IllegalAccessException
                 | IllegalArgumentException e) {
            return null;
        }
        MethodHandle handle = inUse;
        return () -> {
            try {
                return (boolean) handle.invokeExact();
            } catch (Throwable e) {
                return true;
            }
        };
    }
}
