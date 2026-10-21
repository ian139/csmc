package dev.csmc.surf.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * The subset of a VMF file the importer reads, still in Source units and axes (x east, y north, z up). Structure per
 * the Valve Developer Community "VMF (Valve Map Format)" article and the loader in Source SDK 2013
 * {@code utils/vbsp/map.cpp}: one {@code world} block (the worldspawn entity) and {@code entity} blocks, each holding
 * {@code solid} blocks whose {@code side} blocks carry a {@code plane} of three points and a {@code material}.
 * {@code hidden} blocks (objects hidden in Hammer) are skipped because vbsp registers no handler for them and never
 * compiles their content.
 */
public record VmfMap(List<Entity> entities, int hiddenBlocksSkipped) {
    public VmfMap {
        entities = List.copyOf(entities);
    }

    /** A point (Source units). */
    public record Point(double x, double y, double z) {
        public Vec3 vec() {
            return new Vec3(x, y, z);
        }
    }

    /** One brush face: the plane through {@code p0, p1, p2} (vbsp winding: outward normal (p0-p1)x(p2-p1)). */
    public record Side(Point p0, Point p1, Point p2, String material, boolean displacement) {}

    public record Solid(int id, List<Side> sides) {
        public Solid {
            sides = List.copyOf(sides);
        }
    }

    /**
     * An entity; {@code index} is its position in the file (worldspawn is 0). Keys are lower-cased because Source
     * looks them up case-insensitively; duplicate keys keep the first value.
     */
    public record Entity(int index, String classname, Map<String, String> keys, List<Solid> solids) {
        public Entity {
            keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
            solids = List.copyOf(solids);
        }

        public String key(String name) {
            return keys.get(name.toLowerCase(Locale.ROOT));
        }

        /** The {@code origin} key, or null when absent. */
        public Point origin() {
            String value = key("origin");
            return value == null ? null : parseVector(value, "origin of " + classname);
        }

        public int intKey(String name, int fallback) {
            String value = key(name);
            if (value == null || value.isBlank()) return fallback;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new SurfImportException(classname + " key " + name + " is not an integer: " + value);
            }
        }
    }

    public static VmfMap read(KeyValues.Node root, ImportLimits limits) {
        List<Entity> entities = new ArrayList<>();
        int hidden = 0;
        int solids = 0;
        boolean sawWorld = false;
        for (KeyValues.Node block : root.children()) {
            String name = block.lowerName();
            if (name.equals("hidden")) {
                hidden++;
                continue;
            }
            if (!name.equals("world") && !name.equals("entity")) continue;
            if (name.equals("world")) {
                if (sawWorld) throw new SurfImportException("more than one world block");
                sawWorld = true;
            }
            if (entities.size() >= limits.maxEntities()) {
                throw new SurfImportException("more than " + limits.maxEntities() + " entities");
            }
            Map<String, String> keys = new LinkedHashMap<>();
            for (KeyValues.Pair pair : block.pairs()) keys.putIfAbsent(pair.key().toLowerCase(Locale.ROOT), pair.value());
            String classname = keys.getOrDefault("classname", name.equals("world") ? "worldspawn" : "");
            if (name.equals("world")) classname = "worldspawn";
            List<Solid> entitySolids = new ArrayList<>();
            for (KeyValues.Node child : block.children()) {
                String childName = child.lowerName();
                if (childName.equals("hidden")) {
                    hidden++;
                } else if (childName.equals("solid")) {
                    if (++solids > limits.maxSolids()) {
                        throw new SurfImportException("more than " + limits.maxSolids() + " solids");
                    }
                    entitySolids.add(solid(child, limits));
                }
            }
            Entity entity = new Entity(0, classname.toLowerCase(Locale.ROOT), keys, entitySolids);
            if (name.equals("world")) {
                entities.add(0, entity);
            } else {
                entities.add(entity);
            }
        }
        if (!sawWorld) throw new SurfImportException("no world block: not a VMF file");
        List<Entity> indexed = new ArrayList<>(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            indexed.add(new Entity(i, e.classname(), e.keys(), e.solids()));
        }
        return new VmfMap(indexed, hidden);
    }

    private static Solid solid(KeyValues.Node node, ImportLimits limits) {
        int id = 0;
        String idText = node.get("id");
        if (idText != null) {
            try {
                id = Integer.parseInt(idText.trim());
            } catch (NumberFormatException e) {
                throw new SurfImportException("solid id is not an integer: " + idText);
            }
        }
        List<Side> sides = new ArrayList<>();
        for (KeyValues.Node child : node.children("side")) {
            if (sides.size() >= limits.maxSidesPerSolid()) {
                throw new SurfImportException("solid " + id + " has more than " + limits.maxSidesPerSolid() + " sides");
            }
            String plane = child.get("plane");
            if (plane == null) throw new SurfImportException("solid " + id + " has a side without a plane");
            Point[] points = parsePlane(plane, limits, "solid " + id);
            String material = child.get("material");
            sides.add(new Side(points[0], points[1], points[2],
                    material == null ? "" : material.toLowerCase(Locale.ROOT).replace('\\', '/'),
                    child.child("dispinfo") != null));
        }
        if (sides.size() < 4) throw new SurfImportException("solid " + id + " has fewer than 4 sides");
        return new Solid(id, sides);
    }

    /** {@code "(x y z) (x y z) (x y z)"} as written by Hammer and BSPSource. */
    static Point[] parsePlane(String text, ImportLimits limits, String where) {
        String cleaned = text.replace('(', ' ').replace(')', ' ').trim();
        String[] parts = cleaned.isEmpty() ? new String[0] : cleaned.split("\\s+");
        if (parts.length != 9) throw new SurfImportException(where + ": plane needs 3 points: " + text);
        double[] v = new double[9];
        for (int i = 0; i < 9; i++) {
            v[i] = number(parts[i], where);
            if (Math.abs(v[i]) > 2 * limits.maxCoordinate()) {
                throw new SurfImportException(where + ": plane point outside +/-" + 2 * limits.maxCoordinate());
            }
        }
        return new Point[] {new Point(v[0], v[1], v[2]), new Point(v[3], v[4], v[5]), new Point(v[6], v[7], v[8])};
    }

    static Point parseVector(String text, String where) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) throw new SurfImportException(where + " needs 3 numbers: " + text);
        return new Point(number(parts[0], where), number(parts[1], where), number(parts[2], where));
    }

    private static double number(String text, String where) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new SurfImportException(where + ": not a number: " + text);
        }
        if (!Double.isFinite(value)) throw new SurfImportException(where + ": non-finite number " + text);
        return value;
    }
}
