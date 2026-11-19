package com.jemcik.jemrec.shell;

/**
 * Moves a voice up or down in pitch as it comes, a sample at a time, for the
 * voice changer (ClipPlayer's voice session).
 *
 * The classic two-tap delay line: the samples are written into a ring, and two
 * read points travel through it at [ratio] times the speed they are written -
 * faster than the writing for a higher voice, slower for a lower one - half a
 * window apart, each faded in and out by a triangle so that the jump where a
 * read point wraps is never heard (the two fades always add to one). It holds
 * the speech's pace, and since it moves the whole spectrum it moves the
 * voice's formants with the pitch, which is most of what makes a voice sound
 * smaller or larger. What it costs is a little roughness in the voice, and a
 * delay of half a window, 12 ms.
 *
 * Plain Java, so the tests can run it off the phone.
 */
final class PitchShifter {

    /** A window of 25 ms: long enough for a voice's pitch to survive it. */
    private static final int WINDOW_MS = 25;

    private final int window;
    private final float[] ring;
    private final double step;
    private int write;
    private double phase;

    /** [semitones] up (positive) or down (negative), at [rate] samples a second. */
    PitchShifter(int rate, int semitones) {
        window = rate * WINDOW_MS / 1000;
        ring = new float[window + 4];
        step = Math.pow(2, semitones / 12.0) - 1;
    }

    short process(short in) {
        ring[write] = in;
        double d1 = phase;
        double d2 = phase + window / 2.0;
        if (d2 >= window) {
            d2 -= window;
        }
        double out = fade(d1) * tap(d1) + fade(d2) * tap(d2);
        write = (write + 1) % ring.length;
        phase -= step;
        while (phase < 0) {
            phase += window;
        }
        while (phase >= window) {
            phase -= window;
        }
        return (short) Math.max(-32768, Math.min(32767, Math.round(out)));
    }

    /** 0 where a read point wraps, 1 half a window from it. */
    private double fade(double delay) {
        return 1 - Math.abs(2 * delay / window - 1);
    }

    /** The sample [delay] samples before the one just written, between two. */
    private double tap(double delay) {
        double pos = write - delay;
        int n = ring.length;
        int older = (int) Math.floor(pos);
        double frac = pos - older;
        int a = ((older % n) + n) % n;
        int b = (a + 1) % n;
        return ring[a] * (1 - frac) + ring[b] * frac;
    }
}
