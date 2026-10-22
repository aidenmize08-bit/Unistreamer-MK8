package com.example.streamer.hls;

import static org.junit.Assert.*;

import java.util.Locale;

import org.junit.Test;

public final class HlsParserTest {
    @Test public void selectsMatchingDefaultAudioAndHighestVariant() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English, Stereo\","
                + "LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio/en.m3u8\"\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Spanish\","
                + "LANGUAGE=\"es\",DEFAULT=NO,URI=\"audio/es.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"aud\"\n"
                + "low/video.m3u8\n"
                + "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2500000,BANDWIDTH=3000000,"
                + "CODECS=\"avc1.640028\",AUDIO=\"aud\"\n"
                + "high/video.m3u8\n";
        HlsParser.MasterPlaylist master = HlsParser.parse(playlist,
                "https://cdn.example/path/master.m3u8?token=x").master;
        HlsParser.Variant selected = master.bestVariant();
        assertEquals("https://cdn.example/path/high/video.m3u8", selected.uri);
        HlsParser.Rendition audio = master.audioFor(selected, Locale.ENGLISH);
        assertNotNull(audio);
        assertEquals("https://cdn.example/path/audio/en.m3u8", audio.uri);
        assertEquals("English, Stereo", audio.name);
    }

    @Test public void fallsBackWhenProviderUsesStaleAudioGroup() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"real\",DEFAULT=YES,URI=\"a.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.4d401f\",AUDIO=\"stale\"\n"
                + "v.m3u8\n";
        HlsParser.MasterPlaylist master = HlsParser.parse(playlist,
                "https://cdn.example/master.m3u8").master;
        assertEquals("https://cdn.example/a.m3u8",
                master.audioFor(master.bestVariant(), Locale.ENGLISH).uri);
    }

    @Test public void resolvesByteRangesMapAndRotatingKey() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/key.bin\",IV=0x01\n"
                + "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"100@5\"\n"
                + "#EXTINF:4.5,\n"
                + "#EXT-X-BYTERANGE:250@105\n"
                + "media.bin\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:3,\n"
                + "#EXT-X-BYTERANGE:200\n"
                + "media.bin\n"
                + "#EXT-X-ENDLIST\n";
        HlsParser.MediaPlaylist media = HlsParser.parse(playlist,
                "https://cdn.example/live/index.m3u8").media;
        assertEquals(2, media.segments.size());
        assertEquals(42, media.segments.get(0).sequence);
        assertEquals(Long.valueOf(105), media.segments.get(0).byteRange.offset);
        assertEquals(Long.valueOf(355), media.segments.get(1).byteRange.offset);
        assertTrue(media.segments.get(1).discontinuity);
        assertEquals("https://cdn.example/live/init.mp4", media.initialization.uri);
        assertEquals("https://cdn.example/live/keys/key.bin", media.segments.get(0).key.uri);
        assertTrue(media.endList);
        assertEquals(7.5, media.durationSeconds(), 0.0001);
    }

    @Test public void detectsMediaPlaylistWithoutExtensions() {
        String playlist = "#EXTM3U\n#EXTINF:6,\nsegment?id=1\n#EXTINF:6,\nsegment?id=2\n";
        HlsParser.MediaPlaylist media = HlsParser.parse(playlist,
                "https://edge.example/session/playlist?sig=abc").media;
        assertEquals("https://edge.example/session/segment?id=1", media.segments.get(0).uri);
    }
}
