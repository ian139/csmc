package dev.csmc.skin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** The actual item wear float, not a normalized position within a kit's market wear range. */
public record SkinData(int paintKit, float wear, int seed) {
    public static final Codec<SkinData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("paint_kit").forGetter(SkinData::paintKit),
            Codec.floatRange(0, 1).fieldOf("wear").forGetter(SkinData::wear),
            Codec.intRange(0, 1000).fieldOf("seed").forGetter(SkinData::seed)
    ).apply(instance, SkinData::new));
    public static final StreamCodec<ByteBuf, SkinData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SkinData::paintKit,
            ByteBufCodecs.FLOAT, SkinData::wear,
            ByteBufCodecs.VAR_INT, SkinData::seed, SkinData::new);

    public SkinData {
        if (paintKit < 0 || !Float.isFinite(wear) || wear < 0 || wear > 1 || seed < 0 || seed > 1000) {
            throw new IllegalArgumentException("Invalid skin paint kit, wear or seed");
        }
    }

    public String wearTier() {
        return wearTier(wear);
    }

    public static String wearTier(float wear) {
        if (wear < .07f) return "Factory New";
        if (wear < .15f) return "Minimal Wear";
        if (wear < .38f) return "Field-Tested";
        if (wear < .45f) return "Well-Worn";
        return "Battle-Scarred";
    }
}
