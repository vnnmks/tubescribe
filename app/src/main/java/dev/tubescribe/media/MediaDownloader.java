package dev.tubescribe.media;

import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.Fmt;
import dev.tubescribe.util.ProgressListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resumable googlevideo downloader.
 *
 * <p>Downloads in 10 MiB HTTP range chunks: this both survives interrupted
 * runs and avoids the throttling YouTube applies to single long responses.</p>
 */
public final class MediaDownloader {
    private static final Logger LOG = Logger.getLogger(MediaDownloader.class.getName());
    private static final long CHUNK = 10L * 1024 * 1024;
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client;

    public MediaDownloader() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * Downloads {@code url} to {@code target}, reusing an existing target or
     * resuming a {@code .part} file when possible.
     */
    public void download(final String url, final Path target, final String label,
                         final ProgressListener listener, final Cancellation cancel)
            throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            listener.update(1.0, label + " already downloaded (" + Fmt.bytes(Files.size(target)) + ")");
            return;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        final Path part = target.resolveSibling(target.getFileName() + ".part");
        if (Files.isRegularFile(part) && Files.size(part) == 0) {
            Files.deleteIfExists(part);
        }

        long total = -1;
        long offset = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (offset > 0) {
            listener.update(-1, "Resuming " + label + " at " + Fmt.bytes(offset));
            LOG.info("Resuming " + label + " at " + offset + " bytes");
        }

        int consecutiveFailures = 0;
        boolean complete = false;
        while (!complete) {
            cancel.throwIfCancelled();
            if (total > 0 && offset >= total) {
                complete = true;
                break;
            }
            final long requestStart = offset;
            final long rangeEnd = total > 0
                    ? Math.min(total - 1, requestStart + CHUNK - 1)
                    : requestStart + CHUNK - 1;
            final long requestLength = rangeEnd - requestStart + 1;
            final Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", dev.tubescribe.net.NewPipeDownloader.USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.youtube.com/")
                    .header("Range", "bytes=" + offset + "-" + rangeEnd)
                    .build();

            final okhttp3.Call call = client.newCall(request);
            final Runnable cancelHook = call::cancel;
            final Runnable removeHook = cancel.addHook(cancelHook);
            boolean responseWasFull = false;
            try (Response response = call.execute()) {
                final int code = response.code();
                if (code == 416) {
                    if (total > 0 && offset >= total) {
                        complete = true;
                        break;
                    }
                    LOG.warning("Server rejected resume offset " + offset + "; restarting download");
                    Files.deleteIfExists(part);
                    offset = 0;
                    total = -1;
                    continue;
                }
                if (code != 200 && code != 206) {
                    final String detail = response.body() == null
                            ? "" : truncate(response.body().string(), 300);
                    throw new MediaException("Media download failed with HTTP " + code
                            + (detail.isBlank() ? "" : ": " + detail)
                            + ". The stream URL may have expired; try the link again.");
                }
                if (code == 200) {
                    responseWasFull = true;
                    if (offset > 0) {
                        LOG.info("Server ignored the range request; restarting download");
                        offset = 0;
                    }
                }
                final ResponseBody body = response.body();
                if (body == null) {
                    throw new MediaException("Media server returned an empty response.");
                }
                if (total <= 0) {
                    total = parseContentRangeTotal(response.header("Content-Range"));
                    if (total <= 0 && body.contentLength() >= 0) {
                        total = body.contentLength() + (code == 206 ? offset : 0);
                    }
                }

                final long reportEvery = 250L * 1_000_000;
                long lastReport = System.nanoTime() - reportEvery;
                final byte[] buffer = new byte[64 * 1024];
                long writtenThisResponse = 0;
                try (InputStream in = body.byteStream();
                     OutputStream out = new BufferedOutputStream(
                             Files.newOutputStream(part, java.nio.file.StandardOpenOption.CREATE,
                                     offset == 0 ? java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                                             : java.nio.file.StandardOpenOption.APPEND),
                             64 * 1024)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        cancel.throwIfCancelled();
                        out.write(buffer, 0, read);
                        offset += read;
                        writtenThisResponse += read;
                        final long now = System.nanoTime();
                        if (now - lastReport >= reportEvery || (total > 0 && offset >= total)) {
                            lastReport = now;
                            final double fraction = total > 0
                                    ? Math.min(1.0, offset / (double) total) : -1;
                            listener.update(fraction, label + " · " + Fmt.bytes(offset)
                                    + (total > 0 ? " / " + Fmt.bytes(total) : ""));
                        }
                    }
                }
                consecutiveFailures = 0;
                if (responseWasFull) {
                    // A 200 response contains the entire remainder; there are no chunks left.
                    complete = true;
                } else if (writtenThisResponse == 0) {
                    throw new MediaException("Media server returned no data for the requested range.");
                } else if (total <= 0) {
                    // No length information: stop when a short range was returned.
                    complete = writtenThisResponse < requestLength;
                }
            } catch (final MediaException e) {
                throw e;
            } catch (final IOException e) {
                if (cancel.isCancelled()) {
                    cancel.throwIfCancelled();
                }
                consecutiveFailures++;
                final long current = Files.isRegularFile(part) ? Files.size(part) : 0;
                LOG.log(Level.WARNING, "Download chunk failed at " + current + " bytes ("
                        + consecutiveFailures + ")", e);
                if (consecutiveFailures >= 4) {
                    throw new MediaException("Download failed after " + consecutiveFailures
                            + " attempts: " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(500L * consecutiveFailures);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new MediaException("Download interrupted.", interrupted);
                }
            } finally {
                removeHook.run();
            }
        }

        final long size = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (size == 0) {
            throw new MediaException("Downloaded file is empty.");
        }
        if (total > 0 && size < total) {
            throw new MediaException("Download ended early: " + Fmt.bytes(size)
                    + " of " + Fmt.bytes(total) + ".");
        }
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        listener.update(1.0, label + " ready (" + Fmt.bytes(size) + ")");
        LOG.info("Downloaded " + label + " to " + target + " (" + size + " bytes)");
    }

    static long parseContentRangeTotal(final String header) {
        if (header == null) {
            return -1;
        }
        final Matcher matcher = CONTENT_RANGE.matcher(header);
        if (!matcher.find() || "*".equals(matcher.group(3))) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(3));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static String truncate(final String value, final int max) {
        final String cleaned = value.replaceAll("\\s+", " ").strip();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
    }

    public void cancelAll() {
        client.dispatcher().cancelAll();
    }

    static {
        // Access the locale class so static analysis sees intended formatting behavior.
        Locale.getDefault();
    }
}
