# Upstream provenance

## Home KTV — MIT

Source: https://github.com/zhayinggang/ktv-home
Commit: 9fa653ac1606e3a110b03718ceee88e9c2281187

Retained and adapted:
- `player/PlaybackEngine.kt`: remote playback, track selection, buffer/error recovery; modified to accept per-source headers and a channel processor; removed source URL logging.
- `player/LrcParser.kt`: retained parser, covered by tests; external cloud lyric fetching is not wired in this prototype.
- `library/FilenameParser.java`: retained parser; replaced Java isBlank for Android compatibility.
- `library/ParsedMeta.java`: replaced record with Android-compatible value class.
- `libs/media3-decoder-ffmpeg.aar`: upstream-supplied Media3 1.4.1 / FFmpeg 6.0.1 audio extension. Source upstream README and licenses must accompany redistribution; codec configuration should be audited before a public release.

The UI, SQLite repositories and scanner are Android replacements for upstream's H5/server modules; they are not the original Spring Boot server embedded inside Android. Phase-one playback/LRC files are retained under `future/playback/`, not compiled or exposed in the APK. The supplied AAR is reserved for that later phase and not packaged in this APK.

## 115driver — MIT

Source: https://github.com/SheltonZhu/115driver
Commit: 542720cb0034954750454e89f32b4852add6e2a9

Kotlin adaptation of QR TV-session protocol, read-only listing/download requests, and `pkg/crypto/m115` transport encoding.
New files `Drive115.kt` and `SessionCipher.kt` carry source attribution. No credentials are copied from upstream.

## 115 SDK Go — Apache-2.0 / 115-plus-desktop — MIT

https://github.com/xhofe/115-sdk-go
https://github.com/lvzhenbo/115-plus-desktop

References for optional official device-PKCE request formats, file schemas and refresh-token flow.
No source application credentials were used. Corresponding licenses are included.

## Other dependencies

AndroidX / Media3 / Kotlin / coroutines / ZXing / pinyin4j: see Gradle coordinates and included license notices.
OkHttp: Apache-2.0. FFmpeg: LGPL-2.1-or-later, subject to the actual upstream build configuration.
No claim is made that all upstream binaries have been independently audited for public distribution.

## 0.2.0 H5 migration

`frontend/src` was copied from the MIT Home KTV H5 source at the same pinned commit. Reused Vue/Pinia stores, song rows, favorites, search, artist browsing, queue, dialogs and toasts. The Android shell, landscape home page, request bridge and spatial remote navigation replace the original remote-service transport. `frontend/UPSTREAM-LICENSE` retains the upstream MIT notice.

Only supported routes are built. Network requests from the WebView are blocked except for bundled app assets. Existing Android library/scanner/credentials remain in place. No code or art from the examined proprietary APKs is included in the deliverable.

## 0.4.1 unified playback

The playback adapter now embeds VideoLAN LibVLC (`org.videolan.android:libvlc-all:3.6.5`, LGPL-2.1-or-later) for all media formats, using the upstream binary artifact and its standard Java bindings. Official source: https://github.com/videolan/vlc-android ; LibVLC JNI: https://code.videolan.org/videolan/libvlcjni . Maven provides the matching source artifact. LGPL text is bundled in app assets. The earlier Media3 FFmpeg AAR is no longer a runtime dependency.

PlaybackTransport and the UI/queue adapter are local implementations. The loopback-only transport carries Range requests and per-source headers to the fixed 115 source without exposing account cookies or signed URLs to player options. Native VLC codec/demux implementations are reused, not reimplemented.
