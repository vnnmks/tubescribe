package dev.tubescribe.media;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * A sparse on-disk cache for one HTTP byte stream (one DASH track).
 *
 * <p>Playback and the background prefetch both write into the same file: a
 * byte range is only considered present once it has actually been written.
 * The accompanying {@code .idx} file records the downloaded ranges and the
 * stream's total size, so a later session can serve those ranges locally and
 * fetch the rest from YouTube.</p>
 *
 * <p>Writes may be far apart (the user seeks), so the data file is sparse:
 * the gap between two downloaded ranges is never read, and Android's
 * ext4/f2fs filesystems do not allocate blocks for it.</p>
 */
public final class StreamCache {
    /** Flush the index at most every few MiB instead of on every read. */
    private static final long FLUSH_BYTES = 4L * 1024 * 1024;
    private static final String HEADER = "v1";

    private final File dataFile;
    private final File indexFile;
    /** start (inclusive) -> end (exclusive), merged and sorted. */
    private final TreeMap<Long, Long> ranges = new TreeMap<>();
    private long total = -1;
    private long unflushedBytes;

    public StreamCache(final File dataFile) {
        this.dataFile = dataFile;
        this.indexFile = new File(dataFile.getParentFile(), dataFile.getName() + ".idx");
        load();
    }

    public File dataFile() {
        return dataFile;
    }

    public File indexFile() {
        return indexFile;
    }

    /** The stream's full size in bytes, or -1 until an HTTP response reveals it. */
    public synchronized long total() {
        return total;
    }

    public synchronized void setTotal(final long value) {
        if (value <= 0) {
            return;
        }
        if (total != value) {
            total = value;
            trimToTotal();
            // Mark the index dirty even when no bytes are pending, so the new
            // total is persisted instead of being skipped by flushLocked().
            if (unflushedBytes == 0) {
                unflushedBytes = 1;
            }
            flushLocked();
        }
    }

    /** End (exclusive) of the downloaded range containing {@code position}, or -1. */
    public synchronized long availableEnd(final long position) {
        final Map.Entry<Long, Long> entry = ranges.floorEntry(position);
        if (entry != null && entry.getValue() > position) {
            return entry.getValue();
        }
        return -1;
    }

    /** How many bytes from position 0 are continuously present. */
    public synchronized long contiguousEnd() {
        final long end = availableEnd(0);
        return end < 0 ? 0 : end;
    }

    public synchronized long downloadedBytes() {
        long sum = 0;
        for (final Map.Entry<Long, Long> entry : ranges.entrySet()) {
            sum += entry.getValue() - entry.getKey();
        }
        return sum;
    }

    /** True once every byte of a known total size has been downloaded. */
    public synchronized boolean isComplete() {
        if (total <= 0 || !dataFile.isFile() || dataFile.length() < total) {
            return false;
        }
        final Map.Entry<Long, Long> first = ranges.firstEntry();
        return first != null && first.getKey() == 0 && first.getValue() >= total;
    }

    /** Reads cached bytes; the caller must have checked {@link #availableEnd}. */
    public synchronized int read(final RandomAccessFile file, final long position,
                                 final byte[] buffer, final int offset, final int length)
            throws IOException {
        file.seek(position);
        return file.read(buffer, offset, length);
    }

    /** Writes bytes and records them as available. */
    public synchronized void write(final RandomAccessFile file, final long position,
                                   final byte[] buffer, final int offset, final int length)
            throws IOException {
        file.seek(position);
        file.write(buffer, offset, length);
        addRange(position, position + length);
        unflushedBytes += length;
        if (unflushedBytes >= FLUSH_BYTES) {
            flushLocked();
        }
    }

    /** Persists the range index atomically. */
    public synchronized void flush() {
        flushLocked();
    }

    private void flushLocked() {
        if (unflushedBytes == 0 && indexFile.isFile()) {
            return;
        }
        final File temp = new File(indexFile.getParentFile(), indexFile.getName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.newLine();
            out.write("total=" + total);
            out.newLine();
            for (final Map.Entry<Long, Long> entry : ranges.entrySet()) {
                out.write(entry.getKey() + "-" + entry.getValue());
                out.newLine();
            }
        } catch (final IOException e) {
            // A missing index only costs a re-download; never fail playback for it.
            temp.delete();
            return;
        }
        try {
            Files.move(temp.toPath(), indexFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException ignored) {
                temp.delete();
            }
        } catch (final IOException ignored) {
            temp.delete();
        }
        unflushedBytes = 0;
    }

    private void addRange(final long start, final long end) {
        if (end <= start) {
            return;
        }
        long newStart = start;
        long newEnd = end;
        final Map.Entry<Long, Long> before = ranges.floorEntry(newStart);
        if (before != null && before.getValue() >= newStart) {
            newStart = before.getKey();
            newEnd = Math.max(newEnd, before.getValue());
            ranges.remove(before.getKey());
        }
        Map.Entry<Long, Long> next = ranges.ceilingEntry(newStart);
        while (next != null && next.getKey() <= newEnd) {
            newEnd = Math.max(newEnd, next.getValue());
            ranges.remove(next.getKey());
            next = ranges.ceilingEntry(newStart);
        }
        if (total > 0) {
            newEnd = Math.min(newEnd, total);
        }
        if (newEnd > newStart) {
            ranges.put(newStart, newEnd);
        }
    }

    private void trimToTotal() {
        if (total <= 0) {
            return;
        }
        final TreeMap<Long, Long> trimmed = new TreeMap<>();
        for (final Map.Entry<Long, Long> entry : ranges.entrySet()) {
            if (entry.getKey() >= total) {
                continue;
            }
            trimmed.put(entry.getKey(), Math.min(entry.getValue(), total));
        }
        ranges.clear();
        ranges.putAll(trimmed);
    }

    private void load() {
        if (!indexFile.isFile()) {
            return;
        }
        long loadedTotal = -1;
        final TreeMap<Long, Long> loaded = new TreeMap<>();
        try (BufferedReader in = Files.newBufferedReader(indexFile.toPath(), StandardCharsets.UTF_8)) {
            final String header = in.readLine();
            if (!HEADER.equals(header)) {
                return;
            }
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.startsWith("total=")) {
                    try {
                        loadedTotal = Long.parseLong(line.substring(6).strip());
                    } catch (final NumberFormatException ignored) {
                        loadedTotal = -1;
                    }
                } else if (!line.isEmpty()) {
                    final int dash = line.indexOf('-');
                    if (dash <= 0) {
                        continue;
                    }
                    try {
                        final long start = Long.parseLong(line.substring(0, dash).strip());
                        final long end = Long.parseLong(line.substring(dash + 1).strip());
                        if (start >= 0 && end > start) {
                            loaded.put(start, end);
                        }
                    } catch (final NumberFormatException ignored) {
                        // A torn index line is simply ignored.
                    }
                }
            }
        } catch (final IOException e) {
            return;
        }
        final long fileLength = dataFile.isFile() ? dataFile.length() : 0;
        total = loadedTotal;
        for (final Map.Entry<Long, Long> entry : loaded.entrySet()) {
            if (entry.getKey() >= fileLength) {
                continue;
            }
            long end = Math.min(entry.getValue(), fileLength);
            if (total > 0) {
                end = Math.min(end, total);
            }
            addRange(entry.getKey(), end);
        }
        unflushedBytes = 0;
    }

    // ------------------------------------------------------------- static queries

    /** Whether the given data file has a complete, indexed download. */
    public static boolean isComplete(final File dataFile) {
        return new StreamCache(dataFile).isComplete();
    }

    /** The full stream size recorded in the index, or -1. */
    public static long totalBytes(final File dataFile) {
        return new StreamCache(dataFile).total();
    }

    /** Bytes actually downloaded (sum of ranges), or the file size without an index. */
    public static long downloadedBytes(final File dataFile) {
        final StreamCache cache = new StreamCache(dataFile);
        final long downloaded = cache.downloadedBytes();
        return downloaded > 0 ? downloaded : dataFile.length();
    }

    /**
     * Writes an index that marks a fully downloaded file as complete. Used
     * when a complete DASH track was produced by the plain downloader (cache
     * upgrade) rather than by the range data source.
     */
    public static void markComplete(final File dataFile) {
        final StreamCache cache = new StreamCache(dataFile);
        final long length = dataFile.length();
        if (length <= 0) {
            return;
        }
        cache.total = length;
        cache.addRange(0, length);
        cache.unflushedBytes = 1;
        cache.flushLocked();
    }
}
