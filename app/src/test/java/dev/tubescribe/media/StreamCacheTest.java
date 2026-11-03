package dev.tubescribe.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;

/** Range bookkeeping for the sparse DASH stream cache. */
public final class StreamCacheTest {
    private File dir;
    private File data;
    private StreamCache cache;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("stream-cache-test").toFile();
        data = new File(dir, "video.mp4");
        cache = new StreamCache(data);
    }

    @After
    public void tearDown() {
        delete(dir);
    }

    private static void delete(final File file) {
        if (file == null) {
            return;
        }
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void write(final long position, final int length) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(data, "rw")) {
            final byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) ((position + i) & 0xff);
            }
            cache.write(file, position, bytes, 0, length);
        }
    }

    @Test
    public void contiguousWritesBecomeOneRange() throws IOException {
        write(0, 100);
        write(100, 50);

        assertEquals(150, cache.contiguousEnd());
        assertEquals(150, cache.downloadedBytes());
        assertEquals(150, cache.availableEnd(99));
        assertEquals(-1, cache.availableEnd(150));
    }

    @Test
    public void sparseWritesKeepTheirOwnRanges() throws IOException {
        cache.setTotal(1000);
        write(0, 100);
        write(400, 100);

        assertEquals(100, cache.contiguousEnd());
        assertEquals(200, cache.downloadedBytes());
        assertEquals(500, cache.availableEnd(400));
        assertEquals(-1, cache.availableEnd(200));
        assertFalse(cache.isComplete());
    }

    @Test
    public void overlappingWritesMerge() throws IOException {
        cache.setTotal(1000);
        write(100, 100);
        write(0, 150);
        write(200, 50);

        assertEquals(250, cache.contiguousEnd());
        assertEquals(250, cache.downloadedBytes());
        assertEquals(250, cache.availableEnd(249));
    }

    @Test
    public void completeNeedsEveryByteNotJustTheFileLength() throws IOException {
        cache.setTotal(1000);
        write(0, 100);
        write(900, 100);

        // The sparse file is 1000 bytes long, but the middle is missing.
        assertEquals(1000, data.length());
        assertFalse(cache.isComplete());

        write(100, 800);
        assertEquals(1000, cache.contiguousEnd());
        assertTrue(cache.isComplete());
    }

    @Test
    public void indexSurvivesReopen() throws IOException {
        cache.setTotal(1000);
        write(0, 100);
        write(400, 100);
        cache.flush();

        final StreamCache reopened = new StreamCache(data);
        assertEquals(1000, reopened.total());
        assertEquals(100, reopened.contiguousEnd());
        assertEquals(200, reopened.downloadedBytes());
        assertEquals(500, reopened.availableEnd(400));
        assertFalse(reopened.isComplete());
    }

    @Test
    public void staleIndexRangesBeyondTheFileAreDropped() throws IOException {
        cache.setTotal(1000);
        write(0, 100);
        write(400, 100);
        cache.flush();

        // Simulate a data file that lost its tail (e.g. a partial cleanup).
        try (RandomAccessFile file = new RandomAccessFile(data, "rw")) {
            file.setLength(450);
        }

        final StreamCache reopened = new StreamCache(data);
        assertEquals(100, reopened.contiguousEnd());
        assertEquals(150, reopened.downloadedBytes());
        assertEquals(450, reopened.availableEnd(400));
        assertEquals(-1, reopened.availableEnd(450));
    }

    @Test
    public void newTotalIsPersistedEvenWithNoPendingBytes() throws IOException {
        cache.flush(); // Writes an index whose total is still unknown.
        cache.setTotal(1000);

        assertEquals(1000, new StreamCache(data).total());
    }

    @Test
    public void markCompleteIndexesAPlainDownload() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(data, "rw")) {
            file.setLength(4096);
        }

        StreamCache.markComplete(data);

        final StreamCache reopened = new StreamCache(data);
        assertEquals(4096, reopened.total());
        assertEquals(4096, reopened.contiguousEnd());
        assertEquals(4096, reopened.downloadedBytes());
        assertTrue(reopened.isComplete());
    }

    @Test
    public void downloadedBytesFallsBackToFileLengthWithoutIndex() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(data, "rw")) {
            file.setLength(4096);
        }

        assertEquals(4096, StreamCache.downloadedBytes(data));
        assertFalse(StreamCache.isComplete(data));
    }
}
