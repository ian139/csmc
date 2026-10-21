package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class PcmDownmixTest {
    private static ByteBuffer pcm(ByteOrder order, int... samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(order);
        for (int sample : samples) buffer.putShort((short) sample);
        return buffer.flip();
    }

    private static short[] samples(ByteBuffer mono) {
        ByteBuffer read = mono.duplicate().order(mono.order());
        short[] out = new short[read.remaining() / 2];
        for (int i = 0; i < out.length; i++) out[i] = read.getShort();
        return out;
    }

    @Test
    public void stereoFramesAverageIntoOneChannel() {
        ByteBuffer mono = PcmDownmix.toMono16(pcm(ByteOrder.LITTLE_ENDIAN, 100, 300, -200, -400, 7, 8), 2, false);
        assertEquals(ByteOrder.LITTLE_ENDIAN, mono.order());
        assertTrue("OpenAL needs a direct buffer", mono.isDirect());
        assertEquals(0, mono.position());
        short[] out = samples(mono);
        assertEquals(3, out.length);
        assertEquals(200, out[0]);
        assertEquals(-300, out[1]);
        assertEquals(7, out[2]);
    }

    @Test
    public void extremesDoNotOverflow() {
        short[] out = samples(PcmDownmix.toMono16(pcm(ByteOrder.LITTLE_ENDIAN,
                Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE), 2, false));
        assertEquals(Short.MAX_VALUE, out[0]);
        assertEquals(Short.MIN_VALUE, out[1]);
    }

    @Test
    public void bigEndianAndMoreChannelsAreHonoured() {
        ByteBuffer mono = PcmDownmix.toMono16(pcm(ByteOrder.BIG_ENDIAN, 30, 60, 90, -3, -6, -9), 3, true);
        assertEquals(ByteOrder.BIG_ENDIAN, mono.order());
        short[] out = samples(mono);
        assertEquals(2, out.length);
        assertEquals(60, out[0]);
        assertEquals(-6, out[1]);
    }

    @Test
    public void readsFromPositionWithoutConsumingInputAndDropsPartialFrame() {
        ByteBuffer input = pcm(ByteOrder.LITTLE_ENDIAN, 999, 10, 20, 5);
        input.position(2);
        short[] out = samples(PcmDownmix.toMono16(input, 2, false));
        assertEquals(1, out.length);
        assertEquals(15, out[0]);
        assertEquals(2, input.position());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveChannelCount() {
        PcmDownmix.toMono16(pcm(ByteOrder.LITTLE_ENDIAN, 1), 0, false);
    }
}
