package dev.tubescribe.net;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * NewPipeExtractor's HTTP bridge, backed by OkHttp.
 *
 * <p>NewPipe performs a mix of GET, HEAD and POST requests while extracting a
 * YouTube page. The downloader keeps cookies for the lifetime of the process,
 * which matters for YouTube's consent and bot-check interstitials.</p>
 */
public final class NewPipeDownloader extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    private final OkHttpClient client;

    public NewPipeDownloader() {
        this(new Cookies.MemoryJar());
    }

    public NewPipeDownloader(final CookieJar cookieJar) {
        this(new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(cookieJar == null ? new Cookies.MemoryJar() : cookieJar)
                .build());
    }

    public NewPipeDownloader(final OkHttpClient client) {
        this.client = client;
    }

    @Override
    public Response execute(final Request request) throws IOException {
        final String method = request.httpMethod().toUpperCase(Locale.ROOT);
        final okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(request.url())
                .header("User-Agent", USER_AGENT);

        String contentType = null;
        for (final Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            final String name = header.getKey();
            for (final String value : header.getValue()) {
                builder.addHeader(name, value);
            }
            if ("Content-Type".equalsIgnoreCase(name) && !header.getValue().isEmpty()) {
                contentType = header.getValue().get(0);
            }
        }

        final byte[] data = request.dataToSend();
        if (data != null) {
            final MediaType mediaType = contentType == null ? null : MediaType.parse(contentType);
            builder.method(method, RequestBody.create(data, mediaType));
        } else {
            builder.method(method, null);
        }

        try (okhttp3.Response raw = client.newCall(builder.build()).execute()) {
            final String body = raw.body() == null ? "" : raw.body().string();
            final Map<String, List<String>> headers = new HashMap<>(raw.headers().toMultimap());
            return new Response(
                    raw.code(),
                    raw.message(),
                    headers,
                    body,
                    raw.request().url().toString());
        }
    }

    /** Builds the default OkHttp client used for extraction. */
    public static OkHttpClient client(final CookieJar cookieJar) {
        return new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(cookieJar == null ? new Cookies.MemoryJar() : cookieJar)
                .build();
    }

    static List<Cookie> emptyCookies() {
        return List.of();
    }

    static HttpUrl parse(final String url) {
        return HttpUrl.parse(url);
    }
}
