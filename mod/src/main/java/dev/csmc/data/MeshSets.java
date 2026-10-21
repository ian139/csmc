package dev.csmc.data;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CS2 exports name each mesh node {@code <model>.vmdl_c.<group>} ({@code body_hd}, {@code body_legacy},
 * accessory groups such as {@code eholster}). Groups are alternative or optional bodies: rendering exactly one
 * avoids overlapping duplicates. Nodes without the marker are not grouped and always render.
 */
public final class MeshSets {
    public static final List<String> DEFAULT_PREFERENCE = List.of("body_hd", "body_legacy");
    private static final String MARKER = ".vmdl_c.";

    private MeshSets() {}

    /** Group suffix of a CS2 mesh node name, or null for ungrouped names. */
    public static String group(String name) {
        if (name == null) return null;
        int at = name.lastIndexOf(MARKER);
        if (at < 0 || at + MARKER.length() == name.length()) return null;
        return name.substring(at + MARKER.length());
    }

    /** First preferred group present, else the first group in node order, else null (nothing grouped). */
    public static String choose(Collection<String> names, List<String> preference) {
        Set<String> groups = new LinkedHashSet<>();
        for (String name : names) {
            String group = group(name);
            if (group != null) groups.add(group);
        }
        if (groups.isEmpty()) return null;
        for (String preferred : preference) if (groups.contains(preferred)) return preferred;
        return groups.iterator().next();
    }

    /** True when {@code name} renders under the chosen group. */
    public static boolean selected(String name, String chosen) {
        String group = group(name);
        return chosen == null || group == null || group.equals(chosen);
    }
}
