package dev.csmc.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.csmc.balance.BalanceModel.Band;
import dev.csmc.balance.BalanceModel.Result;
import dev.csmc.balance.BalanceModel.Target;
import dev.csmc.balance.BalanceModel.Zone;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Role invariants from docs/balance.md, checked on the shipped no-cache defaults and, when this machine has a
 * local CS2 export, on the export with the balance overrides (the configuration actually played).
 */
public class BalanceInvariantTest {
    private static Map<String, Map<String, WeaponData>> configurations() {
        Map<String, Map<String, WeaponData>> configs = new LinkedHashMap<>();
        configs.put("no-cache defaults", BalanceData.load(BalanceData.NO_EXPORT));
        if (Files.isRegularFile(BalanceData.EXPORT)) {
            configs.put("local CS2 export", BalanceData.load(BalanceData.EXPORT));
        }
        return configs;
    }

    private static double ttk(WeaponData weapon, Target target, Zone zone, Band band) {
        return BalanceModel.evaluate(weapon, target, zone, band).ttkS();
    }

    /** Median expected TTK of a role's weapons. */
    private static double median(Map<String, WeaponData> weapons, WeaponRole role, Target target, Band band) {
        double[] values = role.ids().stream().mapToDouble(id -> ttk(weapons.get(id), target, Zone.BODY, band))
                .sorted().toArray();
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0;
    }

    @Test
    public void everyFirearmHasARole() {
        // Mirrors CsmcMod.FIREARM_IDS (GameTest-checked); the harness evaluates exactly these.
        assertEquals(36, WeaponRole.all().size());
        for (Map<String, WeaponData> weapons : configurations().values()) {
            weapons.forEach((id, w) -> assertTrue("missing definition " + id, w != null));
        }
    }

    @Test
    public void snipersOneShotUnarmoredHeadAndBodyAtLongRange() {
        configurations().forEach((config, weapons) -> {
            for (String id : WeaponRole.SNIPER.ids()) {
                for (Target target : List.of(Target.PLAYER, Target.ZOMBIE)) {
                    for (Zone zone : Zone.values()) {
                        Result r = BalanceModel.evaluate(weapons.get(id), target, zone, Band.LONG);
                        assertEquals(config + ": " + id + " hits to kill " + target + " " + zone,
                                1.0, r.hitsNeeded(), 0.0);
                    }
                }
            }
        });
    }

    /** Only the AWP one-shots a kevlar body: SSG 08 and M14 need two body hits at every band. */
    @Test
    public void onlyTheAwpOneShotsAKevlarBody() {
        configurations().forEach((config, weapons) -> {
            for (Band band : Band.values()) {
                for (String id : List.of("ssg08", "m14")) {
                    Result r = BalanceModel.evaluate(weapons.get(id), Target.PLAYER_KEVLAR, Zone.BODY, band);
                    assertTrue(config + ": kevlar body survives " + id + " at " + band, r.hitsNeeded() >= 2.0);
                }
                Result awp = BalanceModel.evaluate(weapons.get("awp"), Target.PLAYER_KEVLAR, Zone.BODY, band);
                assertEquals(config + ": AWP one-shots kevlar at " + band, 1.0, awp.hitsNeeded(), 0.0);
            }
        });
    }

    @Test
    public void smgsOutTtkRiflesUpClose() {
        configurations().forEach((config, weapons) -> {
            for (Target target : List.of(Target.PLAYER, Target.ZOMBIE, Target.SKELETON)) {
                double smg = median(weapons, WeaponRole.SMG, target, Band.CLOSE);
                double rifle = median(weapons, WeaponRole.RIFLE, target, Band.CLOSE);
                assertTrue(config + ": SMG median " + smg + " vs rifle " + rifle + " on " + target, smg < rifle);
            }
        });
    }

    @Test
    public void shotgunsWinCloseAndLoseLong() {
        List<WeaponRole> others = Arrays.stream(WeaponRole.values())
                .filter(r -> r != WeaponRole.SHOTGUN && r != WeaponRole.ZEUS).toList();
        configurations().forEach((config, weapons) -> {
            for (Target target : List.of(Target.PLAYER, Target.PLAYER_IRON, Target.PLAYER_DIAMOND,
                    Target.PLAYER_NETHERITE, Target.PLAYER_KEVLAR)) {
                double shotgun = median(weapons, WeaponRole.SHOTGUN, target, Band.CLOSE);
                for (WeaponRole role : others) {
                    double other = median(weapons, role, target, Band.CLOSE);
                    // One-shot ties are possible against unarmored players; armored players must be strictly faster.
                    assertTrue(config + ": close shotgun " + shotgun + " vs " + role + " " + other + " on " + target,
                            target == Target.PLAYER ? shotgun <= other : shotgun < other);
                }
            }
            for (Target target : List.of(Target.PLAYER, Target.ZOMBIE)) {
                double shotgun = median(weapons, WeaponRole.SHOTGUN, target, Band.LONG);
                for (WeaponRole role : others) {
                    double other = median(weapons, role, target, Band.LONG);
                    assertTrue(config + ": long shotgun " + shotgun + " vs " + role + " " + other + " on " + target,
                            shotgun > other);
                }
            }
        });
    }

    @Test
    public void everyGunBeatsANetheriteSwordAtItsRoleRange() {
        double sword = BalanceModel.swordTtk(Target.ZOMBIE.health);
        configurations().forEach((config, weapons) -> weapons.forEach((id, w) -> {
            Band band = BalanceModel.roleBand(WeaponRole.of(id));
            double gun = ttk(w, Target.ZOMBIE, Zone.BODY, band);
            assertTrue(config + ": " + id + " zombie TTK " + gun + " vs sword " + sword, gun < sword);
        }));
    }

    @Test
    public void noGunDominatesEveryDistanceBand() {
        List<Target> targets = List.of(Target.PLAYER, Target.PLAYER_KEVLAR);
        configurations().forEach((config, weapons) -> weapons.forEach((id, w) -> {
            if (WeaponRole.of(id) == WeaponRole.ZEUS) {
                return; // single-charge utility, out of range beyond close
            }
            boolean beaten = false;
            for (Band band : Band.values()) {
                for (Target target : targets) {
                    double own = ttk(w, target, Zone.BODY, band);
                    for (Map.Entry<String, WeaponData> other : weapons.entrySet()) {
                        if (WeaponRole.of(other.getKey()) != WeaponRole.ZEUS
                                && ttk(other.getValue(), target, Zone.BODY, band) < own) {
                            beaten = true;
                        }
                    }
                }
            }
            assertTrue(config + ": " + id + " is best or tied in every band", beaten);
        }));
    }

    @Test
    public void kevlarMeaningfullyRaisesTtk() {
        configurations().forEach((config, weapons) -> {
            List<Double> ratios = new ArrayList<>();
            weapons.forEach((id, w) -> {
                if (WeaponRole.of(id) == WeaponRole.ZEUS) {
                    return;
                }
                Result bare = BalanceModel.evaluate(w, Target.PLAYER, Zone.BODY, Band.MEDIUM);
                Result kevlar = BalanceModel.evaluate(w, Target.PLAYER_KEVLAR, Zone.BODY, Band.MEDIUM);
                assertFalse(config + ": kevlar lowers " + id + " TTK", kevlar.ttkS() < bare.ttkS());
                assertTrue(config + ": kevlar must be the strongest bullet armor for " + id,
                        kevlar.hitsNeeded() >= BalanceModel.evaluate(w, Target.PLAYER_NETHERITE, Zone.BODY,
                                Band.MEDIUM).hitsNeeded());
                ratios.add(kevlar.hitsNeeded() / bare.hitsNeeded());
            });
            double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            assertTrue(config + ": mean kevlar hits-to-kill ratio " + mean, mean >= 1.25);
        });
    }

    /** Regenerates the tables in docs/balance.md when CSMC_WRITE_BALANCE_DOC=1. */
    @Test
    public void balanceDocTables() throws Exception {
        Path doc = Path.of("../docs/balance.md");
        String begin = "<!-- BEGIN GENERATED: BalanceInvariantTest -->";
        String end = "<!-- END GENERATED -->";
        StringBuilder tables = new StringBuilder();
        configurations().forEach((config, weapons) -> tables.append(BalanceReport.render(config, weapons)).append('\n'));
        if (!"1".equals(System.getenv("CSMC_WRITE_BALANCE_DOC"))) {
            assertTrue("balance tables render", tables.length() > 0);
            return;
        }
        String text = Files.readString(doc);
        int from = text.indexOf(begin);
        int to = text.indexOf(end);
        assertTrue("generated markers in " + doc, from >= 0 && to > from);
        Files.writeString(doc, text.substring(0, from + begin.length()) + "\n\n" + tables + text.substring(to));
    }
}
