# simpleView

A very small, dependency-free PDF viewer for Android 14 and newer.

- Opens PDFs from the system file picker, "Open with" from other apps, or "Share" to simpleView
- Continuous vertical scrolling, pinch-to-zoom, double-tap zoom, sharp text at any zoom level
- Page indicator, **Go to page**, and a **Dark pages** (inverted colours) mode
- Password-protected PDFs (Android 15+; Android 14's built-in renderer cannot open them)
- No permissions, no network, no analytics, no third-party libraries (about 50 KB release APK)

It uses Android's built-in `android.graphics.pdf.PdfRenderer`, so the app itself stays tiny.

| Setting | Value |
|---|---|
| Application ID | `com.simpleview.pdfviewer` (change it in `app/build.gradle.kts` before publishing if you want your own) |
| minSdk / targetSdk / compileSdk | 34 / 36 / 36 |
| Language | Java, framework views only |

## Build

Requires JDK 17+ and the Android SDK (platform 36, build-tools 36.0.0). Create `local.properties` with
`sdk.dir=<path to your Android SDK>` (Android Studio does this for you), then:

```powershell
.\gradlew.bat assembleDebug      # app\build\outputs\apk\debug\app-debug.apk  (for testing)
.\gradlew.bat bundleRelease      # app\build\outputs\bundle\release\app-release.aab  (for Google Play)
.\gradlew.bat lintRelease        # static checks
```

## Sign the release bundle (required by Google Play)

1. Create an upload key **once** and keep it (and its passwords) safe and backed up:

   ```powershell
   keytool -genkeypair -v -keystore simpleview-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Create `keystore.properties` in the project root (it is git-ignored):

   ```properties
   storeFile=simpleview-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```

3. Run `.\gradlew.bat bundleRelease` and upload `app-release.aab`. Enrol in **Play App Signing** when asked.

Without `keystore.properties` the release build is unsigned and Play will reject it.

## Publishing checklist (Play Console)

- Increase `versionCode` in `app/build.gradle.kts` for every upload.
- Listing assets are in [store-assets/](store-assets): `icon-512.png` and `feature-graphic-1024x500.png`.
  You still need at least 2 phone screenshots (aspect ratio between 9:16 and 16:9, so crop a 1080x2400 capture to
  1080x1920).
- **Data safety**: the app collects and shares no data and has no network access.
- **Privacy policy**: Play asks for a URL; a one-line "simpleView does not collect, store or transmit any data" page is
  enough.
- Content rating questionnaire: no user-generated content, no ads.
- Target API level 36 satisfies Google Play's requirement for new apps in 2026.

## Notes

- Files are opened through the Storage Access Framework, so no storage permission is requested.
- Links inside PDFs, text search and text selection are not available because `PdfRenderer` does not expose them.
