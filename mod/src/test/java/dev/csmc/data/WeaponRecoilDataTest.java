package dev.csmc.data;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeaponRecoilDataTest {
    @Test
    public void magnitudeThirtyWithoutVarianceKicksThreeQuartersDegreeUp() {
        WeaponData.Recoil recoil = new WeaponData.Recoil(0.0, 0.0, 30.0, 0.0, 223L, List.of());
        for (int shot = 1; shot <= 30; shot++) {
            WeaponData.Kick kick = recoil.kick(shot);
            assertEquals(0.75, kick.pitch(), 1.0E-12);
            assertEquals(0.0, kick.yaw(), 1.0E-12);
        }
    }

    @Test
    public void measuredDegreePatternOverridesMagnitudeWithoutScaling() {
        WeaponData.Kick first = new WeaponData.Kick(0.6, -0.4);
        WeaponData.Kick last = new WeaponData.Kick(0.3, 0.2);
        WeaponData.Recoil recoil = new WeaponData.Recoil(45.0, 70.0, 30.0, 10.0, 223L,
                List.of(first, last));
        assertSame(first, recoil.kick(1));
        assertSame(last, recoil.kick(2));
        assertSame(last, recoil.kick(30));
        assertEquals(0.6, recoil.kick(1).pitch(), 0.0);
        assertEquals(-0.4, recoil.kick(1).yaw(), 0.0);
    }

    @Test
    public void seededSprayReplaysAtResetAndAcrossIndependentDefinitions() {
        WeaponData.Recoil server = new WeaponData.Recoil(0.0, 70.0, 30.0, 3.0, 223L, List.of());
        WeaponData.Recoil client = new WeaponData.Recoil(0.0, 70.0, 30.0, 3.0, 223L, List.of());
        WeaponData.Kick[] firstSpray = new WeaponData.Kick[30];
        for (int shot = 1; shot <= firstSpray.length; shot++) {
            firstSpray[shot - 1] = server.kick(shot);
            assertEquals(firstSpray[shot - 1], client.kick(shot));
        }
        for (int shot = 1; shot <= firstSpray.length; shot++) {
            assertEquals("reset restarts the same spray at shot " + shot, firstSpray[shot - 1], server.kick(shot));
        }
    }
}
