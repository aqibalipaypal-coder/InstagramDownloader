# Instagram Downloader

Native Android app to download publicly accessible Instagram photos, videos, Reels, and carousel media.

## Getting the APK

GitHub Actions always delivers artifacts as a ZIP file.

1. Go to the Actions tab.
2. Open a successful **Build APK** run.
3. Download the **InstagramDownloader-APK** artifact.
4. The download is a ZIP named `InstagramDownloader-APK.zip`.
5. Extract it. Inside you will find `app-debug.apk`.
6. Transfer `app-debug.apk` to your phone and install it.

Do not try to install the ZIP itself. The APK is the file inside the ZIP.

## What the workflow does

- Runs unit tests first (`gradle test`).
- Builds the debug APK only if tests pass.
- Verifies the APK exists, is larger than 1 MB, and has valid ZIP/APK magic bytes.
- Uploads the verified APK as the `InstagramDownloader-APK` artifact (retention 14 days).

## Local build

Open in Android Studio and build a debug APK, or run:

```
./gradlew test assembleDebug
```

## Tests

Unit tests for the media parser live in `app/src/test/java/com/instadownloader/app/InstagramParserTest.kt`.
They cover:

- Valid data-sjs script elements
- Nested JSON objects and arrays
- Multiline content
- Missing scripts
- Malformed JSON
- Login walls
- Photo, Reel, and carousel extraction
- Invalid URLs

Live Instagram network retrieval is not guaranteed in CI because Instagram frequently blocks automated requests. Those cases are reported as BLOCKED or NOT TESTED when they cannot run.

## Disclaimer

Public media only. Not affiliated with Instagram. Respect creators and terms of service.
