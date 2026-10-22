package com.example.streamer.hls;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dependency-free HLS parser. Network access and storage deliberately live elsewhere. */
public final class HlsParser {
    public static final class ByteRange {
        public final long length;
        public final Long offset;

        public ByteRange(long length, Long offset) {
            if (length <= 0) throw new IllegalArgumentException("Byte range length must be positive");
            this.length = length;
            this.offset = offset;
        }

        public long endInclusive(long resolvedOffset) {
            return resolvedOffset + length - 1;
        }
    }

    public static final class Key {
        public final String method;
        public final String uri;
        public final String iv;
        public final String keyFormat;

        Key(String method, String uri, String iv, String keyFormat) {
            this.method = method;
            this.uri = uri;
            this.iv = iv;
            this.keyFormat = keyFormat;
        }

        public boolean isNone() { return "NONE".equalsIgnoreCase(method); }
        public boolean isAes128() {
            return "AES-128".equalsIgnoreCase(method)
                    && (keyFormat == null || "identity".equalsIgnoreCase(keyFormat));
        }
    }

    public static final class MapSegment {
        public final String uri;
        public final ByteRange byteRange;
        public final Key key;

        MapSegment(String uri, ByteRange byteRange, Key key) {
            this.uri = uri;
            this.byteRange = byteRange;
            this.key = key;
        }
    }

    public static final class Segment {
        public final String uri;
        public final double durationSeconds;
        public final long sequence;
        public final ByteRange byteRange;
        public final Key key;
        public final MapSegment initialization;
        public final boolean discontinuity;

        Segment(String uri, double durationSeconds, long sequence, ByteRange byteRange,
                Key key, MapSegment initialization, boolean discontinuity) {
            this.uri = uri;
            this.durationSeconds = durationSeconds;
            this.sequence = sequence;
            this.byteRange = byteRange;
            this.key = key;
            this.initialization = initialization;
            this.discontinuity = discontinuity;
        }
    }

    public static final class MediaPlaylist {
        public final String sourceUrl;
        public final List<Segment> segments;
        public final MapSegment initialization;
        public final boolean endList;

        MediaPlaylist(String sourceUrl, List<Segment> segments, MapSegment initialization,
                      boolean endList) {
            this.sourceUrl = sourceUrl;
            this.segments = Collections.unmodifiableList(segments);
            this.initialization = initialization;
            this.endList = endList;
        }

        public double durationSeconds() {
            double total = 0;
            for (Segment segment : segments) total += segment.durationSeconds;
            return total;
        }
    }

    public static final class Variant {
        public final String uri;
        public final long bandwidth;
        public final long averageBandwidth;
        public final String codecs;
        public final String resolution;
        public final String audioGroup;

        Variant(String uri, Map<String, String> attributes) {
            this.uri = uri;
            bandwidth = parseLong(attributes.get("BANDWIDTH"), 0);
            averageBandwidth = parseLong(attributes.get("AVERAGE-BANDWIDTH"), 0);
            codecs = attributes.get("CODECS");
            resolution = attributes.get("RESOLUTION");
            audioGroup = attributes.get("AUDIO");
        }

        public long selectionBandwidth() {
            return averageBandwidth > 0 ? averageBandwidth : bandwidth;
        }

        public boolean declaresAudioCodec() {
            if (codecs == null) return false;
            String lower = codecs.toLowerCase(Locale.ROOT);
            return lower.contains("mp4a") || lower.contains("ac-3")
                    || lower.contains("ec-3") || lower.contains("opus");
        }
    }

    public static final class Rendition {
        public final String type;
        public final String groupId;
        public final String name;
        public final String language;
        public final String uri;
        public final boolean isDefault;
        public final boolean autoselect;

        Rendition(Map<String, String> attributes) {
            type = attributes.get("TYPE");
            groupId = attributes.get("GROUP-ID");
            name = attributes.get("NAME");
            language = attributes.get("LANGUAGE");
            uri = attributes.get("URI");
            isDefault = yes(attributes.get("DEFAULT"));
            autoselect = yes(attributes.get("AUTOSELECT"));
        }
    }

    public static final class MasterPlaylist {
        public final String sourceUrl;
        public final List<Variant> variants;
        public final List<Rendition> renditions;

        MasterPlaylist(String sourceUrl, List<Variant> variants, List<Rendition> renditions) {
            this.sourceUrl = sourceUrl;
            this.variants = Collections.unmodifiableList(variants);
            this.renditions = Collections.unmodifiableList(renditions);
        }

        public Variant bestVariant() {
            Variant best = null;
            for (Variant variant : variants) {
                if (best == null || variant.selectionBandwidth() > best.selectionBandwidth()) best = variant;
            }
            if (best == null) throw new IllegalArgumentException("No playable HLS variants");
            return best;
        }

        public Rendition audioFor(Variant variant, Locale preferredLocale) {
            List<Rendition> matching = new ArrayList<>();
            for (Rendition rendition : renditions) {
                if (!"AUDIO".equalsIgnoreCase(rendition.type) || rendition.uri == null) continue;
                if (variant.audioGroup == null || variant.audioGroup.equals(rendition.groupId)) {
                    matching.add(rendition);
                }
            }
            // Some providers emit a stale/mismatched AUDIO group. If the variant declares no
            // audio codec, falling back to a default audio rendition is safer than silent video.
            if (matching.isEmpty() && !variant.declaresAudioCodec()) {
                for (Rendition rendition : renditions) {
                    if ("AUDIO".equalsIgnoreCase(rendition.type) && rendition.uri != null) {
                        matching.add(rendition);
                    }
                }
            }
            if (matching.isEmpty()) return null;
            String language = preferredLocale == null ? null : preferredLocale.getLanguage();
            if (language != null && !language.isEmpty()) {
                for (Rendition rendition : matching) {
                    if (rendition.language != null
                            && rendition.language.toLowerCase(Locale.ROOT)
                            .startsWith(language.toLowerCase(Locale.ROOT))) return rendition;
                }
            }
            for (Rendition rendition : matching) if (rendition.isDefault) return rendition;
            for (Rendition rendition : matching) if (rendition.autoselect) return rendition;
            return matching.get(0);
        }
    }

    public static final class Parsed {
        public final MasterPlaylist master;
        public final MediaPlaylist media;

        Parsed(MasterPlaylist master, MediaPlaylist media) {
            this.master = master;
            this.media = media;
        }

        public boolean isMaster() { return master != null; }
    }

    private HlsParser() {}

    public static Parsed parse(String text, String sourceUrl) {
        if (text == null || !text.contains("#EXTM3U")) {
            throw new IllegalArgumentException("The response is not an HLS playlist");
        }
        List<String> lines = normalizedLines(text);
        boolean master = false;
        for (String line : lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) { master = true; break; }
        }
        return master ? new Parsed(parseMaster(lines, sourceUrl), null)
                : new Parsed(null, parseMedia(lines, sourceUrl));
    }

    private static MasterPlaylist parseMaster(List<String> lines, String sourceUrl) {
        List<Variant> variants = new ArrayList<>();
        List<Rendition> renditions = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("#EXT-X-MEDIA:")) {
                renditions.add(new Rendition(parseAttributes(afterColon(line))));
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> attributes = parseAttributes(afterColon(line));
                String uri = nextUri(lines, i + 1);
                if (uri != null) variants.add(new Variant(resolve(sourceUrl, uri), attributes));
            }
        }
        if (variants.isEmpty()) throw new IllegalArgumentException("Master playlist has no variants");
        List<Rendition> resolved = new ArrayList<>();
        for (Rendition rendition : renditions) {
            if (rendition.uri == null) {
                resolved.add(rendition);
            } else {
                Map<String, String> copy = new LinkedHashMap<>();
                copy.put("TYPE", rendition.type);
                copy.put("GROUP-ID", rendition.groupId);
                copy.put("NAME", rendition.name);
                copy.put("LANGUAGE", rendition.language);
                copy.put("URI", resolve(sourceUrl, rendition.uri));
                copy.put("DEFAULT", rendition.isDefault ? "YES" : "NO");
                copy.put("AUTOSELECT", rendition.autoselect ? "YES" : "NO");
                resolved.add(new Rendition(copy));
            }
        }
        return new MasterPlaylist(sourceUrl, variants, resolved);
    }

    private static MediaPlaylist parseMedia(List<String> lines, String sourceUrl) {
        List<Segment> segments = new ArrayList<>();
        long sequence = 0;
        double pendingDuration = 0;
        ByteRange pendingRange = null;
        long nextImplicitOffset = 0;
        Key key = null;
        MapSegment initialization = null;
        boolean discontinuity = false;
        boolean endList = false;

        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = parseLong(afterColon(line), 0);
            } else if (line.startsWith("#EXTINF:")) {
                String raw = afterColon(line);
                int comma = raw.indexOf(',');
                pendingDuration = parseDouble(comma < 0 ? raw : raw.substring(0, comma), 0);
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                pendingRange = parseByteRange(afterColon(line));
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attributes = parseAttributes(afterColon(line));
                String method = attributes.get("METHOD");
                key = new Key(method == null ? "NONE" : method,
                        resolveNullable(sourceUrl, attributes.get("URI")), attributes.get("IV"),
                        attributes.get("KEYFORMAT"));
                if (key.isNone()) key = null;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> attributes = parseAttributes(afterColon(line));
                String uri = attributes.get("URI");
                if (uri == null) throw new IllegalArgumentException("EXT-X-MAP has no URI");
                ByteRange range = attributes.get("BYTERANGE") == null ? null
                        : parseByteRange(attributes.get("BYTERANGE"));
                initialization = new MapSegment(resolve(sourceUrl, uri), range, key);
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                discontinuity = true;
            } else if (line.equals("#EXT-X-ENDLIST")) {
                endList = true;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                ByteRange resolvedRange = pendingRange;
                if (resolvedRange != null) {
                    long offset = resolvedRange.offset == null ? nextImplicitOffset : resolvedRange.offset;
                    resolvedRange = new ByteRange(resolvedRange.length, offset);
                    nextImplicitOffset = offset + resolvedRange.length;
                } else {
                    nextImplicitOffset = 0;
                }
                segments.add(new Segment(resolve(sourceUrl, line), pendingDuration, sequence++,
                        resolvedRange, key, initialization, discontinuity));
                pendingDuration = 0;
                pendingRange = null;
                discontinuity = false;
            }
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("Media playlist has no segments");
        return new MediaPlaylist(sourceUrl, segments, initialization, endList);
    }

    public static Map<String, String> parseAttributes(String input) {
        Map<String, String> result = new LinkedHashMap<>();
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i <= input.length(); i++) {
            char c = i == input.length() ? ',' : input.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == ',' && !quoted) {
                String part = input.substring(start, i).trim();
                int equals = part.indexOf('=');
                if (equals > 0) {
                    String key = part.substring(0, equals).trim().toUpperCase(Locale.ROOT);
                    String value = unquote(part.substring(equals + 1).trim());
                    result.put(key, value);
                }
                start = i + 1;
            }
        }
        return result;
    }

    private static ByteRange parseByteRange(String raw) {
        String value = unquote(raw.trim());
        int at = value.indexOf('@');
        long length = Long.parseLong(at < 0 ? value : value.substring(0, at));
        Long offset = at < 0 ? null : Long.parseLong(value.substring(at + 1));
        return new ByteRange(length, offset);
    }

    private static List<String> normalizedLines(String text) {
        String[] raw = text.replace("\r", "").split("\n");
        List<String> lines = new ArrayList<>(raw.length);
        for (String line : raw) lines.add(line.trim());
        return lines;
    }

    private static String nextUri(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty() && !line.startsWith("#")) return line;
            if (line.startsWith("#EXT-X-STREAM-INF:")) return null;
        }
        return null;
    }

    public static String resolve(String base, String child) {
        try {
            return new URI(base).resolve(child).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid playlist URL", e);
        }
    }

    private static String resolveNullable(String base, String child) {
        return child == null ? null : resolve(base, child);
    }

    private static String afterColon(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean yes(String value) { return "YES".equalsIgnoreCase(value); }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static double parseDouble(String value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
