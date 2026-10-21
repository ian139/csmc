package dev.csmc.surf.importer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Decides what an imported brush becomes. Sources: entity solidity from Source SDK 2013 {@code server/bmodels.cpp}
 * (func_wall SOLID_BSP, func_illusionary SOLID_NONE) and {@code server/modelentities.cpp} (func_brush
 * {@code Solidity} 0 toggle / 1 never / 2 always, {@code StartDisabled} turns a toggle brush non-solid); func_detail
 * is merged into the world by vbsp ({@code utils/vbsp/map.cpp}); tool-texture behaviour from the Valve Developer
 * Community "Tool textures (Source)" article and vbsp's handling of hint/skip faces (no contents) and clip contents
 * (player-solid, never drawn).
 */
public final class BrushClassifier {
    private BrushClassifier() {}

    /** What a brush turns into. */
    public enum Kind {
        /** Player-solid and drawn: placed with a palette block. */
        VISIBLE,
        /** Player-solid but never drawn (nodraw-only, clip, playerclip): placed as {@code minecraft:barrier}. */
        INVISIBLE,
        /** Not imported as geometry. */
        IGNORED
    }

    /** Classification of one brush; {@code reason} names the rule for skipped brushes, {@code material} the block. */
    public record Result(Kind kind, String material, String reason) {}

    /** How a tool material behaves for a player. */
    enum Tool { NONE, NODRAW, CLIP, TRIGGER, SKYBOX, HINT_SKIP, ORIGIN, NOT_PLAYER_SOLID, UNKNOWN_TOOL }

    /**
     * Block palette for drawn brushes: the 16 concrete colours. A material always maps to the same block
     * (CRC-32 of its lower-case name modulo 16) so re-imports are identical; textures themselves are never imported.
     */
    public static final List<String> PALETTE = List.of(
            "minecraft:white_concrete", "minecraft:light_gray_concrete", "minecraft:gray_concrete",
            "minecraft:black_concrete", "minecraft:brown_concrete", "minecraft:red_concrete",
            "minecraft:orange_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete",
            "minecraft:green_concrete", "minecraft:cyan_concrete", "minecraft:light_blue_concrete",
            "minecraft:blue_concrete", "minecraft:purple_concrete", "minecraft:magenta_concrete",
            "minecraft:pink_concrete");

    public static final String INVISIBLE_BLOCK = "minecraft:barrier";

    static Tool tool(String material) {
        if (!material.startsWith("tools/")) return Tool.NONE;
        return switch (material.substring("tools/".length())) {
            case "toolsnodraw", "toolsinvisible" -> Tool.NODRAW;
            case "toolsclip", "toolsplayerclip" -> Tool.CLIP;
            case "toolstrigger" -> Tool.TRIGGER;
            case "toolsskybox", "toolsskybox2d" -> Tool.SKYBOX;
            case "toolshint", "toolsskip" -> Tool.HINT_SKIP;
            case "toolsorigin" -> Tool.ORIGIN;
            case "toolsnpcclip", "toolsblockbullets", "toolsblock_los", "toolsblocklight", "toolsareaportal",
                 "toolsoccluder", "toolsfog", "toolsdotted", "toolsblack_noportal" -> Tool.NOT_PLAYER_SOLID;
            default -> Tool.UNKNOWN_TOOL;
        };
    }

    /** Palette block for a drawn material. */
    public static String paletteBlock(String material) {
        CRC32 crc = new CRC32();
        crc.update(material.getBytes(StandardCharsets.UTF_8));
        return PALETTE.get((int) (crc.getValue() % PALETTE.size()));
    }

    /** Why an entity's brushes are or are not geometry; null means "import as solid". */
    static String entityRejection(VmfMap.Entity entity) {
        String cls = entity.classname();
        switch (cls) {
            case "worldspawn", "func_detail", "func_wall" -> {
                return null;
            }
            case "func_brush" -> {
                int solidity = entity.intKey("solidity", 0);
                if (solidity == 1) return "func_brush Solidity=Never";
                if (solidity != 2 && entity.intKey("startdisabled", 0) != 0) return "func_brush StartDisabled";
                return null;
            }
            case "func_illusionary" -> {
                return "func_illusionary (non-solid)";
            }
            default -> {
                return cls.isEmpty() ? "entity without classname" : cls + " (unsupported brush entity)";
            }
        }
    }

    /** Classifies one brush of an entity accepted by {@link #entityRejection}. */
    static Result classify(VmfMap.Solid solid) {
        boolean anyVisible = false;
        boolean unknownTool = false;
        Map<String, Integer> visibleCounts = new TreeMap<>();
        for (VmfMap.Side side : solid.sides()) {
            if (side.displacement()) return new Result(Kind.IGNORED, null, "displacement brush");
            Tool tool = tool(side.material());
            switch (tool) {
                case HINT_SKIP -> {
                    return new Result(Kind.IGNORED, null, "hint/skip brush");
                }
                case ORIGIN -> {
                    return new Result(Kind.IGNORED, null, "origin brush");
                }
                case TRIGGER -> {
                    return new Result(Kind.IGNORED, null, "trigger-textured brush");
                }
                case SKYBOX -> {
                    return new Result(Kind.IGNORED, null, "skybox brush");
                }
                case NOT_PLAYER_SOLID -> {
                    return new Result(Kind.IGNORED, null, "non-player-solid tool brush");
                }
                case UNKNOWN_TOOL -> unknownTool = true;
                case NONE -> {
                    anyVisible = true;
                    visibleCounts.merge(side.material(), 1, Integer::sum);
                }
                case NODRAW, CLIP -> { }
            }
        }
        if (!anyVisible) {
            return new Result(Kind.INVISIBLE, INVISIBLE_BLOCK, unknownTool ? "unknown tool texture" : null);
        }
        String dominant = null;
        int best = -1;
        for (Map.Entry<String, Integer> entry : visibleCounts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                dominant = entry.getKey();
            }
        }
        return new Result(Kind.VISIBLE, paletteBlock(dominant), null);
    }
}
