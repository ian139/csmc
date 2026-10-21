package dev.csmc.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import dev.csmc.network.AmmoHudPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

public class AmmoHudStateTest {
    private static final ResourceLocation AK = ResourceLocation.fromNamespaceAndPath("csmc", "ak47");
    private static final ResourceLocation GLOCK = ResourceLocation.fromNamespaceAndPath("csmc", "glock");


    @Test
    public void unboundGunShowsItsSyncedMagazineAndNoReserve() {
        AmmoHudState state = new AmmoHudState();
        assertEquals(17, state.magazine(0, AK, 17));
        assertEquals(-1, state.reserve(0, AK));
        state.accept(0, AK, 13, 90, false, AmmoHudPayload.NOTICE_NONE, 0);
        assertEquals(13, state.magazine(0, AK, 17));
        assertEquals(90, state.reserve(0, AK));
        assertEquals(20, state.magazine(1, GLOCK, 20));
        assertEquals(-1, state.reserve(1, GLOCK));
        assertEquals(-1, state.reserve(0, GLOCK));
    }

    @Test
    public void reloadingShowsOnlyForTheReloadingGun() {
        AmmoHudState state = new AmmoHudState();
        state.accept(0, AK, 13, 90, true, AmmoHudPayload.NOTICE_NONE, 0);
        assertEquals(AmmoHudState.LINE_RELOADING, state.line(0, AK, 0));
        assertEquals(AmmoHudState.LINE_NONE, state.line(1, GLOCK, 0));
    }

    @Test
    public void noticesAreBoundToTheirSlotAndWeaponAndExpire() {
        AmmoHudState state = new AmmoHudState();
        state.accept(0, AK, 0, 0, false, AmmoHudPayload.NOTICE_NO_AMMO, 1000);
        assertEquals(AmmoHudState.LINE_NO_AMMO, state.line(0, AK, 1000));
        assertEquals(AmmoHudState.LINE_NONE, state.line(1, GLOCK, 1000));
        assertEquals(AmmoHudState.LINE_NONE, state.line(0, GLOCK, 1000));
        // A later state update for the same gun keeps the notice until it expires.
        state.accept(0, AK, 0, 0, false, AmmoHudPayload.NOTICE_NONE, 1500);
        assertEquals(AmmoHudState.LINE_NO_AMMO, state.line(0, AK, 2999));
        assertEquals(AmmoHudState.LINE_NONE, state.line(0, AK, 1000 + AmmoHudState.NOTICE_MS));
    }

    @Test
    public void reloadStartReplacesAnEarlierNotice() {
        AmmoHudState state = new AmmoHudState();
        state.accept(0, AK, 10, 0, false, AmmoHudPayload.NOTICE_RELOAD_CANCELLED, 0);
        assertEquals(AmmoHudState.LINE_RELOAD_CANCELLED, state.line(0, AK, 0));
        state.accept(0, AK, 10, 60, true, AmmoHudPayload.NOTICE_NONE, 10);
        assertEquals(AmmoHudState.LINE_RELOADING, state.line(0, AK, 10));
        state.accept(0, AK, 30, 40, false, AmmoHudPayload.NOTICE_NONE, 20);
        assertEquals(AmmoHudState.LINE_NONE, state.line(0, AK, 20));
    }

    @Test
    public void clearForgetsStateAndNotices() {
        AmmoHudState state = new AmmoHudState();
        state.accept(0, AK, 0, 0, false, AmmoHudPayload.NOTICE_NO_AMMO, 0);
        state.clear();
        assertEquals(-1, state.reserve(0, AK));
        assertEquals(AmmoHudState.LINE_NONE, state.line(0, AK, 0));
    }

    @Test
    public void numberTextIsReusedUntilTheValueChanges() {
        AmmoHudState.NumberText text = new AmmoHudState.NumberText();
        String thirty = text.of(30);
        assertEquals("30", thirty);
        assertSame(thirty, text.of(30));
        assertEquals("29", text.of(29));
        assertEquals("", text.of(-1));
    }

    @Test
    public void placementIsReusedUntilAnInputChanges() {
        AmmoHudLayout.Cache cache = new AmmoHudLayout.Cache();
        AmmoHudLayout.Placement first = cache.place(640, 360, 2, 2, 51, 11, 11, false);
        assertSame(first, cache.place(640, 360, 2, 2, 51, 11, 11, false));
        assertNotSame(first, cache.place(640, 360, 2, 2, 51, 11, 17, false));
        assertEquals(AmmoHudLayout.place(640, 360, 3, 2, 51, 11, 17, true), cache.place(640, 360, 3, 2, 51, 11, 17, true));
    }
}
