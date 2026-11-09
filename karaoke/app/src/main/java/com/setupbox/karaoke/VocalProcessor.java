package com.setupbox.karaoke;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * Stereo 16-bit PCM channel selector for KTV files that carry the vocal guide on one channel
 * and the backing track on the other. LEFT/RIGHT copy the chosen channel to both speakers.
 */
public final class VocalProcessor extends BaseAudioProcessor {
    public static final int BOTH = 0, LEFT = 1, RIGHT = 2;
    private volatile int mode = BOTH;

    public void setMode(int m) { mode = m; }
    public int getMode() { return mode; }

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT || in.channelCount != 2) {
            return AudioFormat.NOT_SET; // inactive: mono / other formats pass through untouched
        }
        return in;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int size = input.remaining();
        if (size == 0) return;
        ByteBuffer out = replaceOutputBuffer(size);
        int m = mode;
        if (m == BOTH) {
            out.put(input);
        } else {
            while (input.remaining() >= 4) {
                byte l0 = input.get(), l1 = input.get(), r0 = input.get(), r1 = input.get();
                byte a = m == LEFT ? l0 : r0, b = m == LEFT ? l1 : r1;
                out.put(a).put(b).put(a).put(b);
            }
        }
        out.flip();
    }
}
