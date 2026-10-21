package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Per-dimension record of imported surf maps: their teleport zones (checked every server tick) and spawn points, in
 * world coordinates. Stored as {@code data/csmc_surf_maps.dat} in the dimension's save folder.
 */
public final class SurfImportData extends SavedData {
    static final String FILE_ID = "csmc_surf_maps";

    /** One imported map in world coordinates. */
    public record MapRecord(BlockPosRecord origin, List<TeleportZone> zones, List<SurfMapConverter.Spawn> spawns) {
        public MapRecord {
            zones = List.copyOf(zones);
            spawns = List.copyOf(spawns);
        }
    }

    /** Integer origin used for the import (kept so a summary can name it). */
    public record BlockPosRecord(int x, int y, int z) {}

    /** Aggregate per-dimension budgets over every imported map (checked before an import places anything). */
    public static final int MAX_ZONES = 4096;
    public static final int MAX_ZONE_VOLUMES = 16384;
    /** Total (zone, chunk column) index entries: bounds the memory and update cost of the spatial index. */
    public static final long MAX_INDEX_ENTRIES = 262_144;

    private final Map<String, MapRecord> maps = new TreeMap<>();
    /** Zones bucketed by every chunk column their bounds overlap, so a tick tests only zones near each player. */
    private Map<Long, List<TeleportZone>> index = Map.of();
    private int zoneCount;
    private Map<TeleportZone, Integer> zoneOrder = Map.of();
    /**
     * The registered factory. The command-storage data-fix type is used only because the loader requires a
     * non-null type; this data has no vanilla schema, so data fixers leave it unchanged.
     */
    private static final SavedData.Factory<SurfImportData> FACTORY = new SavedData.Factory<>(SurfImportData::new,
            SurfImportData::load, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    public static SurfImportData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public Map<String, MapRecord> maps() {
        return Collections.unmodifiableMap(maps);
    }

    /** Number of zones over every map. */
    public int zoneCount() {
        return zoneCount;
    }

    /**
     * Zones whose bounds share a chunk column with {@code box}, in map-name then file order, each once. Cost is
     * proportional to the zones near {@code box}, not to every imported zone.
     */
    public List<TeleportZone> zonesNear(AABB box) {
        if (index.isEmpty()) return List.of();
        int x0 = SectionPos.blockToSectionCoord(Mth.floor(box.minX)), x1 = SectionPos.blockToSectionCoord(Mth.floor(box.maxX));
        int z0 = SectionPos.blockToSectionCoord(Mth.floor(box.minZ)), z1 = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ));
        if (x0 == x1 && z0 == z1) return index.getOrDefault(ChunkPos.asLong(x0, z0), List.of());
        Set<TeleportZone> out = new LinkedHashSet<>();
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) out.addAll(index.getOrDefault(ChunkPos.asLong(cx, cz), List.of()));
        }
        List<TeleportZone> ordered = new ArrayList<>(out);
        ordered.sort(java.util.Comparator.comparingInt(zoneOrder::get));
        return List.copyOf(ordered);
    }

    /**
     * Why storing {@code record} under {@code name} (replacing any map of that name) would exceed the aggregate
     * budgets, or null when it fits.
     */
    public String budgetViolation(String name, MapRecord record) {
        long zones = 0;
        long volumes = 0;
        long entries = 0;
        for (Map.Entry<String, MapRecord> entry : maps.entrySet()) {
            if (entry.getKey().equals(name)) continue;
            for (TeleportZone zone : entry.getValue().zones()) {
                zones++;
                volumes += zone.volumes().size();
                entries = Math.min(MAX_INDEX_ENTRIES + 1, entries + columns(zone.bounds()));
            }
        }
        for (TeleportZone zone : record.zones()) {
            zones++;
            volumes += zone.volumes().size();
            entries = Math.min(MAX_INDEX_ENTRIES + 1, entries + columns(zone.bounds()));
        }
        if (zones > MAX_ZONES) return "dimension would hold " + zones + " teleport zones (limit " + MAX_ZONES + ")";
        if (volumes > MAX_ZONE_VOLUMES) {
            return "dimension would hold " + volumes + " teleport volumes (limit " + MAX_ZONE_VOLUMES + ")";
        }
        if (entries > MAX_INDEX_ENTRIES) {
            return "teleport zones would cover more than " + MAX_INDEX_ENTRIES + " chunk columns";
        }
        return null;
    }

    private static long columns(AABB box) {
        long dx = (long) SectionPos.blockToSectionCoord(Mth.floor(box.maxX))
                - SectionPos.blockToSectionCoord(Mth.floor(box.minX)) + 1;
        long dz = (long) SectionPos.blockToSectionCoord(Mth.floor(box.maxZ))
                - SectionPos.blockToSectionCoord(Mth.floor(box.minZ)) + 1;
        return dx * dz;
    }

    public void put(String name, MapRecord record) {
        String problem = budgetViolation(name, record);
        if (problem != null) throw new SurfImportException(problem);
        maps.put(name, record);
        rebuild();
        setDirty();
    }

    public boolean remove(String name) {
        boolean removed = maps.remove(name) != null;
        if (removed) {
            rebuild();
            setDirty();
        }
        return removed;
    }

    private void rebuild() {
        Map<Long, List<TeleportZone>> buckets = new HashMap<>();
        Map<TeleportZone, Integer> order = new HashMap<>();
        int count = 0;
        long entries = 0;
        for (MapRecord record : maps.values()) {
            for (TeleportZone zone : record.zones()) {
                order.putIfAbsent(zone, count);
                count++;
                AABB b = zone.bounds();
                entries += columns(b);
                if (entries > MAX_INDEX_ENTRIES) {
                    throw new IllegalStateException("surf map teleport zones exceed " + MAX_INDEX_ENTRIES
                            + " chunk columns");
                }
                int x0 = SectionPos.blockToSectionCoord(Mth.floor(b.minX)), x1 = SectionPos.blockToSectionCoord(Mth.floor(b.maxX));
                int z0 = SectionPos.blockToSectionCoord(Mth.floor(b.minZ)), z1 = SectionPos.blockToSectionCoord(Mth.floor(b.maxZ));
                for (int cx = x0; cx <= x1; cx++) {
                    for (int cz = z0; cz <= z1; cz++) {
                        buckets.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> new ArrayList<>()).add(zone);
                    }
                }
            }
        }
        Map<Long, List<TeleportZone>> frozen = new HashMap<>(buckets.size());
        for (Map.Entry<Long, List<TeleportZone>> e : buckets.entrySet()) frozen.put(e.getKey(), List.copyOf(e.getValue()));
        index = frozen;
        zoneOrder = order;
        zoneCount = count;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag mapsTag = new CompoundTag();
        for (Map.Entry<String, MapRecord> entry : maps.entrySet()) {
            MapRecord record = entry.getValue();
            CompoundTag mapTag = new CompoundTag();
            mapTag.putIntArray("origin", new int[] {record.origin().x(), record.origin().y(), record.origin().z()});
            ListTag zones = new ListTag();
            for (TeleportZone zone : record.zones()) zones.add(saveZone(zone));
            mapTag.put("zones", zones);
            ListTag spawns = new ListTag();
            for (SurfMapConverter.Spawn spawn : record.spawns()) {
                CompoundTag s = new CompoundTag();
                s.putString("class", spawn.classname());
                s.put("pos", doubles(spawn.position().x, spawn.position().y, spawn.position().z));
                s.putFloat("yaw", spawn.yaw());
                s.putFloat("pitch", spawn.pitch());
                spawns.add(s);
            }
            mapTag.put("spawns", spawns);
            mapsTag.put(entry.getKey(), mapTag);
        }
        tag.put("maps", mapsTag);
        return tag;
    }

    private static CompoundTag saveZone(TeleportZone zone) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", zone.name());
        ListTag volumes = new ListTag();
        for (ConvexBrush volume : zone.volumes()) {
            CompoundTag v = new CompoundTag();
            ListTag planes = new ListTag();
            for (BrushPlane p : volume.planes()) planes.add(doubles(p.nx(), p.ny(), p.nz(), p.distance()));
            v.put("planes", planes);
            AABB b = volume.bounds();
            v.put("bounds", doubles(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
            volumes.add(v);
        }
        tag.put("volumes", volumes);
        tag.put("destination", doubles(zone.destination().x, zone.destination().y, zone.destination().z));
        tag.putFloat("yaw", zone.yaw());
        tag.putFloat("pitch", zone.pitch());
        tag.putBoolean("setAngles", zone.setAngles());
        if (zone.landmark() != null) {
            tag.put("landmark", doubles(zone.landmark().x, zone.landmark().y, zone.landmark().z));
        }
        return tag;
    }

    static SurfImportData load(CompoundTag tag, HolderLookup.Provider provider) {
        SurfImportData data = new SurfImportData();
        CompoundTag mapsTag = tag.getCompound("maps");
        int loadedZones = 0;
        int loadedVolumes = 0;
        for (String name : mapsTag.getAllKeys()) {
            CompoundTag mapTag = mapsTag.getCompound(name);
            int[] o = mapTag.getIntArray("origin");
            BlockPosRecord origin = o.length == 3 ? new BlockPosRecord(o[0], o[1], o[2]) : new BlockPosRecord(0, 0, 0);
            ListTag zoneTags = mapTag.getList("zones", Tag.TAG_COMPOUND);
            if (zoneTags.size() > MAX_ZONES - loadedZones) {
                throw new SurfImportException("saved surf maps exceed " + MAX_ZONES + " teleport zones");
            }
            List<TeleportZone> zones = new ArrayList<>(zoneTags.size());
            for (Tag z : zoneTags) {
                CompoundTag zoneTag = (CompoundTag) z;
                int count = zoneTag.getList("volumes", Tag.TAG_COMPOUND).size();
                if (count > MAX_ZONE_VOLUMES - loadedVolumes) {
                    throw new SurfImportException("saved surf maps exceed " + MAX_ZONE_VOLUMES + " teleport volumes");
                }
                loadedVolumes += count;
                zones.add(loadZone(zoneTag));
            }
            loadedZones += zoneTags.size();
            List<SurfMapConverter.Spawn> spawns = new ArrayList<>();
            for (Tag s : mapTag.getList("spawns", Tag.TAG_COMPOUND)) {
                CompoundTag st = (CompoundTag) s;
                double[] p = read(st.getList("pos", Tag.TAG_DOUBLE), 3);
                spawns.add(new SurfMapConverter.Spawn(st.getString("class"), new Vec3(p[0], p[1], p[2]),
                        st.getFloat("yaw"), st.getFloat("pitch")));
            }
            MapRecord record = new MapRecord(origin, zones, spawns);
            String problem = data.budgetViolation(name, record);
            if (problem != null) throw new SurfImportException("saved surf maps exceed budget: " + problem);
            data.maps.put(name, record);
        }
        data.rebuild();
        return data;
    }

    private static TeleportZone loadZone(CompoundTag tag) {
        List<ConvexBrush> volumes = new ArrayList<>();
        for (Tag v : tag.getList("volumes", Tag.TAG_COMPOUND)) {
            CompoundTag vt = (CompoundTag) v;
            List<BrushPlane> planes = new ArrayList<>();
            for (Tag p : vt.getList("planes", Tag.TAG_LIST)) {
                double[] d = read((ListTag) p, 4);
                planes.add(new BrushPlane(d[0], d[1], d[2], d[3]));
            }
            double[] b = read(vt.getList("bounds", Tag.TAG_DOUBLE), 6);
            volumes.add(new ConvexBrush(planes, new AABB(b[0], b[1], b[2], b[3], b[4], b[5])));
        }
        double[] d = read(tag.getList("destination", Tag.TAG_DOUBLE), 3);
        Vec3 landmark = null;
        if (tag.contains("landmark", Tag.TAG_LIST)) {
            double[] l = read(tag.getList("landmark", Tag.TAG_DOUBLE), 3);
            landmark = new Vec3(l[0], l[1], l[2]);
        }
        return new TeleportZone(tag.getString("name"), volumes, new Vec3(d[0], d[1], d[2]), tag.getFloat("yaw"),
                tag.getFloat("pitch"), tag.getBoolean("setAngles"), landmark);
    }

    private static ListTag doubles(double... values) {
        ListTag list = new ListTag();
        for (double value : values) list.add(DoubleTag.valueOf(value));
        return list;
    }

    private static double[] read(ListTag list, int size) {
        if (list.size() != size) throw new IllegalStateException("corrupt surf map data: expected " + size + " values");
        double[] out = new double[size];
        for (int i = 0; i < size; i++) out[i] = list.getDouble(i);
        return out;
    }
}
