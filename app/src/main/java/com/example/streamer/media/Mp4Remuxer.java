package com.example.streamer.media;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Container-level conversion for streams already encoded with MP4-compatible codecs. */
public final class Mp4Remuxer {
    public static final class Result {
        public final boolean hasVideo;
        public final boolean hasAudio;

        Result(boolean hasVideo, boolean hasAudio) {
            this.hasVideo = hasVideo;
            this.hasAudio = hasAudio;
        }
    }

    private static final int BUFFER_SIZE = 32 * 1024 * 1024;

    private Mp4Remuxer() {}

    public static Result remux(File videoInput, File separateAudioInput, File output)
            throws IOException {
        List<InputState> inputs = new ArrayList<>();
        MediaMuxer muxer = null;
        boolean started = false;
        boolean success = false;
        boolean hasVideo = false;
        boolean hasAudio = false;
        try {
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            InputState video = new InputState(videoInput);
            inputs.add(video);
            hasVideo = video.selectFirstTrack("video/", muxer);
            if (separateAudioInput == null) hasAudio = video.selectFirstTrack("audio/", muxer);

            if (separateAudioInput != null) {
                InputState audio = new InputState(separateAudioInput);
                inputs.add(audio);
                hasAudio = audio.selectFirstTrack("audio/", muxer);
            }
            if (!hasVideo) throw new IOException("Downloaded stream has no extractable video track");
            if (separateAudioInput != null && !hasAudio) {
                throw new IOException("Separate audio rendition has no extractable audio track");
            }

            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Map<Integer, Long> lastPtsByOutputTrack = new HashMap<>();

            while (true) {
                InputState next = nextInput(inputs);
                if (next == null) break;
                buffer.clear();
                int size = next.extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    next.finished = true;
                    continue;
                }
                int sourceTrack = next.extractor.getSampleTrackIndex();
                Integer outputTrack = next.trackMap.get(sourceTrack);
                if (outputTrack != null) {
                    long rawPts = Math.max(0, next.extractor.getSampleTime() - next.firstSampleTime);
                    long previous = lastPtsByOutputTrack.containsKey(outputTrack)
                            ? lastPtsByOutputTrack.get(outputTrack) : -1;
                    long pts = Math.max(rawPts, previous + 1);
                    lastPtsByOutputTrack.put(outputTrack, pts);
                    info.set(0, size, pts, next.extractor.getSampleFlags());
                    muxer.writeSampleData(outputTrack, buffer, info);
                }
                next.extractor.advance();
            }

            muxer.stop();
            started = false;
            muxer.release();
            muxer = null;
            validate(output, separateAudioInput != null);
            success = true;
            return new Result(hasVideo, hasAudio);
        } catch (RuntimeException e) {
            throw new IOException("Android could not remux these codecs into MP4", e);
        } finally {
            if (muxer != null) {
                if (started) {
                    try { muxer.stop(); } catch (RuntimeException ignored) { }
                }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
            for (InputState input : inputs) input.release();
            if (!success) {
                //noinspection ResultOfMethodCallIgnored
                output.delete();
            }
        }
    }

    private static InputState nextInput(List<InputState> inputs) {
        InputState selected = null;
        long selectedTime = Long.MAX_VALUE;
        for (InputState input : inputs) {
            if (input.finished) continue;
            long time = input.extractor.getSampleTime();
            if (time < 0) {
                input.finished = true;
                continue;
            }
            long normalized = Math.max(0, time - input.firstSampleTime);
            if (normalized < selectedTime) {
                selectedTime = normalized;
                selected = input;
            }
        }
        return selected;
    }

    private static void validate(File output, boolean requireAudio) throws IOException {
        if (!output.isFile() || output.length() == 0) throw new IOException("MP4 output was empty");
        MediaExtractor check = new MediaExtractor();
        boolean video = false;
        boolean audio = false;
        try {
            check.setDataSource(output.getAbsolutePath());
            for (int i = 0; i < check.getTrackCount(); i++) {
                String mime = check.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                video |= mime != null && mime.startsWith("video/");
                audio |= mime != null && mime.startsWith("audio/");
            }
        } finally {
            check.release();
        }
        if (!video) throw new IOException("MP4 validation found no video track");
        if (requireAudio && !audio) throw new IOException("MP4 validation found no audio track");
    }

    private static final class InputState {
        final MediaExtractor extractor = new MediaExtractor();
        final Map<Integer, Integer> trackMap = new HashMap<>();
        long firstSampleTime;
        boolean finished;

        InputState(File file) throws IOException {
            extractor.setDataSource(file.getAbsolutePath());
        }

        boolean selectFirstTrack(String prefix, MediaMuxer muxer) {
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith(prefix)) {
                    extractor.selectTrack(i);
                    trackMap.put(i, muxer.addTrack(format));
                    long sampleTime = extractor.getSampleTime();
                    firstSampleTime = sampleTime < 0 ? 0 : sampleTime;
                    return true;
                }
            }
            return false;
        }

        void release() {
            try { extractor.release(); } catch (RuntimeException ignored) { }
        }
    }
}
