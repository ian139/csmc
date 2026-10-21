package dev.csmc.skin.gameplay;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.csmc.item.SkinnableWeapon;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinNames;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Operator-only attachment creation (the held firearm is never modified) and the {@code /csmc skins} picker opener. */
public final class SkinCommands {
    private SkinCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(command(SkinCatalog::kits)));
    }

    /** The production {@code /csmc skin} tree, permission gate included, over explicit kit metadata. */
    public static LiteralArgumentBuilder<CommandSourceStack> command(List<SkinCatalog.Kit> kits) {
        List<SkinCatalog.Kit> fixed = List.copyOf(kits);
        return command(() -> fixed);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> command(Supplier<List<SkinCatalog.Kit>> kits) {
        return Commands.literal("csmc")
                .then(Commands.literal("skins").executes(SkinCommands::openPicker))
                .then(Commands.literal("skin")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("name_or_id", StringArgumentType.string())
                                .suggests((context, builder) -> suggestKits(context, builder, kits.get()))
                                .executes(context -> give(context, kits.get(), false, false))
                                .then(Commands.argument("wear", FloatArgumentType.floatArg(0, 1))
                                        .executes(context -> give(context, kits.get(), true, false))
                                        .then(Commands.argument("seed", IntegerArgumentType.integer(0, 1000))
                                                .executes(context -> give(context, kits.get(), true, true))))));
    }

    /** Anyone may open the picker; applying from it is operator-only and checked server-side. */
    private static int openPicker(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("commands.csmc.skin.requires_player"));
            return 0;
        }
        if (!(player.getMainHandItem().getItem() instanceof SkinnableWeapon)) {
            source.sendFailure(Component.translatable("commands.csmc.skin.requires_gun"));
            return 0;
        }
        if (!ServerPlayNetworking.canSend(player, SkinPickerPayload.TYPE)) {
            source.sendFailure(Component.translatable("commands.csmc.skins.client_missing"));
            return 0;
        }
        ServerPlayNetworking.send(player, SkinPickerPayload.OPEN);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> context, List<SkinCatalog.Kit> kits,
                            boolean hasWear, boolean hasSeed) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("commands.csmc.skin.requires_player"));
            return 0;
        }
        if (!(player.getMainHandItem().getItem() instanceof SkinnableWeapon gun)) {
            source.sendFailure(Component.translatable("commands.csmc.skin.requires_gun"));
            return 0;
        }
        String weapon = gun.weaponId().getPath();
        String query = StringArgumentType.getString(context, "name_or_id");

        int paintKit;
        SkinCatalog.Kit kit;
        if (isInteger(query)) {
            paintKit = parsePaintKit(query);
            if (paintKit < 0) {
                source.sendFailure(Component.translatable("commands.csmc.skin.invalid_id", query));
                return 0;
            }
            // Unknown IDs carry no metadata and are granted as-is; known IDs are validated below.
            kit = byId(kits, paintKit);
        } else {
            if (kits.isEmpty()) {
                source.sendFailure(Component.translatable("commands.csmc.skin.catalog_unavailable"));
                return 0;
            }
            kit = byName(kits, query, weapon);
            if (kit == null) {
                source.sendFailure(Component.translatable("commands.csmc.skin.unknown", query));
                return 0;
            }
            paintKit = kit.paintKit();
        }
        String label = kit == null ? Integer.toString(paintKit) : SkinNames.displayName(kit);
        if (kit != null && !kit.supports(weapon)) {
            source.sendFailure(Component.translatable("commands.csmc.skin.unsupported", label, weapon));
            return 0;
        }

        // Explicit debug wear spans [0, 1], even outside the kit's normal wear range.
        // Without metadata there is no wear range, so the default is the factory-new floor.
        float wear = hasWear ? FloatArgumentType.getFloat(context, "wear") : kit == null ? 0 : kit.wearMin();
        if (!Float.isFinite(wear) || wear < 0 || wear > 1) {
            source.sendFailure(Component.translatable("commands.csmc.skin.invalid_wear"));
            return 0;
        }
        int seed = hasSeed ? IntegerArgumentType.getInteger(context, "seed") : player.getRandom().nextInt(1001);
        if (seed < 0 || seed > 1000) {
            source.sendFailure(Component.translatable("commands.csmc.skin.invalid_seed"));
            return 0;
        }

        player.getInventory().placeItemBackInInventory(
                SkinGameplay.skin(weapon, new SkinData(paintKit, wear, seed)));
        source.sendSuccess(() -> Component.translatable("commands.csmc.skin.success",
                label, weapon, Float.toString(wear), seed), false);
        return 1;
    }

    /** Optional sign followed by digits; numeric names are always treated as paint-kit IDs. */
    private static boolean isInteger(String query) {
        int start = !query.isEmpty() && (query.charAt(0) == '+' || query.charAt(0) == '-') ? 1 : 0;
        if (start == query.length()) return false;
        for (int i = start; i < query.length(); i++) {
            char character = query.charAt(i);
            if (character < '0' || character > '9') return false;
        }
        return true;
    }

    /** Returns -1 for negative or out-of-range IDs. */
    private static int parsePaintKit(String query) {
        try {
            int id = Integer.parseInt(query);
            return id < 0 ? -1 : id;
        } catch (NumberFormatException overflow) {
            return -1;
        }
    }

    private static SkinCatalog.Kit byId(List<SkinCatalog.Kit> kits, int id) {
        for (SkinCatalog.Kit kit : kits) {
            if (kit.paintKit() == id) return kit;
        }
        return null;
    }

    /**
     * Internal names are unique and match regardless of the held weapon, so support is validated afterwards.
     * Display names are shared across weapons (a knife and a Glock can both be "Gamma Doppler (Phase 3)"), so they
     * only match kits that support the held weapon.
     */
    private static SkinCatalog.Kit byName(List<SkinCatalog.Kit> kits, String query, String weapon) {
        for (SkinCatalog.Kit kit : kits) {
            if (kit.name().equalsIgnoreCase(query)) return kit;
        }
        // Phase-aware names ("Doppler (Phase 2)") pick that exact kit; the shared base name keeps its first-match meaning.
        for (SkinCatalog.Kit kit : kits) {
            if (kit.supports(weapon) && SkinNames.displayName(kit).equalsIgnoreCase(query)) return kit;
        }
        for (SkinCatalog.Kit kit : kits) {
            if (kit.supports(weapon) && kit.displayName().equalsIgnoreCase(query)) return kit;
        }
        return null;
    }

    private static CompletableFuture<Suggestions> suggestKits(CommandContext<CommandSourceStack> context,
                                                              SuggestionsBuilder builder, List<SkinCatalog.Kit> kits) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || !(player.getMainHandItem().getItem() instanceof SkinnableWeapon gun)) {
            return builder.buildFuture();
        }
        String weapon = gun.weaponId().getPath();
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (SkinCatalog.Kit kit : kits) {
            if (!kit.supports(weapon)) continue;
            String suggestion = suggestionName(kit);
            if (SharedSuggestionProvider.matchesSubStr(remaining, suggestion.toLowerCase(Locale.ROOT))) {
                builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
    }

    private static String suggestionName(SkinCatalog.Kit kit) {
        String name = kit.name();
        for (int i = 0; i < name.length(); i++) {
            if (!StringReader.isAllowedInUnquotedString(name.charAt(i))) return Integer.toString(kit.paintKit());
        }
        // Numeric names would be interpreted as paint-kit IDs instead of names.
        return name.isEmpty() || isInteger(name) ? Integer.toString(kit.paintKit()) : name;
    }
}

