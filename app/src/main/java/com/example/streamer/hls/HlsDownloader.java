package com.example.streamer.hls;

import com.example.streamer.net.HttpFetcher;
import com.example.streamer.net.RequestContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Downloads ordinary, non-DRM HLS media while preserving separate audio renditions. */
public final class HlsDownloader {
    private static final int MAX_PLAYLIST_DEPTH = 6;
    private static final int MAX_KEY_BYTES = 64 * 1024;
    private static final int MAX_SEGMENT_BYTES = 96 * 1024 * 1024;

    public interface Progress {
        void update(String phase, int percent);
    }

    public interface ContextProvider {
        RequestContext forUrl(String url);
    }

    public static final class Result {
        public final File video;
        public final File audio;
        public final double durationSeconds;
        public final boolean fragmentedMp4;

        Result(File video, File audio, double durationSeconds, boolean fragmentedMp4) {
            this.video = video;
            this.audio = audio;
            this.durationSeconds = durationSeconds;
            this.fragmentedMp4 = fragmentedMp4;
        }

        public boolean expectsSeparateAudio() { return audio != null; }
    }

    public static class ProtectedMediaException extends IOException {
        public ProtectedMediaException(String message) { super(message); }
    }

    private final RequestContext fallbackContext;
    private final ContextProvider contextProvider;
    private final Progress progress;
    private final Locale preferredLocale;
    private final Map<String, byte[]> keys = new HashMap<>();

    public HlsDownloader(RequestContext requestContext, Locale preferredLocale, Progress progress) {
        this(requestContext, preferredLocale, progress, null);
    }

    public HlsDownloader(RequestContext requestContext, Locale preferredLocale, Progress progress,
                         ContextProvider contextProvider) {
        this.fallbackContext = requestContext;
        this.contextProvider = contextProvider;
        this.preferredLocale = preferredLocale == null ? Locale.getDefault() : preferredLocale;
        this.progress = progress;
    }

    public Result download(String playlistUrl, File workDirectory) throws IOException {
        if (!workDirectory.exists() && !workDirectory.mkdirs()) {
            throw new IOException("Could not create download work directory");
        }
        progress("Resolving streams", 1);
        Resolved resolved = resolve(playlistUrl, 0);
        File videoFile = new File(workDirectory, "video.part");
        File audioFile = resolved.audio == null ? null : new File(workDirectory, "audio.part");
        downloadMedia(resolved.video, videoFile, "Video", 0, audioFile == null ? 85 : 55);
        if (resolved.audio != null) downloadMedia(resolved.audio, audioFile, "Audio", 55, 85);
        return new Result(videoFile, audioFile, resolved.video.durationSeconds(),
                resolved.video.initialization != null);
    }

    public boolean isMasterPlaylist(String playlistUrl) throws IOException {
        HttpFetcher.TextResponse response = retryText(playlistUrl);
        try { return HlsParser.parse(response.text, response.finalUrl).isMaster(); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
    }

    private Resolved resolve(String url, int depth) throws IOException {
        if (depth > MAX_PLAYLIST_DEPTH) throw new IOException("HLS playlist nesting is too deep");
        HttpFetcher.TextResponse response = retryText(url);
        HlsParser.Parsed parsed;
        try { parsed = HlsParser.parse(response.text, response.finalUrl); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        if (!parsed.isMaster()) return new Resolved(parsed.media, null);

        HlsParser.Variant variant = parsed.master.bestVariant();
        HlsParser.Rendition outerAudio = parsed.master.audioFor(variant, preferredLocale);
        Resolved nestedVideo = resolve(variant.uri, depth + 1);
        HlsParser.MediaPlaylist audio = nestedVideo.audio;
        if (audio == null && outerAudio != null) {
            Resolved nestedAudio = resolve(outerAudio.uri, depth + 1);
            audio = nestedAudio.audio != null ? nestedAudio.audio : nestedAudio.video;
        }
        return new Resolved(nestedVideo.video, audio);
    }

    private void downloadMedia(HlsParser.MediaPlaylist playlist, File destination, String label,
                               int progressStart, int progressEnd) throws IOException {
        HlsParser.MapSegment writtenMap = null;
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            for (int i = 0; i < playlist.segments.size(); i++) {
                HlsParser.Segment segment = playlist.segments.get(i);
                if (segment.initialization != null && !sameMap(writtenMap, segment.initialization)) {
                    byte[] init = retryBytes(segment.initialization.uri,
                            segment.initialization.byteRange, MAX_SEGMENT_BYTES);
                    output.write(decryptIfNeeded(init, segment.initialization.key, segment.sequence, true));
                    writtenMap = segment.initialization;
                }
                byte[] bytes = retryBytes(segment.uri, segment.byteRange, MAX_SEGMENT_BYTES);
                output.write(decryptIfNeeded(bytes, segment.key, segment.sequence, false));
                int percent = progressStart + (int) (((i + 1L) * (progressEnd - progressStart))
                        / playlist.segments.size());
                progress(label, percent);
            }
            output.getFD().sync();
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw error;
        }
        if (destination.length() == 0) throw new IOException(label + " download was empty");
    }

    private byte[] decryptIfNeeded(byte[] encrypted, HlsParser.Key key, long sequence,
                                   boolean initialization) throws IOException {
        if (key == null || key.isNone()) return encrypted;
        if (!key.isAes128()) {
            throw new ProtectedMediaException("Protected or unsupported HLS encryption: " + key.method);
        }
        if (key.uri == null) throw new IOException("AES-128 key URI is missing");
        byte[] keyBytes = keys.get(key.uri);
        if (keyBytes == null) {
            keyBytes = retryBytes(key.uri, null, MAX_KEY_BYTES);
            if (keyBytes.length < 16) throw new IOException("AES-128 key was shorter than 16 bytes");
            if (keyBytes.length != 16) keyBytes = Arrays.copyOf(keyBytes, 16);
            keys.put(key.uri, keyBytes);
        }
        byte[] iv;
        if (key.iv != null) iv = parseIv(key.iv);
        else if (initialization) throw new IOException("Encrypted EXT-X-MAP requires an explicit IV");
        else iv = sequenceIv(sequence);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not decrypt ordinary AES-128 HLS segment", e);
        }
    }

    private HttpFetcher.TextResponse retryText(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return fetcherFor(url).textResponse(url); }
            catch (IOException e) {
                last = e;
                backoff(attempt);
            }
        }
        throw last == null ? new IOException("Playlist request failed") : last;
    }

    private byte[] retryBytes(String url, HlsParser.ByteRange range, int maximum) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return fetcherFor(url).bytes(url, range, maximum); }
            catch (IOException e) {
                last = e;
                backoff(attempt);
            }
        }
        throw last == null ? new IOException("Media request failed") : last;
    }

    private static void backoff(int attempt) throws IOException {
        if (attempt >= 2) return;
        try { Thread.sleep(500L << attempt); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private void progress(String phase, int percent) {
        if (progress != null) progress.update(phase, Math.max(0, Math.min(100, percent)));
    }

    private HttpFetcher fetcherFor(String url) {
        RequestContext resolved = contextProvider == null ? null : contextProvider.forUrl(url);
        return new HttpFetcher(resolved == null ? fallbackContext : resolved);
    }

    private static boolean sameMap(HlsParser.MapSegment a, HlsParser.MapSegment b) {
        if (a == b) return true;
        if (a == null || b == null || !a.uri.equals(b.uri)) return false;
        if (a.byteRange == null || b.byteRange == null) return a.byteRange == b.byteRange;
        return a.byteRange.length == b.byteRange.length
                && java.util.Objects.equals(a.byteRange.offset, b.byteRange.offset);
    }

    private static byte[] parseIv(String raw) throws IOException {
        String value = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
        if ((value.length() & 1) == 1) value = "0" + value;
        if (value.length() > 32) throw new IOException("AES IV is longer than 128 bits");
        byte[] result = new byte[16];
        int byteCount = value.length() / 2;
        try {
            for (int i = 0; i < byteCount; i++) {
                result[16 - byteCount + i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            throw new IOException("Invalid AES IV", e);
        }
        return result;
    }

    private static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 8; i--) {
            iv[i] = (byte) sequence;
            sequence >>>= 8;
        }
        return iv;
    }

    private static final class Resolved {
        final HlsParser.MediaPlaylist video;
        final HlsParser.MediaPlaylist audio;
        Resolved(HlsParser.MediaPlaylist video, HlsParser.MediaPlaylist audio) {
            this.video = video;
            this.audio = audio;
        }
    }
}
