package dev.csmc.skin.gameplay;

import dev.csmc.CsmcMod;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinNames;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToLongFunction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.RandomSource;

/**
 * Cosmetic case-opening animation for the receiving player. The result was already granted server-side when the case
 * was rolled; {@code strip} is a server-drawn filler reel with the result at {@code landingIndex}. {@code pool} names
 * the kind of case being opened, for the screen title.
 */
public record CaseOpeningPayload(WeaponCaseItem.Pool pool, List<Entry> strip, int landingIndex, SkinData result)
        implements CustomPacketPayload {
    public static final Type<CaseOpeningPayload> TYPE = new Type<>(CsmcMod.id("case_opening"));
    /** Reel length and the fixed slot the result lands on (a few filler tiles remain to its right, like CS). */
    public static final int STRIP_LENGTH = 50;
    public static final int LANDING_INDEX = 44;
    /** Codec bounds: generous over what {@link #create} produces, small enough to keep a hostile packet cheap. */
    public static final int MAX_STRIP = 64;
    public static final int MAX_STRING = 64;

    /** One reel tile. {@code tier} indexes {@link SkinCatalog#RARITY_TIERS}. */
    public record Entry(String weapon, int paintKit, String name, int tier) {
        public Entry {
            if (tier < 0 || tier >= SkinCatalog.RARITY_TIERS.size()) throw new IllegalArgumentException("Invalid rarity tier");
            // Kits always have positive ids; the client builds SkinData from this.
            if (paintKit <= 0) throw new IllegalArgumentException("Invalid paint kit " + paintKit);
        }

        static Entry of(WeaponCaseItem.Pick pick) {
            return new Entry(pick.weapon(), pick.kit().paintKit(), clip(SkinNames.displayName(pick.kit())), pick.tier());
        }
    }

    public CaseOpeningPayload {
        if (pool == null) throw new IllegalArgumentException("Missing case pool");
        strip = List.copyOf(strip);
        if (strip.isEmpty() || strip.size() > MAX_STRIP) throw new IllegalArgumentException("Invalid strip length");
        if (landingIndex < CaseScroll.MIN_LANDING_INDEX || landingIndex >= strip.size()) {
            throw new IllegalArgumentException("Invalid landing index " + landingIndex);
        }
        if (strip.get(landingIndex).paintKit() != result.paintKit()) throw new IllegalArgumentException("Result not at landing index");
    }

    public Entry resultEntry() { return strip.get(landingIndex); }

    /**
     * Builds the reel deterministically from {@code stripSeed}: same seed, kits, weapons and weights give the same
     * strip. Filler draws from the same {@code weapons} the result was rolled over, so the reel shows only them.
     */
    public static CaseOpeningPayload create(WeaponCaseItem.Pool pool, WeaponCaseItem.Rolled rolled,
                                            List<SkinCatalog.Kit> kits, Collection<String> weapons, long stripSeed,
                                            ToLongFunction<String> rarityWeight) {
        RandomSource random = RandomSource.create(stripSeed);
        List<Entry> strip = new ArrayList<>(STRIP_LENGTH);
        for (int i = 0; i < STRIP_LENGTH; i++) {
            if (i == LANDING_INDEX) {
                strip.add(Entry.of(rolled.pick()));
                continue;
            }
            // The result was drawn from these kits and weapons, so a filler pick always exists.
            strip.add(Entry.of(WeaponCaseItem.pick(kits, weapons, random, rarityWeight).orElseThrow()));
        }
        return new CaseOpeningPayload(pool, strip, LANDING_INDEX, rolled.data());
    }

    private static String clip(String text) {
        return text.length() <= MAX_STRING ? text : text.substring(0, MAX_STRING);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, Entry> ENTRY_CODEC = StreamCodec.of((buf, entry) -> {
        buf.writeUtf(entry.weapon(), MAX_STRING);
        buf.writeVarInt(entry.paintKit());
        buf.writeUtf(entry.name(), MAX_STRING);
        buf.writeByte(entry.tier());
    }, buf -> {
        String weapon = buf.readUtf(MAX_STRING);
        int paintKit = buf.readVarInt();
        String name = buf.readUtf(MAX_STRING);
        int tier = buf.readByte();
        try {
            return new Entry(weapon, paintKit, name, tier);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException(invalid.getMessage(), invalid);
        }
    });

    private static final WeaponCaseItem.Pool[] POOLS = WeaponCaseItem.Pool.values();

    public static final StreamCodec<RegistryFriendlyByteBuf, CaseOpeningPayload> STREAM_CODEC = StreamCodec.of((buf, packet) -> {
        buf.writeByte(packet.pool.ordinal());
        buf.writeVarInt(packet.strip.size());
        for (Entry entry : packet.strip) ENTRY_CODEC.encode(buf, entry);
        buf.writeVarInt(packet.landingIndex);
        SkinData.STREAM_CODEC.encode(buf, packet.result);
    }, buf -> {
        int pool = buf.readByte();
        if (pool < 0 || pool >= POOLS.length) throw new DecoderException("Case pool " + pool + " out of range");
        int size = buf.readVarInt();
        if (size <= 0 || size > MAX_STRIP) throw new DecoderException("Case strip length " + size + " out of range");
        List<Entry> strip = new ArrayList<>(size);
        for (int i = 0; i < size; i++) strip.add(ENTRY_CODEC.decode(buf));
        int landing = buf.readVarInt();
        SkinData result = SkinData.STREAM_CODEC.decode(buf);
        try {
            return new CaseOpeningPayload(POOLS[pool], strip, landing, result);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException(invalid.getMessage(), invalid);
        }
    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
