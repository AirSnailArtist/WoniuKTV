# Verification — 0.1.0

Build: JDK 17, Android Gradle Plugin 8.7.3, Gradle 8.11.1, SDK 35.

Validated on AYANEO Pocket DS, Android 13:

- APK assembles and launches as `com.pocketktv.app/.PocketActivity`.
- Landscape library shell renders without clipping at 1920×1080.
- The live 115 QR endpoint returns a QR payload, rendered inside the app.
- Unit tests: Home KTV filename parsing, pinyin, extensions, web/Open API directory schemas, string IDs, numeric success flags, host boundary checks and transport encoding input validation.
- Device tests: persisted scan offsets, duplicate scan deduplication, favorite preservation, queue persistence, Chinese/pinyin search and account isolation.
- Device tests: portable catalog export/import, favorites restoration, credential-free export schema, transaction rollback and rejection of another account's catalog.

Pending user's account authorization:

- Completing the actual 115 login and scanning a real KTV folder.
- Getting a fresh CDN URL for a real catalog file.
- Uploading the catalog through the 115 sample-upload flow and restoring it on another device.

Playback/audio are deferred by request; source in `future/` is not compiled into this version.
