package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Test;

/** The clips kept for playing into a call: slots, lengths, whole-or-nothing writes. */
public class ClipBoardTest {

    private ClipBoard board;
    private File dir;

    @Before
    public void emptyBoard() throws IOException {
        dir = Files.createTempDirectory("clips").toFile();
        board = new ClipBoard(new File(dir, "clips"));
    }

    private static byte[] pcm(int bytes) {
        byte[] b = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    public void startsEmptyWithTwentySlots() {
        assertEquals(20, ClipBoard.SLOTS);
        String[] lengths = board.lengths().split(",");
        assertEquals(20, lengths.length);
        for (String l : lengths) {
            assertEquals("0", l);
        }
        assertFalse(board.present(0));
    }

    @Test
    public void keepsAClipAndReadsItBack() throws IOException {
        byte[] clip = pcm(9_600);
        board.put(3, new ByteArrayInputStream(clip), clip.length);
        assertTrue(board.present(3));
        assertEquals(9_600, board.length(3));
        assertArrayEquals(clip, board.read(3));
        assertEquals("0,0,0,9600,0", board.lengths().substring(0, 12));
    }

    @Test
    public void replacingASlotReplacesItWhole() throws IOException {
        board.put(0, new ByteArrayInputStream(pcm(4_000)), 4_000);
        board.put(0, new ByteArrayInputStream(pcm(2_000)), 2_000);
        assertEquals(2_000, board.length(0));
    }

    @Test
    public void aCutShortClipLeavesTheOldOneAndNoLeftovers() throws IOException {
        byte[] old = pcm(4_000);
        board.put(1, new ByteArrayInputStream(old), old.length);
        try {
            board.put(1, new ByteArrayInputStream(pcm(100)), 4_000);
            fail("a clip cut short was kept");
        } catch (IOException expected) {
            // the sender gave up half way
        }
        assertArrayEquals(old, board.read(1));
        assertFalse(new File(board.file(1).getPath() + ".new").exists());
    }

    @Test
    public void refusesLengthsThatCannotBeAClip() {
        for (int length : new int[]{0, -2, 3, ClipBoard.MAX_BYTES + 2}) {
            try {
                board.put(2, new ByteArrayInputStream(new byte[8]), length);
                fail("kept a clip of " + length + " bytes");
            } catch (IOException expected) {
                // refused
            }
        }
        assertFalse(board.present(2));
    }

    @Test
    public void aMinuteAtFortyEightKilohertzIsTheLongest() {
        assertEquals(60 * 48_000 * 2, ClipBoard.MAX_BYTES);
    }

    @Test
    public void onlyTheTwentySlotsExist() {
        assertTrue(ClipBoard.validSlot(0));
        assertTrue(ClipBoard.validSlot(19));
        assertFalse(ClipBoard.validSlot(-1));
        assertFalse(ClipBoard.validSlot(20));
        try {
            board.file(20);
            fail("a slot that does not exist had a file");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void deletingEmptiesOneSlotAndDeleteAllEmptiesTheBoard() throws IOException {
        board.put(4, new ByteArrayInputStream(pcm(200)), 200);
        board.put(5, new ByteArrayInputStream(pcm(200)), 200);
        board.delete(4);
        assertFalse(board.present(4));
        assertTrue(board.present(5));
        board.deleteAll();
        assertFalse(board.present(5));
    }

    @Test
    public void readingAnEmptySlotIsAnError() {
        try {
            board.read(7);
            fail("read a clip that is not there");
        } catch (IOException expected) {
            // refused
        }
    }
}
