package com.jemcik.jemrec.clips

import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.ui.clipsSummary
import com.jemcik.jemrec.ui.pitchLabel
import com.jemcik.jemrec.ui.voiceLabel
import com.jemcik.jemrec.ui.rateLabel
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files the user brings, made into clips the recorder plays; and the clip counter's line. */
class ClipsTest {

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun wav(rate: Int, channels: Int, bits: Int, data: ByteArray, format: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(le32(36 + data.size)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(format)); out.write(le16(channels))
        out.write(le32(rate)); out.write(le32(rate * channels * bits / 8)); out.write(le16(channels * bits / 8))
        out.write(le16(bits))
        out.write("data".toByteArray()); out.write(le32(data.size)); out.write(data)
        return out.toByteArray()
    }

    private fun pcm(vararg samples: Int): ByteArray = Clips.toBytes(ShortArray(samples.size) { samples[it].toShort() })

    @Test
    fun rawPcmAtTheRecordersRateIsKeptAsItIs() {
        val raw = pcm(1, -2, 300, -400)
        val made = Clips.convert(raw, 48_000) as Clips.Converted.Usable
        assertArrayEquals(raw, made.pcm)
    }

    @Test
    fun rawPcmAtALowerRateIsStretchedToFortyEightKilohertz() {
        // One second at 8 kHz is one second at 48 kHz: six times the samples.
        val raw = Clips.toBytes(ShortArray(8_000) { 1000 })
        val made = Clips.convert(raw, 8_000) as Clips.Converted.Usable
        assertEquals(48_000 * 2, made.pcm.size)
        assertEquals(1.0, made.seconds, 0.001)
        assertTrue(Clips.toShorts(made.pcm).all { it.toInt() == 1000 })
    }

    @Test
    fun resamplingMeetsTheOriginalSamplesAndInterpolatesBetween() {
        val out = Clips.resample(shortArrayOf(0, 600), 24_000)
        // Twice as many samples, the second halfway between the two.
        assertEquals(4, out.size)
        assertEquals(0, out[0].toInt())
        assertEquals(300, out[1].toInt())
        assertEquals(600, out[2].toInt())
    }

    @Test
    fun aSixteenBitMonoWavIsReadAtItsOwnRate() {
        val file = wav(48_000, 1, 16, pcm(10, 20, 30))
        val made = Clips.convert(file, 8_000) as Clips.Converted.Usable
        assertArrayEquals(pcm(10, 20, 30), made.pcm)
    }

    @Test
    fun stereoIsMixedDownToMono() {
        val file = wav(48_000, 2, 16, pcm(100, 300, -200, 200))
        val made = Clips.convert(file, 48_000) as Clips.Converted.Usable
        assertArrayEquals(pcm(200, 0), made.pcm)
    }

    @Test
    fun eightBitWavIsCentredAndWidened() {
        val file = wav(48_000, 1, 8, byteArrayOf(128.toByte(), 255.toByte(), 0))
        val made = Clips.convert(file, 48_000) as Clips.Converted.Usable
        assertArrayEquals(pcm(0, 127 * 256, -128 * 256), made.pcm)
    }

    @Test
    fun aStreamedWavWithNoLengthIsReadToItsEnd() {
        val file = wav(48_000, 1, 16, pcm(5, 6, 7))
        // A recorder that did not know the length writes zero where it goes.
        val at = file.size - 6 - 4
        for (i in 0..3) file[at + i] = 0
        val made = Clips.convert(file, 48_000) as Clips.Converted.Usable
        assertArrayEquals(pcm(5, 6, 7), made.pcm)
    }

    @Test
    fun compressedAndOddWavFilesAreRefusedWithAReason() {
        val adpcm = Clips.convert(wav(8_000, 1, 16, pcm(1, 2), format = 2), 48_000)
        assertTrue(adpcm is Clips.Converted.Unreadable)
        val bits24 = Clips.convert(wav(48_000, 1, 24, ByteArray(6)), 48_000)
        assertTrue(bits24 is Clips.Converted.Unreadable)
    }

    @Test
    fun nothingIsNothing() {
        assertEquals(Clips.Converted.Empty, Clips.convert(ByteArray(0), 48_000))
        assertEquals(Clips.Converted.Empty, Clips.convert(wav(48_000, 1, 16, ByteArray(0)), 48_000))
    }

    @Test
    fun moreThanAMinuteIsTooLong() {
        val raw = ByteArray(2 * 8_000 * 61)
        val made = Clips.convert(raw, 8_000)
        assertTrue(made is Clips.Converted.TooLong)
        assertEquals(61.0, (made as Clips.Converted.TooLong).seconds, 0.001)
        assertTrue(Clips.convert(ByteArray(2 * 8_000 * 60), 8_000) is Clips.Converted.Usable)
    }

    @Test
    fun aFileNameMakesAClipsName() {
        assertEquals("please hold", Clips.nameOf("please_hold.wav"))
        assertEquals("greeting 1", Clips.nameOf("greeting-1.pcm"))
        assertEquals("no extension", Clips.nameOf("no extension"))
        assertEquals("Clip", Clips.nameOf(null))
        assertEquals(40, Clips.cleanName("x".repeat(100)).length)
    }

    @Test
    fun theCounterReadsMinutesAndSeconds() {
        assertEquals("0:00", Clips.clock(0))
        assertEquals("0:07", Clips.clock(7_400))
        assertEquals("1:05", Clips.clock(65_000))
        assertEquals("0:00", Clips.clock(-5))
    }

    @Test
    fun theRecordersLineForWhereAClipIsIsRead() {
        assertEquals(
            CaptureDaemon.ClipState(playing = true, slot = 4, elapsedMs = 1_500, totalMs = 6_000, live = true),
            CaptureDaemon.parseClipState("CLIPSTATE playing 4 1500 6000 1"),
        )
        val idle = CaptureDaemon.parseClipState("CLIPSTATE idle -1 0 0 1")!!
        assertEquals(false, idle.playing)
        assertEquals(true, idle.live)
        assertNull(CaptureDaemon.parseClipState("CLIPSTATE playing 4"))
        assertNull(CaptureDaemon.parseClipState("PONG 2 abc"))
        assertNull(CaptureDaemon.parseClipState(null))
    }

    @Test
    fun theSettingsRowSaysHowManyClips() {
        assertEquals("Sounds to play into a call", clipsSummary(0))
        assertEquals("1 clip", clipsSummary(1))
        assertEquals("12 clips", clipsSummary(12))
        assertEquals("8 kHz", rateLabel(8_000))
        assertEquals("22.05 kHz", rateLabel(22_050))
        assertEquals("1x (as it is)", voiceLabel(10))
        assertEquals("3x", voiceLabel(30))
        assertEquals("1.5x", voiceLabel(15))
        assertEquals("Higher (+4)", pitchLabel(4))
        assertEquals("Lower (-4)", pitchLabel(-4))
    }
}
