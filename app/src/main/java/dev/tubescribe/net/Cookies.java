package dev.tubescribe.net;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loading and storing of browser-exported Netscape cookies.txt files. */
public final class Cookies {
    private static final Logger LOG = Logger.getLogger(Cookies.class.getName());
    private static final int MAX_STORED = 512;

    private Cookies() {
    }

    public static Loaded load(final Path file) throws IOException {
        if (file == null) {
            return new Loaded(List.of(), 0);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Cookies file not found: " + file);
        }
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        final List<Cookie> cookies = new ArrayList<>();
        int youtube = 0;
        int skipped = 0;
        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber).strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean httpOnly = false;
            if (line.startsWith("#HttpOnly_")) {
                httpOnly = true;
                line = line.substring("#HttpOnly_".length());
            } else if (line.startsWith("#")) {
                continue;
            }
            final String[] fields = line.split("\t", -1);
            if (fields.length < 7) {
                skipped++;
                continue;
            }
            try {
                final String domain = fields[0].strip();
                final boolean includeSubdomains = Boolean.parseBoolean(fields[1].strip());
                final String path = fields[2].isBlank() ? "/" : fields[2].strip();
                final boolean secure = Boolean.parseBoolean(fields[3].strip());
                final long expires = Long.parseLong(fields[4].strip());
                final String name = fields[5].strip();
                final String value = fields[6];
                if (domain.isEmpty() || name.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (expires > 0 && expires * 1000L < System.currentTimeMillis()) {
                    continue;
                }
                final Cookie.Builder builder = new Cookie.Builder()
                        .name(name)
                        .value(value)
                        .path(path);
                if (httpOnly) {
                    builder.httpOnly();
                }
                if (includeSubdomains || domain.startsWith(".")) {
                    builder.domain(domain.startsWith(".") ? domain.substring(1) : domain);
                } else {
                    builder.hostOnlyDomain(domain);
                }
                if (secure) {
                    builder.secure();
                }
                if (expires > 0) {
                    builder.expiresAt(expires * 1000L);
                }
                final Cookie cookie = builder.build();
                cookies.add(cookie);
                final String host = cookie.domain().toLowerCase(Locale.ROOT);
                if (host.endsWith("youtube.com") || host.endsWith("google.com")) {
                    youtube++;
                }
            } catch (final IllegalArgumentException e) {
                skipped++;
            }
        }
        LOG.info("Loaded " + cookies.size() + " cookies from " + file
                + " (" + youtube + " YouTube/Google, " + skipped + " skipped)");
        return new Loaded(List.copyOf(cookies), youtube);
    }

    public static CookieJar cookieJar(final Path file) throws IOException {
        return new MemoryJar(load(file).cookies());
    }

    public record Loaded(List<Cookie> cookies, int youtubeCookies) {
    }

    /** In-memory cookie store seeded from a file and updated from responses. */
    public static final class MemoryJar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();

        public MemoryJar() {
        }

        public MemoryJar(final List<Cookie> seed) {
            cookies.addAll(seed);
        }

        public synchronized int size() {
            return cookies.size();
        }

        @Override
        public synchronized void saveFromResponse(final HttpUrl url, final List<Cookie> newCookies) {
            for (final Cookie cookie : newCookies) {
                cookies.removeIf(existing -> existing.name().equals(cookie.name())
                        && existing.domain().equals(cookie.domain())
                        && existing.path().equals(cookie.path()));
                if (cookie.expiresAt() > System.currentTimeMillis()) {
                    cookies.add(cookie);
                }
            }
            if (cookies.size() > MAX_STORED) {
                cookies.subList(0, cookies.size() - MAX_STORED).clear();
            }
        }

        @Override
        public synchronized List<Cookie> loadForRequest(final HttpUrl url) {
            final List<Cookie> matched = new ArrayList<>();
            final long now = System.currentTimeMillis();
            for (final Cookie cookie : cookies) {
                if (cookie.expiresAt() > now && cookie.matches(url)) {
                    matched.add(cookie);
                }
            }
            return matched;
        }
    }

    static {
        // Keep the logger quiet about individual cookies; never log values.
        LOG.setLevel(Level.INFO);
    }
}
