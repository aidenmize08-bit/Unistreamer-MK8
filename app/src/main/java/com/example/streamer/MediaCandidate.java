package com.example.streamer;

import com.example.streamer.net.RequestContext;

final class MediaCandidate {
    enum Kind { HLS, MPEG_TS, MP4, WEBM, DASH }

    final String url;
    final Kind kind;
    final RequestContext context;
    final long observedAt;

    MediaCandidate(String url, Kind kind, RequestContext context) {
        this.url = url;
        this.kind = kind;
        this.context = context;
        this.observedAt = System.currentTimeMillis();
    }

    String label() {
        String host;
        try { host = new java.net.URL(url).getHost(); }
        catch (Exception ignored) { host = "media"; }
        return kind.name().replace('_', ' ') + " • " + host;
    }
}
