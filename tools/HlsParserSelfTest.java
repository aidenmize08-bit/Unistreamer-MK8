import com.example.streamer.hls.HlsParser;

import java.util.Locale;

/** Runs without Android or JUnit: javac HlsParser.java this file, then java HlsParserSelfTest. */
public final class HlsParserSelfTest {
    public static void main(String[] args) {
        selectsSeparateAudio();
        acceptsBrokenAudioGroup();
        handlesByteRangesAndMaps();
        handlesObscuredSegmentUrls();
        System.out.println("HlsParserSelfTest: all checks passed");
    }

    private static void selectsSeparateAudio() {
        String text = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English, Stereo\","
                + "LANGUAGE=\"en\",DEFAULT=YES,URI=\"audio.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=900000,AUDIO=\"aud\",CODECS=\"avc1.4d401f\"\n"
                + "video.m3u8\n";
        HlsParser.MasterPlaylist master = HlsParser.parse(text, "https://e.test/master").master;
        check(master.audioFor(master.bestVariant(), Locale.ENGLISH).uri.equals(
                "https://e.test/audio.m3u8"), "separate audio selection");
    }

    private static void acceptsBrokenAudioGroup() {
        String text = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"real\",DEFAULT=YES,URI=\"audio.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"stale\",CODECS=\"avc1.4d401f\"\n"
                + "video.m3u8\n";
        HlsParser.MasterPlaylist master = HlsParser.parse(text, "https://e.test/master").master;
        check(master.audioFor(master.bestVariant(), Locale.ENGLISH) != null,
                "malformed provider audio-group fallback");
    }

    private static void handlesByteRangesAndMaps() {
        String text = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXT-X-MAP:URI=\"init\",BYTERANGE=\"100@5\"\n"
                + "#EXTINF:4,\n#EXT-X-BYTERANGE:10@105\nblob\n"
                + "#EXTINF:4,\n#EXT-X-BYTERANGE:20\nblob\n";
        HlsParser.MediaPlaylist media = HlsParser.parse(text, "https://e.test/path/list").media;
        check(media.segments.size() == 2, "segment count");
        check(media.segments.get(1).byteRange.offset == 115, "implicit byte-range offset");
        check(media.initialization.uri.equals("https://e.test/path/init"), "map resolution");
    }

    private static void handlesObscuredSegmentUrls() {
        String text = "#EXTM3U\n#EXTINF:2,\nchunk?id=1\n";
        HlsParser.MediaPlaylist media = HlsParser.parse(text, "https://e.test/s/playlist?sig=x").media;
        check(media.segments.get(0).uri.equals("https://e.test/s/chunk?id=1"),
                "extensionless segment URL");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
