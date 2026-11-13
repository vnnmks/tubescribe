package dev.tubescribe.media;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import dev.tubescribe.net.NewPipeDownloader;
import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.Fmt;
import dev.tubescribe.util.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Media3 {@link DataSource} that plays a DASH track while downloading it.
 *
 * <p>Bytes already in a {@link StreamCache} are served locally. A read that
 * falls outside the cached ranges opens an HTTP range request at exactly that
 * position and writes the response into the cache as it streams. Seeking is
 * therefore cheap: ExoPlayer asks for the new byte offset and only that part
 * is fetched, even when it is far beyond the prefetched prefix.</p>
 */
public final class StreamCacheDataSource implements DataSource {
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    private final StreamCache cache;
    private final StreamUrl streamUrl;
    private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();

    private DataSpec dataSpec;
    private long position;
    /** Bytes the current open() may still deliver; Long.MAX_VALUE for "until EOF". */
    private long remaining;
    private DataSource upstream;
    private RandomAccessFile file;
    private boolean opened;

    public StreamCacheDataSource(final StreamCache cache, final String url) {
        this(cache, StreamUrl.fixed(url));
    }

    public StreamCacheDataSource(final StreamCache cache, final StreamUrl streamUrl) {
        this.cache = cache;
        this.streamUrl = streamUrl;
    }

    @Override
    public void addTransferListener(final TransferListener transferListener) {
        listeners.add(transferListener);
    }

    @Override
    public long open(final DataSpec spec) throws IOException {
        close();
        dataSpec = spec;
        position = spec.position;
        remaining = spec.length == C.LENGTH_UNSET ? Long.MAX_VALUE : spec.length;
        opened = true;
        final long knownTotal = cache.total();
        if (knownTotal > 0) {
            remaining = Math.min(remaining, Math.max(0, knownTotal - position));
        }
        if (remaining == 0) {
            return 0;
        }
        if (cache.availableEnd(position) <= position) {
            openUpstream();
            final long total = cache.total();
            if (total > 0) {
                remaining = Math.min(remaining, Math.max(0, total - position));
            }
        }
        // The contract asks for the bytes readable from here: a real number
        // when known, C.LENGTH_UNSET otherwise. Returning 0 would tell
        // ExoPlayer the stream is empty.
        return remaining == Long.MAX_VALUE ? C.LENGTH_UNSET : remaining;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        if (!opened || length == 0) {
            return length == 0 ? 0 : C.RESULT_END_OF_INPUT;
        }
        while (remaining > 0) {
            if (upstream != null) {
                return readUpstream(buffer, offset, length);
            }
            final long cachedEnd = cache.availableEnd(position);
            if (cachedEnd > position) {
                final int wanted = (int) Math.min(length, Math.min(remaining, cachedEnd - position));
                final int read = cache.read(file(), position, buffer, offset, wanted);
                if (read > 0) {
                    position += read;
                    remaining -= read;
                    return read;
                }
            }
            openUpstream();
            if (upstream == null) {
                break;
            }
        }
        remaining = 0;
        return C.RESULT_END_OF_INPUT;
    }

    private int readUpstream(final byte[] buffer, final int offset, final int length)
            throws IOException {
        final int wanted = (int) Math.min(length, remaining);
        final int read = upstream.read(buffer, offset, wanted);
        if (read == C.RESULT_END_OF_INPUT) {
            if (cache.total() <= 0) {
                cache.setTotal(position);
            }
            closeUpstream();
            remaining = 0;
            return C.RESULT_END_OF_INPUT;
        }
        cache.write(file(), position, buffer, offset, read);
        position += read;
        remaining -= read;
        if (remaining <= 0) {
            closeUpstream();
        }
        return read;
    }

    private void openUpstream() throws IOException {
        if (cache.total() > 0 && position >= cache.total()) {
            return;
        }
        final long length = remaining == Long.MAX_VALUE ? C.LENGTH_UNSET : remaining;
        // The URL is only resolved here, on the first byte that is not cached.
        String target = streamUrl.get();
        boolean refreshed = false;
        DataSource source;
        while (true) {
            source = newUpstreamFactory().createDataSource();
            for (final TransferListener listener : listeners) {
                source.addTransferListener(listener);
            }
            try {
                source.open(new DataSpec(Uri.parse(target), position, length));
                break;
            } catch (final IOException e) {
                source.close();
                // A stale link (403/410) gets one fresh URL before giving up.
                final String fresh = !refreshed && isExpired(e) ? streamUrl.refresh() : null;
                if (fresh == null) {
                    throw e;
                }
                refreshed = true;
                target = fresh;
            }
        }
        upstream = source;
        final Map<String, List<String>> headers = source.getResponseHeaders();
        long total = parseTotal(headers);
        if (total <= 0) {
            final long contentLength = parseContentLength(headers);
            if (contentLength > 0) {
                // A 206 without Content-Range is unusual; treat the response
                // as the remainder. A 200 response is the whole file.
                final int code = source instanceof HttpDataSource
                        ? ((HttpDataSource) source).getResponseCode() : 0;
                total = code == 200 ? contentLength : position + contentLength;
            }
        }
        if (total > 0) {
            final long known = cache.total();
            if (known > 0 && known != total) {
                // The cached bytes came from another stream; mixing them with
                // this one would corrupt the file.
                closeUpstream();
                throw new IOException("YouTube now serves a different stream than the cached "
                        + "part of this video. Fetch it again from its link to refresh the cache.");
            }
            cache.setTotal(total);
        }
    }

    private static boolean isExpired(final IOException e) {
        if (e instanceof HttpDataSource.InvalidResponseCodeException) {
            final int code = ((HttpDataSource.InvalidResponseCodeException) e).responseCode;
            return code == 403 || code == 410;
        }
        return false;
    }

    private RandomAccessFile file() throws IOException {
        if (file == null) {
            final File parent = cache.dataFile().getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            file = new RandomAccessFile(cache.dataFile(), "rw");
        }
        return file;
    }

    private void closeUpstream() {
        if (upstream != null) {
            try {
                upstream.close();
            } catch (final IOException ignored) {
                // Nothing useful to do; the next open creates a fresh source.
            }
            upstream = null;
        }
    }

    @Override
    public Uri getUri() {
        return dataSpec == null ? null : dataSpec.uri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream == null ? Collections.emptyMap() : upstream.getResponseHeaders();
    }

    @Override
    public void close() {
        closeUpstream();
        if (file != null) {
            try {
                file.close();
            } catch (final IOException ignored) {
                // The index is still usable even if the handle did not close.
            }
            file = null;
        }
        cache.flush();
        opened = false;
    }

    private static long parseTotal(final Map<String, List<String>> headers) {
        if (headers == null) {
            return -1;
        }
        final List<String> values = headers.get("Content-Range");
        final List<String> fallback = values == null ? headers.get("content-range") : values;
        if (fallback == null || fallback.isEmpty()) {
            return -1;
        }
        final Matcher matcher = CONTENT_RANGE.matcher(fallback.get(0));
        if (!matcher.find() || "*".equals(matcher.group(3))) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(3));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static long parseContentLength(final Map<String, List<String>> headers) {
        if (headers == null) {
            return -1;
        }
        final List<String> values = headers.get("Content-Length");
        final List<String> fallback = values == null ? headers.get("content-length") : values;
        if (fallback == null || fallback.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(fallback.get(0));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    static DefaultHttpDataSource.Factory newUpstreamFactory() {
        return new DefaultHttpDataSource.Factory()
                .setUserAgent(NewPipeDownloader.USER_AGENT)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(60_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(Map.of(
                        "Referer", "https://www.youtube.com/",
                        "Accept", "*/*",
                        "Accept-Language", "en-US,en;q=0.9"));
    }

    /**
     * Downloads the first {@code maxBytes} of a stream into the cache,
     * resuming from the current contiguous prefix. This is the pipeline's
     * "some video is already on disk" head start before the player opens.
     */
    public static void prefetch(final String url, final StreamCache cache, final long maxBytes,
                                final ProgressListener listener, final Cancellation cancel)
            throws IOException {
        final long start = cache.contiguousEnd();
        if (cache.isComplete() || start >= maxBytes) {
            listener.update(1.0, "Video ready");
            return;
        }
        final long target = cache.total() > 0 ? Math.min(maxBytes, cache.total()) : maxBytes;
        final StreamCacheDataSource source = new StreamCacheDataSource(cache, url);
        try {
            source.open(new DataSpec(Uri.parse(url), start, Math.max(1, target - start)));
            final byte[] buffer = new byte[64 * 1024];
            long lastReport = -1;
            while (true) {
                cancel.throwIfCancelled();
                final int read = source.read(buffer, 0, buffer.length);
                if (read == C.RESULT_END_OF_INPUT) {
                    break;
                }
                final long done = cache.downloadedBytes();
                if (done - lastReport >= 512 * 1024 || cache.isComplete()) {
                    lastReport = done;
                    final double fraction = target <= 0 ? 1.0
                            : Math.min(0.999, done / (double) target);
                    listener.update(fraction, "Buffering video · " + Fmt.bytes(done)
                            + (cache.total() > 0 ? " / " + Fmt.bytes(cache.total()) : ""));
                }
            }
        } finally {
            source.close();
        }
        listener.update(1.0, cache.isComplete() ? "Video ready"
                : "Video ready · streaming");
    }

    /** One factory per track; all data sources share the same range cache. */
    public static final class Factory implements DataSource.Factory {
        private final StreamCache cache;
        private final StreamUrl url;

        public Factory(final File dataFile, final String url) {
            this(new StreamCache(dataFile), StreamUrl.fixed(url));
        }

        public Factory(final File dataFile, final StreamUrl url) {
            this(new StreamCache(dataFile), url);
        }

        public Factory(final StreamCache cache, final StreamUrl url) {
            this.cache = cache;
            this.url = url;
        }

        public StreamCache cache() {
            return cache;
        }

        @Override
        public DataSource createDataSource() {
            return new StreamCacheDataSource(cache, url);
        }
    }
}
