# Reverse-engineering findings

The supplied archive contained only `app-debug.apk`; it did not contain Gradle files or source.
The APK held one application class (`com.example.streamer.MainActivity`) plus the Kotlin standard
library. The reconstructed project is therefore a clean implementation based on recovered behavior,
not a byte-for-byte decompilation.

## Why audio was unreliable

The recovered app did parse `EXT-X-MEDIA` audio renditions, but several architectural details made
real-world failures likely:

1. It kept one global `sniffedHeaders` map. Any later WebView request could replace the headers from
   the master playlist before the downloader fetched its video, audio, key, or segment URLs.
2. It understood only whole-file HLS segments. It ignored `EXT-X-BYTERANGE` on media segments and
   on `EXT-X-MAP`, a common layout for fragmented MP4 audio.
3. It chose only an exact matching `AUDIO` group. A stale or malformed provider group could result
   in silent video even when a default audio rendition was present.
4. It did not recursively retain audio choices across nested master playlists.
5. Its MP4 merge added every audio/video track from every input, so a video rendition containing an
   audio track plus a separate rendition could create duplicate audio tracks or muxer rejection.
6. Its final validation mainly checked track count, not whether a required separate audio track was
   actually present.
7. Direct `.ts` requests were not part of its media-sniff regular expression, despite the interface
   claiming broad MP4 conversion.

## Security boundary retained

Ordinary HLS AES-128 segment encryption is supported as a transport feature. The downloader rejects
`SAMPLE-AES`, non-identity key formats, and other protected-media methods. Captured cookies are sent
only through Android's domain-aware cookie store (with a same-host snapshot fallback), and an
`Authorization` header is not forwarded to a different host after redirects.

## Build status

The current execution environment contains neither an Android SDK, Gradle, nor a Java compiler, so
an APK could not be compiled or installed here. The project targets Android SDK 35, minimum SDK 23,
uses no native libraries, and includes parser fixtures for the recovered failure cases. It still
needs one Android Studio build followed by the user's BlueStacks/media tests.
