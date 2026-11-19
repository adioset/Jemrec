package com.jemcik.jemrec.clips

import android.content.Context
import java.io.File
import kotlin.math.roundToInt

/**
 * The clips played into a call by hand: up to [SLOTS] sounds - a greeting, a
 * "please hold", a line to say again - each kept here as raw 16-bit
 * little-endian mono PCM at 48 kHz, the format the recorder plays (the
 * greeting's own). The recorder plays one into the phone call's uplink with the
 * microphone muted, and gives the microphone back when it ends.
 *
 * The app holds the master copies and sends them to the recorder
 * (CaptureDaemon.syncClips), as it does the greeting: a recorder that is
 * replaced is given them again. A slot is two files in `files/clips`:
 * "clip-N.pcm" and "clip-N.name".
 *
 * What the user brings is whatever they have: a WAV file, or raw PCM at some
 * rate. [convert] makes either the recorder's format. Nothing is levelled or
 * trimmed - unlike a greeting, these are the user's own sounds, played as
 * they are - but a call's uplink plays at full volume with no control over
 * it, so a clip recorded loud will be loud.
 */
object Clips {

    const val SLOTS = 20
    const val RATE = 48_000
    const val MAX_SECONDS = 60
    const val MAX_BYTES = MAX_SECONDS * RATE * 2

    /** The rates a raw .pcm file is offered in; a WAV file says its own. */
    val RAW_RATES = listOf(8_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)

    /** One kept clip. */
    data class Clip(val slot: Int, val name: String, val bytes: Int) {
        val seconds: Double get() = bytes / 2.0 / RATE
    }

    private fun dir(context: Context) = File(context.filesDir, "clips")

    private fun pcmFile(context: Context, slot: Int) = File(dir(context), "clip-$slot.pcm")

    private fun nameFile(context: Context, slot: Int) = File(dir(context), "clip-$slot.name")

    /** Every kept clip, in slot order. */
    fun list(context: Context): List<Clip> = (0 until SLOTS).mapNotNull { slot ->
        val pcm = pcmFile(context, slot)
        if (!pcm.isFile || pcm.length() <= 0) {
            null
        } else {
            Clip(slot, nameFile(context, slot).takeIf { it.isFile }?.readText()?.trim().orEmpty()
                .ifEmpty { "Clip ${slot + 1}" }, pcm.length().toInt())
        }
    }

    /** Whether clips mix with the microphone (true) or mute it (false). A file's
     *  presence, not a preference, so the app's settings keep their inventory. */
    fun mixOn(context: Context): Boolean = File(dir(context), "mix-on").isFile

    fun setMix(context: Context, on: Boolean) {
        val f = File(dir(context), "mix-on")
        if (on) {
            dir(context).mkdirs()
            f.writeText("1")
        } else {
            f.delete()
        }
    }

    /** Voice gain when mixing, in tenths (30 = three times); the raw microphone is
     *  quiet. A file, like [mixOn]. */
    const val DEFAULT_VOICE = 30
    val VOICE_LEVELS = listOf(10, 20, 30, 40, 60, 80)

    fun voiceTenths(context: Context): Int =
        File(dir(context), "voice-gain").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?.coerceIn(10, 80) ?: DEFAULT_VOICE

    fun setVoiceTenths(context: Context, tenths: Int) {
        dir(context).mkdirs()
        File(dir(context), "voice-gain").writeText(tenths.coerceIn(10, 80).toString())
    }

    /** Semitones the voice changer moves the voice: up is positive. */
    const val DEFAULT_PITCH = 4
    val PITCH_STEPS = listOf(4, 6, 8, 10, -4, -8)

    fun pitchSteps(context: Context): Int =
        File(dir(context), "voice-pitch").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?.coerceIn(-12, 12) ?: DEFAULT_PITCH

    fun setPitchSteps(context: Context, steps: Int) {
        dir(context).mkdirs()
        File(dir(context), "voice-pitch").writeText(steps.coerceIn(-12, 12).toString())
    }

    /** The first slot with nothing in it, or null when all are used. */
    fun freeSlot(context: Context): Int? = (0 until SLOTS).firstOrNull { !pcmFile(context, it).isFile }

    fun load(context: Context, slot: Int): ByteArray? =
        pcmFile(context, slot).takeIf { it.isFile }?.readBytes()

    fun save(context: Context, slot: Int, name: String, pcm: ByteArray) {
        require(slot in 0 until SLOTS) { "no clip slot $slot" }
        require(pcm.isNotEmpty() && pcm.size <= MAX_BYTES && pcm.size % 2 == 0) { "not a clip: ${pcm.size} bytes" }
        dir(context).mkdirs()
        val next = File(dir(context), "clip-$slot.pcm.new")
        next.writeBytes(pcm)
        check(next.renameTo(pcmFile(context, slot))) { "could not keep the clip" }
        nameFile(context, slot).writeText(cleanName(name))
    }

    fun rename(context: Context, slot: Int, name: String) {
        if (pcmFile(context, slot).isFile) nameFile(context, slot).writeText(cleanName(name))
    }

    fun delete(context: Context, slot: Int) {
        pcmFile(context, slot).delete()
        nameFile(context, slot).delete()
    }

    /** A name a person can read in a list: one line, not endless. */
    internal fun cleanName(name: String): String =
        name.replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { "Clip" }

    /** A file's name as a clip's: without its extension. */
    internal fun nameOf(fileName: String?): String {
        val last = (fileName ?: "").substringAfterLast('/')
        val base = if ('.' in last) last.substringBeforeLast('.') else last
        return cleanName(base.replace('_', ' ').replace('-', ' '))
    }

    /** What reading a file came to: a clip in the recorder's format, or why not. */
    sealed interface Converted {
        class Usable(val pcm: ByteArray, val seconds: Double) : Converted
        data object Empty : Converted
        data class TooLong(val seconds: Double) : Converted
        /** A WAV file in a form this does not read, or a file that is not audio. */
        data class Unreadable(val why: String) : Converted
    }

    /**
     * Any file the user picked, as a clip. A WAV file (RIFF, 8 or 16 bit
     * PCM, any rate, any number of channels - mixed down to one) is read as
     * it says it is; anything else is raw 16-bit little-endian mono PCM at
     * [rawRate]. Pure, for the tests.
     */
    fun convert(file: ByteArray, rawRate: Int): Converted {
        if (file.isEmpty()) return Converted.Empty
        val wav = readWav(file)
        val samples: ShortArray
        val rate: Int
        when {
            wav is Wav.Unsupported -> return Converted.Unreadable(wav.why)
            wav is Wav.Pcm -> {
                samples = wav.samples
                rate = wav.rate
            }
            else -> {
                samples = toShorts(file)
                rate = rawRate
            }
        }
        if (samples.isEmpty()) return Converted.Empty
        val seconds = samples.size.toDouble() / rate
        if (seconds > MAX_SECONDS) return Converted.TooLong(seconds)
        val out = resample(samples, rate)
        if (out.isEmpty()) return Converted.Empty
        return Converted.Usable(toBytes(out), out.size.toDouble() / RATE)
    }

    /** A WAV file read, or what is wrong with it; [None] when it is not one. */
    internal sealed interface Wav {
        data object None : Wav
        class Pcm(val samples: ShortArray, val rate: Int) : Wav
        data class Unsupported(val why: String) : Wav
    }

    internal fun readWav(b: ByteArray): Wav {
        if (b.size < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return Wav.None
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var seenFormat = false
        while (pos + 8 <= b.size) {
            val size = le32(b, pos + 4)
            val body = pos + 8
            if (tag(b, pos, "fmt ")) {
                if (size < 16 || body + 16 > b.size) return Wav.Unsupported("This WAV file is damaged.")
                format = le16(b, body)
                channels = le16(b, body + 2)
                rate = le32(b, body + 4).toInt()
                bits = le16(b, body + 14)
                // 0xFFFE: WAVE_FORMAT_EXTENSIBLE, whose first two bytes of subformat say PCM.
                if (format == 0xFFFE && size >= 26 && body + 26 <= b.size) format = le16(b, body + 24)
                seenFormat = true
            } else if (tag(b, pos, "data")) {
                if (!seenFormat) return Wav.Unsupported("This WAV file is damaged.")
                if (format != 1) return Wav.Unsupported("Only plain PCM WAV files can be used, not compressed ones.")
                if (bits != 16 && bits != 8) return Wav.Unsupported("Only 8 and 16 bit WAV files can be used.")
                if (channels < 1 || rate !in 1_000..192_000) return Wav.Unsupported("This WAV file is damaged.")
                // A streamed file says 0 or -1 for its length: it is all that is left.
                val left = b.size - body
                val length = if (size <= 0 || size > left) left.toLong() else size
                val frameBytes = channels * (bits / 8)
                val frames = (length / frameBytes).toInt()
                val samples = ShortArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until channels) {
                        val at = body + f * frameBytes + c * (bits / 8)
                        sum += if (bits == 16) {
                            ((b[at].toInt() and 0xff) or (b[at + 1].toInt() shl 8)).toShort().toInt()
                        } else {
                            ((b[at].toInt() and 0xff) - 128) shl 8
                        }
                    }
                    (sum / channels).toShort()
                }
                return Wav.Pcm(samples, rate)
            }
            // Chunks are padded to an even length.
            val next = body.toLong() + size + (size and 1)
            if (next > b.size) break
            pos = next.toInt()
        }
        return Wav.Unsupported("This WAV file has no sound in it.")
    }

    /** Straight-line resampling to [RATE]: for speech played into a call, which
     *  is band-limited by the phone network whatever it is fed. */
    internal fun resample(samples: ShortArray, from: Int): ShortArray {
        if (from == RATE) return samples
        val count = (samples.size.toLong() * RATE / from).toInt()
        if (count <= 0) return ShortArray(0)
        val step = from.toDouble() / RATE
        return ShortArray(count) { i ->
            val at = i * step
            val lo = at.toInt().coerceAtMost(samples.size - 1)
            val hi = (lo + 1).coerceAtMost(samples.size - 1)
            val frac = at - lo
            (samples[lo] + (samples[hi] - samples[lo]) * frac).roundToInt().toShort()
        }
    }

    internal fun toShorts(pcm: ByteArray): ShortArray = ShortArray(pcm.size / 2) { i ->
        ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
    }

    internal fun toBytes(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s ->
            out[2 * i] = s.toInt().toByte()
            out[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
    }

    private fun tag(b: ByteArray, at: Int, text: String) =
        at + 4 <= b.size && text.indices.all { b[at + it] == text[it].code.toByte() }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, at: Int): Long =
        (le16(b, at).toLong()) or (le16(b, at + 2).toLong() shl 16)

    /** m:ss for a counter. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
