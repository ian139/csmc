package dev.csmc.viewmodel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Downmixes interleaved signed 16-bit PCM to one channel so OpenAL spatializes it (multi-channel buffers play
 * unattenuated and undirected).
 */
public final class PcmDownmix {
    private PcmDownmix() {}

    /**
     * Averages each frame's channels from {@code pcm}'s position to its limit, without consuming {@code pcm}. A
     * trailing partial frame is dropped. Returns a new direct buffer in the input's sample byte order.
     */
    public static ByteBuffer toMono16(ByteBuffer pcm, int channels, boolean bigEndian) {
        if (channels <= 0) throw new IllegalArgumentException("Channel count must be positive: " + channels);
        ByteOrder order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer in = pcm.duplicate().order(order);
        int frameBytes = 2 * channels;
        int frames = in.remaining() / frameBytes;
        ByteBuffer out = ByteBuffer.allocateDirect(frames * 2).order(order);
        int base = in.position();
        for (int frame = 0; frame < frames; frame++) {
            int offset = base + frame * frameBytes;
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) sum += in.getShort(offset + channel * 2);
            out.putShort(frame * 2, (short) (sum / channels));
        }
        return out;
    }
}
