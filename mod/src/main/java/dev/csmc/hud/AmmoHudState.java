package dev.csmc.hud;

import dev.csmc.network.AmmoHudPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client view of the server's {@link AmmoHudPayload} fields, kept pure for tests. Counts and notices apply only to
 * the gun (slot and weapon) they were sent for; a just-drawn gun shows its synced magazine until the server
 * confirms it. Notice codes are the payload's {@code NOTICE_*} constants.
 */
public final class AmmoHudState {
    public static final long NOTICE_MS = 2000;
    public static final int LINE_NONE = 0;
    public static final int LINE_RELOADING = 1;
    public static final int LINE_NO_AMMO = 2;
    public static final int LINE_RELOAD_CANCELLED = 3;

    private int slot = -1;
    private ResourceLocation weapon;
    private int magazine = -1;
    private int reserve;
    private boolean reloading;
    private int notice = AmmoHudPayload.NOTICE_NONE;
    private int noticeSlot = -1;
    private ResourceLocation noticeWeapon;
    private long noticeUntil;

    /** Applies one server update; a {@code magazine} of -1 clears the bound gun. */
    public void accept(int slot, ResourceLocation weapon, int magazine, int reserve, boolean reloading, int notice,
                       long nowMs) {
        this.slot = slot;
        this.weapon = weapon;
        this.magazine = magazine;
        this.reserve = reserve;
        this.reloading = reloading;
        if (notice != AmmoHudPayload.NOTICE_NONE) {
            this.notice = notice;
            noticeSlot = slot;
            noticeWeapon = weapon;
            noticeUntil = nowMs + NOTICE_MS;
        } else if (reloading) {
            this.notice = AmmoHudPayload.NOTICE_NONE;
        }
    }

    public void clear() {
        slot = -1;
        weapon = null;
        magazine = -1;
        reloading = false;
        notice = AmmoHudPayload.NOTICE_NONE;
        noticeWeapon = null;
    }

    public boolean bound(int slot, ResourceLocation weapon) {
        return magazine >= 0 && this.slot == slot && weapon.equals(this.weapon);
    }

    /** Server magazine for the bound gun, else the item's synced magazine. */
    public int magazine(int slot, ResourceLocation weapon, int itemMagazine) {
        return bound(slot, weapon) ? magazine : itemMagazine;
    }

    /** Matching inventory ammo for the bound gun, or -1 before the server has confirmed it. */
    public int reserve(int slot, ResourceLocation weapon) {
        return bound(slot, weapon) ? reserve : -1;
    }

    /** One of the {@code LINE_*} constants for the state line above the readout. */
    public int line(int slot, ResourceLocation weapon, long nowMs) {
        if (reloading && bound(slot, weapon)) return LINE_RELOADING;
        if (notice == AmmoHudPayload.NOTICE_NONE || nowMs >= noticeUntil
                || noticeSlot != slot || !weapon.equals(noticeWeapon)) {
            return LINE_NONE;
        }
        return notice == AmmoHudPayload.NOTICE_NO_AMMO ? LINE_NO_AMMO : LINE_RELOAD_CANCELLED;
    }

    /** Decimal text for a count, rebuilt only when the value changes; negative values are blank. */
    public static final class NumberText {
        private int value = Integer.MIN_VALUE;
        private String text = "";

        public String of(int value) {
            if (value != this.value) {
                this.value = value;
                text = value < 0 ? "" : Integer.toString(value);
            }
            return text;
        }
    }
}
