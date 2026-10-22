package com.example.streamer.net;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.net.URI;

/** Immutable browser request context captured with a media URL. */
public final class RequestContext {
    public final Map<String, String> headers;
    public final String pageUrl;
    public final String cookieSnapshot;
    public final String mediaHost;

    public RequestContext(Map<String, String> headers, String pageUrl, String cookieSnapshot,
                          String mediaUrl) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    safe.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.headers = Collections.unmodifiableMap(safe);
        this.pageUrl = pageUrl;
        this.cookieSnapshot = cookieSnapshot;
        this.mediaHost = hostOf(mediaUrl);
    }

    public String header(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    public static boolean unsafeToForward(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("host") || lower.equals("content-length")
                || lower.equals("connection") || lower.equals("accept-encoding")
                || lower.equals("range") || lower.startsWith("sec-fetch-");
    }

    private static String hostOf(String value) {
        try { return value == null ? null : new URI(value).getHost(); }
        catch (Exception ignored) { return null; }
    }
}
