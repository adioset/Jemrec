package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The voice changer: a tone comes out at the pitch asked for, and not louder. */
public class PitchShifterTest {

    private static final int RATE = 48_000;
    // 240 Hz: half the shifter's 25 ms window is exactly three cycles of it, so
    // the two taps of a pure tone are in step and the measurement is clean. A
    // voice is not a pure tone; the test is of the arithmetic.

    /**
     * The strongest frequency of a tone after the shifter, found by trying each
     * whole hertz from 80 to 700 over a second once it has settled. (Counting
     * zero crossings would be thrown by the crossfade's ripple.)
     */
    private static double hertz(int toneHz, int semitones) {
        PitchShifter shifter = new PitchShifter(RATE, semitones);
        int settle = RATE / 2;
        short[] out = new short[RATE];
        int peak = 0;
        for (int i = 0; i < settle + RATE; i++) {
            short in = (short) Math.round(10_000 * Math.sin(2 * Math.PI * toneHz * i / RATE));
            short o = shifter.process(in);
            if (i >= settle) {
                out[i - settle] = o;
                peak = Math.max(peak, Math.abs(o));
            }
        }
        assertTrue("louder than it went in: " + peak, peak <= 10_500);
        assertTrue("went silent: " + peak, peak > 6_000);
        double best = 0;
        double bestPower = -1;
        for (int f = 80; f <= 700; f++) {
            double re = 0;
            double im = 0;
            for (int i = 0; i < out.length; i++) {
                double angle = 2 * Math.PI * f * i / RATE;
                re += out[i] * Math.cos(angle);
                im += out[i] * Math.sin(angle);
            }
            double power = re * re + im * im;
            if (power > bestPower) {
                bestPower = power;
                best = f;
            }
        }
        return best;
    }

    @Test
    public void noShiftKeepsThePitch() {
        assertEquals(240, hertz(240, 0), 2);
    }

    @Test
    public void upFourSemitonesIsAMajorThirdHigher() {
        assertEquals(240 * Math.pow(2, 4 / 12.0), hertz(240, 4), 3);
    }

    @Test
    public void upEightSemitonesIsHigherStill() {
        assertEquals(240 * Math.pow(2, 8 / 12.0), hertz(240, 8), 3);
    }

    @Test
    public void downFourSemitonesIsLower() {
        assertEquals(240 * Math.pow(2, -4 / 12.0), hertz(240, -4), 3);
    }

    @Test
    public void silenceStaysSilent() {
        PitchShifter shifter = new PitchShifter(RATE, 6);
        for (int i = 0; i < 10_000; i++) {
            assertEquals(0, shifter.process((short) 0));
        }
    }
}
