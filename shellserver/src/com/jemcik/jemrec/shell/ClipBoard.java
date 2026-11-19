package com.jemcik.jemrec.shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The clips the user plays into a call by hand (see ClipPlayer): up to
 * SLOTS of them, each raw 16-bit little-endian mono PCM at 48 kHz - the
 * greeting's format, so one player and one set of tracks serve both.
 *
 * Kept by the recorder, beside the greeting in /data/local/tmp, because the
 * recorder is what plays them: the app holds the master copies and sends them
 * over, as it does the greeting (CaptureDaemon.syncClips), and a recorder that
 * is replaced is given them again.
 *
 * Plain Java with the directory handed in, so the tests can run it off the
 * phone. Each slot is a file of its own: "clip-N.pcm".
 */
final class ClipBoard {

    static final int SLOTS = 20;
    /** The greeting's rate (GreetingPlayer.RATE), written out so this class needs no Android. */
    static final int RATE = 48_000;
    /** A minute at most: longer is a recording, not a line to say in a call. */
    static final long MAX_MS = 60_000L;
    static final int MAX_BYTES = (int) (MAX_MS * RATE / 1000 * 2);
    static final File DIR = new File("/data/local/tmp/jemrec-clips");

    private final File dir;

    ClipBoard(File dir) {
        this.dir = dir;
    }

    static boolean validSlot(int slot) {
        return slot >= 0 && slot < SLOTS;
    }

    File file(int slot) {
        if (!validSlot(slot)) {
            throw new IllegalArgumentException("no clip slot " + slot);
        }
        return new File(dir, "clip-" + slot + ".pcm");
    }

    /** Bytes in a slot, 0 when it is empty. */
    long length(int slot) {
        File f = file(slot);
        return f.isFile() ? f.length() : 0;
    }

    boolean present(int slot) {
        return length(slot) > 0;
    }

    /** Every slot's length in bytes, comma-separated, as 'Q' answers. */
    String lengths() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(length(i));
        }
        return sb.toString();
    }

    /** Replace a slot with [length] bytes from [in]; written whole or not at all. */
    void put(int slot, InputStream in, int length) throws IOException {
        File target = file(slot);
        if (length <= 0 || length > MAX_BYTES || (length & 1) != 0) {
            throw new IOException("implausible clip length " + length);
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("could not make " + dir);
        }
        File next = new File(target.getPath() + ".new");
        try (OutputStream out = new FileOutputStream(next)) {
            byte[] buf = new byte[64 * 1024];
            int left = length;
            while (left > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, left));
                if (n < 0) {
                    throw new IOException("clip cut short, " + left + " bytes missing");
                }
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            next.delete();
            throw e;
        }
        if (!next.renameTo(target)) {
            next.delete();
            throw new IOException("could not store the clip");
        }
    }

    void delete(int slot) {
        file(slot).delete();
    }

    void deleteAll() {
        for (int i = 0; i < SLOTS; i++) {
            file(i).delete();
            new File(file(i).getPath() + ".new").delete();
        }
        dir.delete();
    }

    /** The clip as stored. */
    byte[] read(int slot) throws IOException {
        File f = file(slot);
        long length = f.isFile() ? f.length() : 0;
        if (length <= 0 || length > MAX_BYTES) {
            throw new IOException("no usable clip in slot " + slot + " (" + length + " bytes)");
        }
        byte[] pcm = new byte[(int) length & ~1];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < pcm.length) {
                int n = in.read(pcm, off, pcm.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return pcm;
    }
}
