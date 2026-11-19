package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Plays into the call's uplink, where the caller hears it. The route is the
 * greeting's (see GreetingPlayer, which measured them): a media track aimed at
 * the uplink device, or the call-screening track. Muting the microphone around
 * a muted clip is Main's job, not this class's.
 *
 * Two kinds of session, one at a time, each with a Session of its own as the
 * greeting has: a stop never waits for the thread, and a late thread can touch
 * only its own track.
 *
 * - A plain clip: the clip's samples, written to the track, and the end. The
 *   position, for the app's counter, is read from the track's playback head.
 * - A voice session: the injected track REPLACES the microphone on the call's
 *   uplink - on the user's phone (2026-11-19) the caller heard only the clip
 *   with the microphone unmuted - so to have the caller hear the user as well,
 *   the microphone is read here in 20 ms blocks, raised by the user's level,
 *   moved in pitch if the voice changer is on, added to the clip's samples,
 *   and the sum written to the track. A voice session can be "live": it goes
 *   on after the clip ends, and between clips, carrying the changed voice alone
 *   until stopped - so there is never a moment in which the caller hears the
 *   real voice instead. A clip is handed to a running voice session without
 *   a restart of the track, for the same reason.
 */
final class ClipPlayer {

    interface Listener {
        /** The clip has played to its end. */
        void done(long realMs, long audioMs);

        /** It could not be played, or stopped moving. */
        void failed(String why);
    }

    /** A clip mixed into a voice session; [off] is the voice thread's alone. */
    private static final class Clip {
        final int slot;
        final byte[] pcm;
        final Listener listener;
        int off;

        Clip(int slot, byte[] pcm, Listener listener) {
            this.slot = slot;
            this.pcm = pcm;
            this.listener = listener;
        }
    }

    private static final class Session {
        final int slot;
        final long frames;
        final boolean voice;
        volatile boolean stopped;
        volatile boolean finished;
        volatile @Nullable AudioTrack track;
        // A voice session's:
        final AtomicReference<Clip> clip = new AtomicReference<>();
        volatile boolean live;
        volatile int tenths = 10;
        volatile int pitch;
        volatile long clipFramesDone;

        Session(int slot, long frames, boolean voice) {
            this.slot = slot;
            this.frames = frames;
            this.voice = voice;
        }
    }

    /** 20 ms of audio: the size of one mixed block. */
    private static final int MIX_FRAMES = ClipBoard.RATE / 50;

    private volatile @Nullable Session current;

    /**
     * The microphone, started, for a voice session: 48 kHz mono 16-bit, as the
     * clip is. The session releases it.
     */
    @SuppressLint({"WrongConstant", "MissingPermission"})
    static AudioRecord openMic() {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(ClipBoard.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        AudioRecord mic = new AudioRecord.Builder()
                .setContext(FakeContext.get())
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setPrivacySensitive(false)
                .setBufferSizeInBytes(4 * AudioRecord.getMinBufferSize(
                        ClipBoard.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
                .build();
        try {
            mic.startRecording();
        } catch (RuntimeException e) {
            mic.release();
            throw e;
        }
        if (mic.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            mic.release();
            throw new IllegalStateException("the microphone did not start");
        }
        return mic;
    }

    /** A plain clip, the microphone's mute being Main's. */
    void play(AudioManager audio, String route, int slot, byte[] pcm, Listener listener) {
        stop();
        Session session = new Session(slot, pcm.length / 2, false);
        current = session;
        Thread t = new Thread(() -> run(session, audio, route, pcm, listener), "jemrec-clip");
        t.setDaemon(true);
        t.start();
    }

    /** Whether a voice session is running, to hand a clip to or to change. */
    boolean voiceActive() {
        Session session = current;
        return session != null && session.voice && !session.stopped && !session.finished;
    }

    /**
     * Start a voice session with [mic] (from [openMic]), which it releases: the
     * clip in [slot] if there is one, and the voice carried on afterwards if
     * [live].
     */
    void startVoice(AudioManager audio, String route, AudioRecord mic, int tenths, int pitch, boolean live,
            int slot, @Nullable byte[] pcm, @Nullable Listener listener) {
        stop();
        Session session = new Session(slot, pcm == null ? 0 : pcm.length / 2, true);
        session.tenths = tenths;
        session.pitch = pitch;
        session.live = live;
        if (pcm != null && listener != null) {
            session.clip.set(new Clip(slot, pcm, listener));
        }
        current = session;
        Thread t = new Thread(() -> runVoice(session, audio, route, mic), "jemrec-voice");
        t.setDaemon(true);
        t.start();
    }

    /** Give the running voice session a clip, in place of the one it has. */
    void handOver(int slot, byte[] pcm, int tenths, int pitch, Listener listener) {
        Session session = current;
        if (session == null || !session.voice) {
            return;
        }
        session.tenths = tenths;
        session.pitch = pitch;
        session.clip.set(new Clip(slot, pcm, listener));
    }

    /** Change the running voice session's level and pitch, and keep it going after its clip. */
    void setLive(int tenths, int pitch) {
        Session session = current;
        if (session == null || !session.voice) {
            return;
        }
        session.tenths = tenths;
        session.pitch = pitch;
        session.live = true;
    }

    /**
     * Stops the clip and keeps the voice going when the session is live; true
     * then. Otherwise stops the whole session, and false.
     */
    boolean dropClip() {
        Session session = current;
        if (session != null && session.voice && session.live && !session.stopped && !session.finished) {
            session.clip.set(null);
            return true;
        }
        stop();
        return false;
    }

    /** Stops whatever is playing; harmless when nothing is. */
    void stop() {
        Session session = current;
        current = null;
        if (session == null) {
            return;
        }
        session.stopped = true;
        AudioTrack tr = session.track;
        if (tr != null) {
            try {
                tr.pause();
                tr.flush();
            } catch (Throwable ignored) {
                // released already
            }
        }
    }

    /** Whether anything - a clip or the live voice - is going into the call. */
    boolean playing() {
        Session session = current;
        return session != null && !session.stopped && !session.finished;
    }

    /**
     * "CLIPSTATE playing slot elapsedMs totalMs live" while a clip plays,
     * "CLIPSTATE idle -1 0 0 live" when none does: the six fields the app's
     * counter reads (CaptureDaemon.clipState). [live] is 1 while the voice
     * changer's stream is running, between clips too.
     */
    String statusWire() {
        Session session = current;
        if (session == null || session.stopped || session.finished) {
            return "CLIPSTATE idle -1 0 0 0";
        }
        String live = session.live ? "1" : "0";
        if (session.voice) {
            Clip clip = session.clip.get();
            if (clip == null) {
                return "CLIPSTATE idle -1 0 0 " + live;
            }
            long total = clip.pcm.length / 2L;
            return "CLIPSTATE playing " + clip.slot + " "
                    + Math.min(session.clipFramesDone, total) * 1000 / ClipBoard.RATE + " "
                    + total * 1000 / ClipBoard.RATE + " " + live;
        }
        long head = 0;
        AudioTrack tr = session.track;
        if (tr != null) {
            try {
                head = Math.min(tr.getPlaybackHeadPosition() & 0xffffffffL, session.frames);
            } catch (Throwable ignored) {
                // released between the look and the read
            }
        }
        return "CLIPSTATE playing " + session.slot + " " + head * 1000 / ClipBoard.RATE + " "
                + session.frames * 1000 / ClipBoard.RATE + " " + live;
    }

    private static AudioTrack trackFor(AudioManager audio, String route) throws ReflectiveOperationException {
        return AnswerSettings.ROUTE_SCREENING.equals(route)
                ? GreetingPlayer.screeningTrack(audio) : GreetingPlayer.musicTrack(audio);
    }

    private void run(Session session, AudioManager audio, String route, byte[] pcm, Listener listener) {
        AudioTrack tr = null;
        try {
            tr = trackFor(audio, route);
            session.track = tr;
            if (session.stopped) {
                return;
            }
            tr.play();
            long started = SystemClock.elapsedRealtime();
            int off = 0;
            while (off < pcm.length && !session.stopped) {
                int n = tr.write(pcm, off, pcm.length - off);
                if (n < 0) {
                    throw new IllegalStateException("AudioTrack.write returned " + n);
                }
                off += n;
            }
            if (!waitPlayed(session, tr)) {
                if (!session.stopped) {
                    Ln.w("clip: did not play to the end");
                    listener.failed("the clip did not play to the end");
                }
                return;
            }
            long realMs = SystemClock.elapsedRealtime() - started;
            Ln.i("clip: slot " + session.slot + " played " + session.frames * 1000 / ClipBoard.RATE + " ms in "
                    + realMs + " ms on route " + route + ", underruns " + tr.getUnderrunCount());
            listener.done(realMs, session.frames * 1000 / ClipBoard.RATE);
        } catch (Throwable t) {
            if (!session.stopped) {
                Ln.e("clip: could not play", t);
                listener.failed(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } finally {
            session.finished = true;
            session.track = null;
            if (tr != null) {
                try {
                    tr.release();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * The voice session: each block waits for 20 ms of microphone, so the
     * microphone's clock paces the writes; the block is the microphone (raised,
     * and moved in pitch if asked), plus the clip's next 20 ms if one is
     * playing, and goes to the uplink. Ends when stopped, or when the clip has
     * ended and the session is not live. A microphone that stops answering
     * leaves the clip playing alone.
     */
    private void runVoice(Session session, AudioManager audio, String route, AudioRecord mic) {
        AudioTrack tr = null;
        try {
            tr = trackFor(audio, route);
            session.track = tr;
            if (session.stopped) {
                return;
            }
            tr.play();
            long started = SystemClock.elapsedRealtime();
            byte[] heard = new byte[MIX_FRAMES * 2];
            byte[] out = new byte[MIX_FRAMES * 2];
            @Nullable PitchShifter shifter = null;
            int shifterPitch = 0;
            @Nullable Clip active = null;
            int peak = 0;
            boolean micWorks = true;
            while (!session.stopped) {
                Clip next = session.clip.get();
                if (next != active) {
                    active = next;
                    session.clipFramesDone = 0;
                    if (active != null) {
                        active.off = 0;
                        started = SystemClock.elapsedRealtime();
                    }
                }
                if (active == null && !session.live) {
                    break;
                }
                int got = 0;
                if (micWorks) {
                    got = mic.read(heard, 0, heard.length);
                    if (got < 0) {
                        Ln.w("clip: the microphone stopped answering (" + got + "); going on without it");
                        micWorks = false;
                        got = 0;
                    }
                    got &= ~1;
                }
                int pitch = session.pitch;
                if (pitch != shifterPitch || (pitch != 0 && shifter == null)) {
                    shifter = pitch == 0 ? null : new PitchShifter(ClipBoard.RATE, pitch);
                    shifterPitch = pitch;
                }
                int tenths = session.tenths;
                for (int i = 0; i < out.length; i += 2) {
                    int clipSample = 0;
                    if (active != null && active.off + i + 1 < active.pcm.length) {
                        clipSample = (short) ((active.pcm[active.off + i] & 0xff) | (active.pcm[active.off + i + 1] << 8));
                    }
                    int voice = i < got ? (short) ((heard[i] & 0xff) | (heard[i + 1] << 8)) : 0;
                    peak = Math.max(peak, Math.abs(voice));
                    if (shifter != null) {
                        voice = shifter.process((short) voice);
                    }
                    // The raw microphone is much quieter than the call's own
                    // voice path, which has gain control the injected sum goes
                    // around: the voice is raised by the user's setting, and
                    // the sum is squeezed rather than cut off where it would
                    // exceed full scale.
                    int sum = soft(clipSample + voice * tenths / 10);
                    out[i] = (byte) sum;
                    out[i + 1] = (byte) (sum >> 8);
                }
                int done = 0;
                while (done < out.length && !session.stopped) {
                    int w = tr.write(out, done, out.length - done);
                    if (w < 0) {
                        throw new IllegalStateException("AudioTrack.write returned " + w);
                    }
                    done += w;
                }
                if (active != null) {
                    active.off += out.length;
                    session.clipFramesDone = active.off / 2;
                    if (active.off >= active.pcm.length) {
                        Clip finished = active;
                        active = null;
                        // A clip handed over meanwhile is not this one's end.
                        if (session.clip.compareAndSet(finished, null)) {
                            long audioMs = finished.pcm.length / 2L * 1000 / ClipBoard.RATE;
                            Ln.i("clip: slot " + finished.slot + " mixed in " + audioMs + " ms ("
                                    + (SystemClock.elapsedRealtime() - started) + " ms real), microphone x"
                                    + tenths / 10.0 + ", pitch " + pitch + ", loudest sample heard " + peak
                                    + " of 32767" + (peak == 0 ? " - SILENT: the caller heard the clip alone" : ""));
                            finished.listener.done(SystemClock.elapsedRealtime() - started, audioMs);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (!session.stopped) {
                Ln.e("clip: voice session failed", t);
                Clip clip = session.clip.get();
                if (clip != null) {
                    clip.listener.failed(t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        } finally {
            session.finished = true;
            session.track = null;
            try {
                mic.stop();
            } catch (Throwable ignored) {
                // not started
            }
            mic.release();
            if (tr != null) {
                try {
                    tr.release();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** Above 24000 the sum is compressed to a quarter, and never past full scale. */
    static int soft(int x) {
        int a = Math.abs(x);
        if (a > 24_000) {
            a = Math.min(32_767, 24_000 + (a - 24_000) / 4);
        }
        return x < 0 ? -a : a;
    }

    /** True once every frame has played; false if stopped or stalled. */
    private static boolean waitPlayed(Session session, AudioTrack tr) {
        long deadline = SystemClock.elapsedRealtime() + session.frames * 1000 / ClipBoard.RATE + 5_000;
        while (!session.stopped && (tr.getPlaybackHeadPosition() & 0xffffffffL) < session.frames) {
            if (SystemClock.elapsedRealtime() > deadline) {
                Ln.w("clip: playback stalled at " + tr.getPlaybackHeadPosition() + " of " + session.frames);
                return false;
            }
            SystemClock.sleep(20);
        }
        return !session.stopped;
    }
}
