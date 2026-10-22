package com.example.streamer;

import com.example.streamer.media.Mp4Remuxer;
import com.example.streamer.net.HttpFetcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class DirectDownloader {
    interface Progress { void update(int percent); }

    private DirectDownloader() {}

    static File downloadAndConvert(MediaCandidate candidate, File directory, Progress progress)
            throws IOException {
        if (candidate.kind == MediaCandidate.Kind.DASH) {
            throw new IOException("DASH manifests are detected but not supported in this build");
        }
        File raw = new File(directory, "direct.input");
        File output = new File(directory, "direct.mp4");
        HttpFetcher fetcher = new HttpFetcher(candidate.context);
        try (HttpFetcher.Response response = fetcher.open(candidate.url, null);
             FileOutputStream stream = new FileOutputStream(raw)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            long total = 0;
            while ((read = response.stream.read(buffer)) >= 0) {
                stream.write(buffer, 0, read);
                total += read;
                if (progress != null) progress.update((int) Math.min(80, 5 + total / (1024 * 1024)));
            }
            stream.getFD().sync();
        }
        if (raw.length() == 0) throw new IOException("Server returned an empty media file");

        // Even direct MP4 is remuxed. This validates tracks and normalizes timestamps; TS becomes MP4.
        Mp4Remuxer.remux(raw, null, output);
        if (progress != null) progress.update(95);
        return output;
    }
}
