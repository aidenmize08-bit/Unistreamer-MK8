# StreamBrowser (reconstructed)

This source project was reconstructed from the supplied compiled APK. It keeps the original
package name (`com.example.streamer`) and Frutiger Aero direction, but replaces the fragile
single-class download code with separate HLS, HTTP, remux, and storage components.

## What changed

- Captures request headers, cookies, page URL, and referer **per detected media URL** instead of
  keeping one global header map that unrelated requests can overwrite.
- Resolves nested HLS master playlists and selects the highest-bandwidth video variant.
- Resolves matching `EXT-X-MEDIA` audio groups; prefers the device language, then the default or
  autoselect rendition. It also has a conservative fallback for malformed/stale group IDs when
  the chosen video variant declares no embedded audio codec.
- Handles `EXT-X-MAP`, explicit and implicit `EXT-X-BYTERANGE`, rotating ordinary AES-128 keys,
  redirects, request retries, and signed query strings.
- Downloads separate audio and video renditions and validates that the final MP4 contains audio
  when separate audio was expected.
- Converts ordinary H.264/AAC MPEG-TS and fragmented MP4 streams by remuxing them into MP4.
- Uses only Android platform code—there are no native `.so` files—so the same APK runs on ARM
  Android devices and x86/x86_64 BlueStacks installations.

## Important limits

This app does not bypass DRM, paywalls, authentication, or protected media. `SAMPLE-AES`, Widevine,
and non-identity key formats are intentionally rejected. Android's platform muxer can change a
compatible container (for example, H.264/AAC TS to MP4), but it cannot universally transcode every
codec. WebM/VP9/Opus or unusual audio codecs can therefore fail with a clear conversion error.

## Build

Open this folder in a current Android Studio installation with Android SDK 35 installed, let Gradle
sync, and run the `app` configuration. From a terminal with the Gradle wrapper or Gradle 8.9:

```bash
gradle assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. If BlueStacks already has
the old APK installed, uninstall it first: the reconstructed build uses a new signing certificate,
and Android will not install an update signed by a different key.

## Testing notes

Browse to a page, begin playback, then press **DOWNLOAD MP4** and choose the HLS master URL. Test
only media you are authorized to download. If a provider fails, record the exact in-app error and
whether the picker showed a master playlist or only a media playlist; that is enough to diagnose
most remaining compatibility cases without sharing private cookies.
