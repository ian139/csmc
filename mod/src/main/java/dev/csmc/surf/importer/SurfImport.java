package dev.csmc.surf.importer;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Surf map import: {@code /csmc surf import <name> [origin]} converts {@code <server dir>/csmc/surfmaps/<name>.vmf}
 * off the server thread, places it over many ticks and records its teleport zones and spawns;
 * {@code /csmc surf spawn <name>} moves the caller to the map's first spawn; {@code /csmc surf forget <name>} drops
 * the map's zones and spawns (blocks stay). All three need permission level 2. Teleport zones are checked for every
 * player at the end of each server tick, using a per-chunk-column index so only nearby zones are tested. Log lines carry
 * the {@code [csmc-surf-import]} prefix.
 */
public final class SurfImport {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Cells written per tick at most; the time budget is cooperative, checked every 64 writes. */
    public static final int CELLS_PER_TICK = 4096;
    public static final long PLACEMENT_BUDGET_NANOS = 15_000_000L;

    private static ImportLimits limits = ImportLimits.DEFAULT;
    private static Pending pending;
    private static Running running;

    private record Pending(String name, CommandSourceStack source) {}

    private record Running(ImportJob job, ServerLevel level, CommandSourceStack source, long startNanos) {}

    private SurfImport() {}

    /** Called once from {@code dev.csmc.surf.Surf#onInitialize}. */
    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(command()));
        ServerTickEvents.END_SERVER_TICK.register(SurfImport::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            pending = null;
            running = null;
        });
    }

    /** Directory surf maps are read from: {@code <server dir>/csmc/surfmaps}. */
    public static Path mapDirectory(MinecraftServer server) {
        return server.getServerDirectory().resolve("csmc").resolve("surfmaps").toAbsolutePath().normalize();
    }

    /** Overrides the limits (tests use small ones to exercise rejection). */
    public static void setLimits(ImportLimits value) {
        limits = value;
    }

    /** True while a conversion or placement is in progress. */
    public static boolean busy() {
        return pending != null || running != null;
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("csmc").then(Commands.literal("surf").requires(source -> source.hasPermission(2))
                .then(Commands.literal("import")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> startImport(c, null))
                                .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                        .executes(c -> startImport(c, BlockPosArgument.getBlockPos(c, "origin"))))))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(SurfImport::spawn)))
                .then(Commands.literal("forget")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(SurfImport::forget))));
    }

    private static int startImport(CommandContext<CommandSourceStack> context, BlockPos origin) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        if (busy()) {
            source.sendFailure(Component.translatable("commands.csmc.surf.import.busy"));
            return 0;
        }
        Path directory = mapDirectory(source.getServer());
        Path file;
        long size;
        try {
            Files.createDirectories(directory);
            file = MapFiles.resolve(directory, name);
            size = Files.size(file);
        } catch (SurfImportException | IOException e) {
            source.sendFailure(Component.translatable("commands.csmc.surf.import.failed", name, e.getMessage()));
            return 0;
        }
        if (size > limits.maxBytes()) {
            source.sendFailure(Component.translatable("commands.csmc.surf.import.failed", name,
                    "file is larger than " + limits.maxBytes() + " bytes"));
            return 0;
        }
        BlockPos at = origin != null ? origin : BlockPos.containing(source.getPosition());
        ServerLevel level = source.getLevel();
        MinecraftServer server = source.getServer();
        ImportLimits used = limits;
        pending = new Pending(name, source);
        source.sendSuccess(() -> Component.translatable("commands.csmc.surf.import.started", name,
                file.getFileName().toString(), at.toShortString()), true);
        LOGGER.info("[csmc-surf-import] converting {} at origin {}", file, at.toShortString());
        CompletableFuture.supplyAsync(() -> {
            // One confined handle, byte limit enforced while reading (MapFiles#read).
            String text = MapFiles.read(directory, name, used.maxBytes());
            return SurfMapConverter.convert(text, used);
        }, Util.backgroundExecutor()).whenComplete((converted, error) -> server.execute(() -> {
            if (pending == null || pending.source() != source) return;
            pending = null;
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                String message = cause instanceof SurfImportException ? cause.getMessage() : cause.toString();
                LOGGER.warn("[csmc-surf-import] {} rejected: {}", name, message);
                source.sendFailure(Component.translatable("commands.csmc.surf.import.failed", name, message));
                return;
            }
            begin(name, converted, level, at, source);
        }));
        return 1;
    }

    /**
     * Checks that the converted map fits the world at {@code origin} and starts placing it. Returns the job, or null
     * (failure already reported to {@code source}) when it does not fit.
     */
    public static ImportJob begin(String name, SurfMapConverter.Converted converted, ServerLevel level,
                                  BlockPos origin, CommandSourceStack source) {
        String problem = fitProblem(name, converted, level, origin);
        if (problem != null) {
            source.sendFailure(Component.translatable("commands.csmc.surf.import.failed", name, problem));
            return null;
        }
        SurfMapConverter.Summary summary = converted.summary();
        ImportJob job = new ImportJob(name, converted, origin, new LevelImportSink(level));
        running = new Running(job, level, source, System.nanoTime());
        LOGGER.info("[csmc-surf-import] placing {}: {} cells ({} full, {} ramp)", name, job.total(),
                summary.fullCells(), summary.rampCells());
        return job;
    }

    /**
     * Why the converted map cannot be imported at {@code origin}, or null when it fits. Every translated coordinate is
     * computed in {@code long}/{@code double} before narrowing, and cells, teleport zones, destinations and landmarks,
     * and spawns are all checked against the build height and world border (also for maps without cells), plus the
     * dimension's aggregate teleport-zone budgets ({@link SurfImportData#budgetViolation}).
     */
    static String fitProblem(String name, SurfMapConverter.Converted converted, ServerLevel level, BlockPos origin) {
        long ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight();
        WorldBorder border = level.getWorldBorder();
        if (!converted.placements().isEmpty()) {
            SurfMapConverter.Summary summary = converted.summary();
            long low = summary.minY() + oy;
            long high = summary.maxY() + oy;
            if (low < minBuild || high >= maxBuild) {
                return "cells span y " + low + ".." + high + ", outside build height " + minBuild + ".."
                        + (maxBuild - 1) + " (origin y must be " + ((long) minBuild - summary.minY()) + ".."
                        + ((long) maxBuild - 1 - summary.maxY()) + ")";
            }
            long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
            for (Voxelizer.Placement p : converted.placements()) {
                minX = Math.min(minX, p.pos().getX() + ox);
                maxX = Math.max(maxX, p.pos().getX() + ox);
                minZ = Math.min(minZ, p.pos().getZ() + oz);
                maxZ = Math.max(maxZ, p.pos().getZ() + oz);
            }
            // Cell [x, x+1) lies inside the border when both of its corners do.
            if (!inside(border, minX, minZ) || !inside(border, maxX + 1, maxZ + 1)) {
                return "cells x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ + " leave the world border";
            }
        }
        for (TeleportZone zone : converted.zones()) {
            AABB b = zone.bounds().move(ox, oy, oz);
            if (!fits(level, b.minX, b.minY, b.minZ) || !inside(border, b.maxX, b.maxZ)
                    || !Double.isFinite(b.maxY) || b.maxY > maxBuild) {
                return "teleport zone " + zone.name() + " lies outside the world";
            }
            Vec3 destination = zone.destination().add(ox, oy, oz);
            if (!fits(level, destination.x, destination.y, destination.z)) {
                return "teleport destination of " + zone.name() + " lies outside the world";
            }
            if (zone.landmark() != null) {
                Vec3 landmark = zone.landmark().add(ox, oy, oz);
                if (!fits(level, landmark.x, landmark.y, landmark.z)) {
                    return "teleport landmark of " + zone.name() + " lies outside the world";
                }
            }
        }
        for (SurfMapConverter.Spawn spawn : converted.spawns()) {
            Vec3 at = spawn.position().add(ox, oy, oz);
            if (!fits(level, at.x, at.y, at.z)) return "spawn " + spawn.classname() + " lies outside the world";
        }
        List<TeleportZone> zones = new ArrayList<>(converted.zones().size());
        for (TeleportZone zone : converted.zones()) zones.add(zone.translated(ox, oy, oz));
        return SurfImportData.get(level).budgetViolation(name, new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(origin.getX(), origin.getY(), origin.getZ()), zones, List.of()));
    }

    private static boolean inside(WorldBorder border, double x, double z) {
        return x >= border.getMinX() && x <= border.getMaxX() && z >= border.getMinZ() && z <= border.getMaxZ();
    }

    /** A feet position inside the world border and the build height. */
    static boolean fits(ServerLevel level, double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && inside(level.getWorldBorder(), x, z)
                && y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight();
    }

    private static void tick(MinecraftServer server) {
        Running current = running;
        if (current != null) {
            long deadline = System.nanoTime() + PLACEMENT_BUDGET_NANOS;
            if (current.job().step(CELLS_PER_TICK, deadline)) {
                running = null;
                finish(current);
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            SurfImportData data = SurfImportData.get(level);
            if (data.zoneCount() == 0) continue;
            for (ServerPlayer player : List.copyOf(level.players())) {
                if (player.isSpectator() || !player.isAlive()) continue;
                for (TeleportZone zone : data.zonesNear(player.getBoundingBox())) {
                    if (zone.touches(player.getBoundingBox()) && teleport(player, level, zone)) break;
                }
            }
        }
    }

    private static void finish(Running done) {
        ImportJob job = done.job();
        SurfImportData.get(done.level()).put(job.name(), new SurfImportData.MapRecord(
                new SurfImportData.BlockPosRecord(job.origin().getX(), job.origin().getY(), job.origin().getZ()),
                job.worldZones(), job.worldSpawns()));
        SurfMapConverter.Summary s = job.summary();
        long millis = (System.nanoTime() - done.startNanos()) / 1_000_000L;
        LOGGER.info("[csmc-surf-import] {} done: brushes={} invisible={} full={} ramps={} changed={}/{} zones={} "
                        + "spawns={} skipped={} {} ms", job.name(), s.visibleBrushes() + s.invisibleBrushes(),
                s.invisibleBrushes(), s.fullCells(), s.rampCells(), job.solidsChanged() + job.rampsChanged(),
                job.total(), s.zones(), s.spawns(), s.skipped(), millis);
        done.source().sendSuccess(() -> Component.translatable("commands.csmc.surf.import.done", job.name(),
                s.visibleBrushes() + s.invisibleBrushes(), s.invisibleBrushes(), s.fullCells(), s.rampCells(),
                job.unchanged(), s.zones(), s.spawns(), s.skippedTotal(), millis), true);
        for (Map.Entry<String, Integer> entry : s.skipped().entrySet()) {
            done.source().sendSuccess(() -> Component.translatable("commands.csmc.surf.import.skipped",
                    entry.getValue(), entry.getKey()), false);
        }
        int approximated = s.thinBrushesDropped() + s.trimmedCells() + s.overlapsResolved() + s.sliversSkipped()
                + s.hiddenBlocksSkipped();
        if (approximated > 0) {
            done.source().sendSuccess(() -> Component.translatable("commands.csmc.surf.import.approximations",
                    s.thinBrushesDropped(), s.trimmedCells(), s.overlapsResolved(), s.sliversSkipped(),
                    s.hiddenBlocksSkipped()), false);
        }
    }

    /**
     * {@code CTriggerTeleport::Touch} (Source SDK 2013 server/triggers.cpp): position from destination (plus the
     * landmark offset), angles from the destination unless a landmark or "preserve angles" applies, velocity kept.
     * Position and velocity are kept by a relative teleport, so the client keeps its own momentum. Fall distance
     * is reset (Minecraft fall damage is distance based; the pre-teleport drop does not belong to the destination).
     */
    static boolean teleport(ServerPlayer player, ServerLevel level, TeleportZone zone) {
        Vec3 target = zone.target(player.position());
        // Landmark targets depend on the player, and the border can move after import: re-check at teleport time.
        if (!fits(level, target.x, target.y, target.z)) return false;
        EnumSet<RelativeMovement> relative = EnumSet.of(RelativeMovement.X, RelativeMovement.Y, RelativeMovement.Z);
        float yaw = zone.yaw();
        float pitch = zone.pitch();
        if (!zone.setAngles()) {
            relative.add(RelativeMovement.Y_ROT);
            relative.add(RelativeMovement.X_ROT);
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
        player.teleportTo(level, target.x, target.y, target.z, relative, yaw, pitch);
        player.resetFallDistance();
        return true;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("commands.csmc.surf.spawn.requires_player"));
            return 0;
        }
        SurfImportData.MapRecord record = SurfImportData.get(source.getLevel()).maps().get(name);
        if (record == null || record.spawns().isEmpty()) {
            source.sendFailure(Component.translatable("commands.csmc.surf.spawn.none", name));
            return 0;
        }
        SurfMapConverter.Spawn spawn = record.spawns().get(0);
        if (!fits(source.getLevel(), spawn.position().x, spawn.position().y, spawn.position().z)) {
            source.sendFailure(Component.translatable("commands.csmc.surf.import.failed", name,
                    "spawn lies outside the world"));
            return 0;
        }
        player.teleportTo(source.getLevel(), spawn.position().x, spawn.position().y, spawn.position().z,
                spawn.yaw(), spawn.pitch());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        source.sendSuccess(() -> Component.translatable("commands.csmc.surf.spawn.done", name, spawn.classname()),
                false);
        return 1;
    }

    private static int forget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        if (!SurfImportData.get(source.getLevel()).remove(name)) {
            source.sendFailure(Component.translatable("commands.csmc.surf.forget.unknown", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.csmc.surf.forget.done", name), true);
        return 1;
    }
}
