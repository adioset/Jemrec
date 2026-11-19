package com.jemcik.jemrec.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.clips.Clips
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Clips screen shows. */
data class ClipsUi(
    val clips: List<Clips.Clip> = emptyList(),
    /** The sample rate a raw .pcm file is read at; a WAV file says its own. */
    val rawRate: Int = Clips.RATE,
    val importing: Boolean = false,
    /** Play with the microphone on, so the caller hears it and the clip. */
    val mix: Boolean = false,
    /** How much the microphone is raised when mixed, in tenths: 30 is three times. */
    val voiceTenths: Int = Clips.DEFAULT_VOICE,
    /** The voice changer is on: the caller hears the microphone changed, all the call. */
    val voiceLive: Boolean = false,
    /** Semitones the voice changer moves the voice; positive is higher. */
    val pitchSteps: Int = Clips.DEFAULT_PITCH,
    /** The clip playing into the call, and where it is: the counter. */
    val playingSlot: Int? = null,
    val elapsedMs: Long = 0L,
    val totalMs: Long = 0L,
    /** What went wrong last, or what the last import did, in words for the screen. */
    val message: String? = null,
    val problem: Boolean = false,
)

/**
 * The clips played into a call by hand: the list, importing PCM and WAV files,
 * and Play and Stop with the counter. Owned by MainViewModel, with a state flow
 * of its own as AnswerController has.
 *
 * Playing is the recorder's: it mutes the microphone, plays into the call's
 * uplink and gives the microphone back (the daemon's ClipPlayer and Main). This
 * only asks, and follows - asking where the clip is four times a second, which
 * is what the counter shows, until the recorder says it is idle.
 */
class ClipsController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
    initial: ClipsUi? = null,
) {
    private val _state = MutableStateFlow(initial ?: ClipsUi())
    val state: StateFlow<ClipsUi> = _state.asStateFlow()

    private var follow: Job? = null
    /** Tests seed the state; nothing is then read from disk over it. */
    private val seeded = initial != null

    init {
        // The list is on disk and cheap to read: Settings shows how many there are.
        reload()
    }

    fun reload() {
        if (seeded) return
        _state.update { it.copy(clips = Clips.list(app), mix = Clips.mixOn(app), voiceTenths = Clips.voiceTenths(app), pitchSteps = Clips.pitchSteps(app)) }
    }

    fun setVoiceTenths(tenths: Int) {
        if (!seeded) Clips.setVoiceTenths(app, tenths)
        _state.update { it.copy(voiceTenths = tenths) }
        resendVoice()
    }

    fun setPitchSteps(steps: Int) {
        if (!seeded) Clips.setPitchSteps(app, steps)
        _state.update { it.copy(pitchSteps = steps) }
        resendVoice()
    }

    /** The level or pitch changed while the voice changer is on: tell the recorder. */
    private fun resendVoice() {
        val s = _state.value
        if (s.voiceLive) scope.launch { CaptureDaemon.startVoice(app, s.voiceTenths, s.pitchSteps) }
    }

    /**
     * The voice changer, on or off. On, the recorder carries the microphone -
     * raised, and moved in pitch - into the phone call until this is turned
     * off or the call ends, and the caller hears only that. Needs a call.
     */
    fun setVoiceLive(on: Boolean) {
        scope.launch {
            _state.update { it.copy(message = null, problem = false) }
            if (!on) {
                liveWatch?.cancel()
                CaptureDaemon.stopClip(app)
                _state.update { it.copy(voiceLive = false, playingSlot = null, elapsedMs = 0L, totalMs = 0L) }
                return@launch
            }
            val s = _state.value
            val result = CaptureDaemon.startVoice(app, s.voiceTenths, s.pitchSteps)
            when {
                result == "live" -> {
                    _state.update { it.copy(voiceLive = true) }
                    watchLive()
                }
                result == null -> fail("The recorder did not answer. It must be running, and a recent one.")
                result == "nocall" -> fail("No phone call is up. Start or answer a call, then switch the voice changer on.")
                result == "busy" -> fail("The answering machine is taking a message on this call. Take the call over first.")
                result == "nomic" ->
                    fail("The recorder could not open the microphone, so the voice changer is off.")
                else -> fail("The voice changer could not start ($result).")
            }
        }
    }

    private var liveWatch: Job? = null

    /** The voice changer ends with the call: look once a second until the recorder says it is off. */
    private fun watchLive() {
        liveWatch?.cancel()
        liveWatch = scope.launch {
            var unanswered = 0
            while (true) {
                delay(1_000L)
                val now = CaptureDaemon.clipState(app)
                if (now == null) {
                    if (++unanswered >= 3) break
                    continue
                }
                unanswered = 0
                if (!now.live) break
            }
            _state.update { it.copy(voiceLive = false) }
        }
    }

    fun setMix(on: Boolean) {
        if (!seeded) Clips.setMix(app, on)
        _state.update { it.copy(mix = on) }
    }

    /** The screen opened: the list, the recorder given what it lacks, and a
     *  clip already playing - the app was closed and opened mid-clip - followed. */
    fun opened() {
        if (seeded) return
        reload()
        scope.launch {
            CaptureDaemon.syncClips(app)
            val now = CaptureDaemon.clipState(app)
            if (now != null && now.playing) follow(now.slot)
            if (now != null && now.live) {
                _state.update { it.copy(voiceLive = true) }
                watchLive()
            }
        }
    }

    fun setRawRate(rate: Int) {
        _state.update { it.copy(rawRate = rate) }
    }

    /**
     * Bring in the files the user picked, each into the next free slot. A file
     * that cannot be used is named in the message with why, and the rest go on.
     */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.importing) return
        _state.update { it.copy(importing = true, message = null, problem = false) }
        scope.launch {
            val rate = _state.value.rawRate
            var added = 0
            val skipped = mutableListOf<String>()
            for (uri in uris) {
                val fileName = withContext(Dispatchers.IO) { displayName(uri) }
                val label = fileName ?: "a file"
                val slot = withContext(Dispatchers.IO) { Clips.freeSlot(app) }
                if (slot == null) {
                    skipped += "$label: all ${Clips.SLOTS} places are used"
                    continue
                }
                val bytes = withContext(Dispatchers.IO) { readLimited(uri) }
                if (bytes == null) {
                    skipped += "$label: could not be read, or is too big"
                    continue
                }
                val converted = withContext(Dispatchers.Default) { Clips.convert(bytes, rate) }
                when (converted) {
                    is Clips.Converted.Usable -> {
                        withContext(Dispatchers.IO) { Clips.save(app, slot, Clips.nameOf(fileName), converted.pcm) }
                        added++
                        if (!CaptureDaemon.uploadClip(app, slot, converted.pcm)) {
                            say("clips: the recorder will get \"$label\" when it next restarts")
                        }
                    }
                    Clips.Converted.Empty -> skipped += "$label: there is no sound in it"
                    is Clips.Converted.TooLong ->
                        skipped += "$label: ${converted.seconds.toInt()} seconds is longer than ${Clips.MAX_SECONDS}"
                    is Clips.Converted.Unreadable -> skipped += "$label: ${converted.why}"
                }
            }
            val text = buildString {
                append(if (added == 1) "Added 1 clip." else "Added $added clips.")
                if (skipped.isNotEmpty()) append(" Left out: ").append(skipped.joinToString("; ")).append('.')
            }
            _state.update { it.copy(importing = false, clips = Clips.list(app), message = text, problem = skipped.isNotEmpty()) }
        }
    }

    fun rename(slot: Int, name: String) {
        Clips.rename(app, slot, name)
        _state.update { it.copy(clips = Clips.list(app)) }
    }

    fun delete(slot: Int) {
        if (_state.value.playingSlot == slot) stop()
        Clips.delete(app, slot)
        _state.update { it.copy(clips = Clips.list(app)) }
        scope.launch { CaptureDaemon.deleteClip(app, slot) }
    }

    /** Play [slot] into the call that is up. The recorder mutes the microphone
     *  first and gives it back at the end, or at Stop, or when the call ends. */
    fun play(slot: Int) {
        scope.launch {
            _state.update { it.copy(message = null, problem = false) }
            val s = _state.value
            // With the voice changer on every clip is mixed into it, whatever the
            // switch: a muted clip would let the real voice through at its end.
            val result = CaptureDaemon.playClip(
                app, slot, s.mix || s.voiceLive, s.voiceTenths, if (s.voiceLive) s.pitchSteps else 0,
            )
            when {
                result == null -> fail("The recorder did not answer. It must be running, and a recent one.")
                result.startsWith("playing") -> follow(slot)
                result == "nocall" ->
                    fail("No phone call is up. Start or answer a call, then play a clip. Messenger calls cannot take one.")
                result == "nomic" ->
                    fail("The recorder could not open the microphone to mix it in, so nothing was played. Switch Keep my voice on off to play the clip alone.")
                result == "busy" -> fail("The answering machine is taking a message on this call. Take the call over, then play a clip.")
                result == "nomute" ->
                    fail("The microphone could not be muted, so nothing was played: the caller would have heard the room.")
                result == "empty" -> {
                    fail("The recorder did not have that clip yet. It is being sent: try again in a moment.")
                    CaptureDaemon.syncClips(app)
                }
                else -> fail("The clip could not be played ($result).")
            }
        }
    }

    fun stop() {
        scope.launch {
            // Only the clip: the voice changer, if it is on, goes on.
            CaptureDaemon.dropClip(app)
            follow?.cancel()
            _state.update { it.copy(playingSlot = null, elapsedMs = 0L, totalMs = 0L) }
        }
    }

    private fun fail(text: String) {
        _state.update { it.copy(message = text, problem = true, playingSlot = null) }
    }

    /** The counter: ask where the clip is until the recorder says it is done. */
    private fun follow(slot: Int) {
        follow?.cancel()
        val total = _state.value.clips.firstOrNull { it.slot == slot }?.let { (it.seconds * 1000).toLong() } ?: 0L
        _state.update { it.copy(playingSlot = slot, elapsedMs = 0L, totalMs = total) }
        follow = scope.launch {
            var unanswered = 0
            while (true) {
                delay(POLL_MS)
                val now = CaptureDaemon.clipState(app)
                if (now == null) {
                    // A recorder that does not answer three times running is
                    // gone; the counter must not run on for a clip it cannot see.
                    if (++unanswered >= 3) break
                    continue
                }
                unanswered = 0
                if (!now.playing) break
                _state.update { it.copy(playingSlot = now.slot, elapsedMs = now.elapsedMs, totalMs = now.totalMs) }
            }
            _state.update { it.copy(playingSlot = null, elapsedMs = 0L, totalMs = 0L) }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** The whole file, or null when it cannot be read or is bigger than any
     *  clip could be - a minute of 16-bit stereo at 192 kHz is 23 MB. */
    private fun readLimited(uri: Uri): ByteArray? = runCatching {
        app.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE_BYTES) return@use null
            }
            out.toByteArray()
        }
    }.getOrNull()

    private companion object {
        const val POLL_MS = 250L
        const val MAX_FILE_BYTES = 24 * 1024 * 1024
    }
}
