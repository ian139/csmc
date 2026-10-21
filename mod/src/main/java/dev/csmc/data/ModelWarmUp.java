package dev.csmc.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Background model warm-up order after joining a world: the held gun, then the hotbar left to right, then the rest. */
public final class ModelWarmUp {
    private ModelWarmUp() { }

    /** Each known id once; nulls and ids absent from {@code all} are skipped. {@code all} order is kept for the rest. */
    public static List<String> order(String held, List<String> hotbar, Collection<String> all) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (held != null && all.contains(held)) ordered.add(held);
        for (String id : hotbar) if (id != null && all.contains(id)) ordered.add(id);
        ordered.addAll(all);
        return new ArrayList<>(ordered);
    }
}
