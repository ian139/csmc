package dev.csmc.balance;

import dev.csmc.balance.BalanceModel.Band;
import dev.csmc.balance.BalanceModel.Result;
import dev.csmc.balance.BalanceModel.Target;
import dev.csmc.balance.BalanceModel.Zone;
import dev.csmc.data.AmmoType;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRole;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Markdown tables for docs/balance.md, generated from the model. */
final class BalanceReport {
    private BalanceReport() {}

    /** Recipe yields per ammunition item (data/csmc/recipe). */
    static int recipeYield(WeaponData weapon) {
        AmmoType type = AmmoType.fromDefinition(weapon.ammoType());
        return type == AmmoType.SNIPER || type == AmmoType.SHOTGUN ? 8 : 16;
    }

    static String render(String title, Map<String, WeaponData> weapons) {
        StringBuilder out = new StringBuilder();
        out.append("### ").append(title).append("\n\n");
        out.append("#### Stats\n\n");
        out.append("| Weapon | Role | Dmg × pellets | HS × | Armor ratio | RPM | Mag | Reload s | Speed | Range mod | Ammo (yield) |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        weapons.forEach((id, w) -> out.append(String.format(Locale.ROOT,
                "| %s | %s | %s | %s | %s | %.0f | %d | %s | %s | %s | %s (%d) |%n",
                id, role(id), num(w.damage()) + (w.pellets() > 1 ? " × " + w.pellets() : ""),
                num(w.headshotMultiplier()), num(w.armorRatio()), w.rateOfFireRpm(), w.magazineSize(),
                num(w.reloadTimeS()), num(w.maxSpeed()), num(w.rangeModifier()), w.ammoType(), recipeYield(w))));

        out.append("\n#### Players, body: shots / TTK s by band (close ").append(num(Band.CLOSE.blocks))
                .append(", medium ").append(num(Band.MEDIUM.blocks)).append(", long ").append(num(Band.LONG.blocks))
                .append(" blocks)\n\n");
        List<Target> players = List.of(Target.PLAYER, Target.PLAYER_IRON, Target.PLAYER_DIAMOND,
                Target.PLAYER_NETHERITE, Target.PLAYER_KEVLAR);
        bandTable(out, weapons, players, Zone.BODY);
        out.append("\n#### Players, head: shots / TTK s by band\n\n");
        bandTable(out, weapons, List.of(Target.PLAYER, Target.PLAYER_NETHERITE, Target.PLAYER_KEVLAR), Zone.HEAD);

        out.append("\n#### Mobs at the role band, body: shots / TTK s; crafts/kill vs zombie\n\n");
        List<Target> mobs = List.of(Target.ZOMBIE, Target.SKELETON, Target.CREEPER, Target.ENDERMAN,
                Target.IRON_GOLEM, Target.WARDEN);
        out.append("| Weapon | Band |");
        mobs.forEach(t -> out.append(' ').append(t.label).append(" |"));
        out.append(" Crafts/kill |\n|---|---|");
        mobs.forEach(t -> out.append("---|"));
        out.append("---|\n");
        out.append("| netherite sword | melee |");
        mobs.forEach(t -> out.append(String.format(Locale.ROOT, " %d / %.2f |",
                (int) Math.ceil(t.health / BalanceModel.NETHERITE_SWORD_DAMAGE), BalanceModel.swordTtk(t.health))));
        out.append(" – |\n");
        weapons.forEach((id, w) -> {
            Band band = BalanceModel.roleBand(WeaponRole.of(id));
            out.append("| ").append(id).append(" | ").append(band.name().toLowerCase(Locale.ROOT)).append(" |");
            mobs.forEach(t -> out.append(' ').append(cell(BalanceModel.evaluate(w, t, Zone.BODY, band))).append(" |"));
            Result zombie = BalanceModel.evaluate(w, Target.ZOMBIE, Zone.BODY, band);
            out.append(String.format(Locale.ROOT, " %.2f |%n", BalanceModel.craftsPerKill(zombie, recipeYield(w))));
        });
        return out.toString();
    }

    private static void bandTable(StringBuilder out, Map<String, WeaponData> weapons, List<Target> targets, Zone zone) {
        out.append("| Weapon |");
        for (Target t : targets) {
            out.append(' ').append(t.label).append(" |");
        }
        out.append("\n|---|");
        targets.forEach(t -> out.append("---|"));
        out.append('\n');
        weapons.forEach((id, w) -> {
            out.append("| ").append(id).append(" |");
            for (Target t : targets) {
                out.append(' ');
                for (Band band : Band.values()) {
                    if (band != Band.CLOSE) {
                        out.append(" · ");
                    }
                    out.append(cell(BalanceModel.evaluate(w, t, zone, band)));
                }
                out.append(" |");
            }
            out.append('\n');
        });
    }

    static String cell(Result r) {
        return r.killable() ? String.format(Locale.ROOT, "%.1f/%.2f", r.shots(), r.ttkS()) : "–";
    }

    private static String role(String id) {
        return WeaponRole.of(id).name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String num(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "");
    }
}
