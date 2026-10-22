package com.example.streamer.net;

import android.webkit.CookieManager;

import com.example.streamer.hls.HlsParser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** HTTP client that preserves the lawful browser session context for media requests. */
public final class HttpFetcher {
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;
    private static final String FALLBACK_UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private final RequestContext context;

    public HttpFetcher(RequestContext context) {
        this.context = context;
    }

    public String text(String url) throws IOException {
        return textResponse(url).text;
    }

    public TextResponse textResponse(String url) throws IOException {
        byte[] bytes;
        String finalUrl;
        try (Response response = open(url, null);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            finalUrl = response.finalUrl;
            byte[] buffer = new byte[32 * 1024];
            int read;
            int total = 0;
            while ((read = response.stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_TEXT_BYTES) throw new IOException("Playlist exceeded safe size limit");
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.contains("#EXTM3U")) {
            throw new IOException("Playlist response was not HLS (the session may have expired)");
        }
        return new TextResponse(finalUrl, text);
    }

    public byte[] bytes(String url, HlsParser.ByteRange range, int maximumBytes) throws IOException {
        try (Response response = open(url, range);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            int total = 0;
            while ((read = response.stream.read(buffer)) >= 0) {
                total += read;
                if (total > maximumBytes) throw new IOException("Response exceeded safe size limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public Response open(String firstUrl, HlsParser.ByteRange range) throws IOException {
        String current = firstUrl;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");

            String targetHost = connection.getURL().getHost();

            for (Map.Entry<String, String> header : context.headers.entrySet()) {
                if (!RequestContext.unsafeToForward(header.getKey())
                        && !header.getKey().equalsIgnoreCase("Cookie")
                        && (!header.getKey().equalsIgnoreCase("Authorization")
                        || sameHost(targetHost, context.mediaHost))) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            if (context.header("User-Agent") == null) {
                connection.setRequestProperty("User-Agent", FALLBACK_UA);
            }
            if (context.header("Referer") == null && context.pageUrl != null) {
                connection.setRequestProperty("Referer", context.pageUrl);
            }
            String cookies = cookiesFor(current);
            if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
            if (range != null) {
                long offset = range.offset == null ? 0 : range.offset;
                connection.setRequestProperty("Range", "bytes=" + offset + "-" + range.endInclusive(offset));
            }

            int status = connection.getResponseCode();
            storeCookies(current, connection.getHeaderFields());
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect had no Location header");
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect();
                throw new IOException("HTTP " + status + " from " + new URL(current).getHost());
            }

            InputStream input = connection.getInputStream();
            // Some servers ignore Range. Slice the full response without silently writing extra bytes.
            if (range != null && status == HttpURLConnection.HTTP_OK) {
                long offset = range.offset == null ? 0 : range.offset;
                skipFully(input, offset);
                input = new BoundedInputStream(input, range.length);
            }
            return new Response(current, connection, input);
        }
        throw new IOException("Too many redirects");
    }

    private String cookiesFor(String url) {
        String live = null;
        try { live = CookieManager.getInstance().getCookie(url); }
        catch (RuntimeException ignored) { }
        if (live != null && !live.isEmpty()) return live;
        try {
            String host = new URL(url).getHost();
            return sameHost(host, context.mediaHost) ? context.cookieSnapshot : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean sameHost(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static void storeCookies(String url, Map<String, List<String>> headers) {
        try {
            CookieManager manager = CookieManager.getInstance();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String cookie : entry.getValue()) manager.setCookie(url, cookie);
            }
            manager.flush();
        } catch (RuntimeException ignored) { }
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) remaining -= skipped;
            else if (input.read() < 0) throw new IOException("Range starts beyond response body");
            else remaining--;
        }
    }

    public static final class Response implements Closeable {
        public final String finalUrl;
        public final InputStream stream;
        private final HttpURLConnection connection;

        Response(String finalUrl, HttpURLConnection connection, InputStream stream) {
            this.finalUrl = finalUrl;
            this.connection = connection;
            this.stream = stream;
        }

        @Override public void close() throws IOException {
            try { stream.close(); }
            finally { connection.disconnect(); }
        }
    }

    public static final class TextResponse {
        public final String finalUrl;
        public final String text;

        TextResponse(String finalUrl, String text) {
            this.finalUrl = finalUrl;
            this.text = text;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
