# Immich Screensaver for Android TV

A very small (~65 KB) Android TV screensaver (`DreamService`) that shows photos from one
[Immich](https://immich.app) album full-screen, with a clock and date in the bottom-left corner.
Built for a Chromecast with Google TV (2 GB RAM), so it is deliberately frugal:

- No third-party libraries (only `HttpURLConnection`, `BitmapFactory`, `org.json`).
- Only permission: `INTERNET`. No analytics, no crash reporting, no logging of URLs or keys.
- Photos only: videos, trashed, archived, hidden and locked assets are skipped.
- Downloads Immich's `preview` rendition (never originals), decoded to at most 1920×1080 in
  `RGB_565`. At most two full-size bitmaps are in memory at any time (plus two ~10 KB blur
  thumbnails); no disk or memory cache.
- One photo every 20 s (configurable) with a 1.5 s cross-fade, shuffled with no repeats until the whole album
  has been shown. The album is re-fetched each time the screensaver starts.
- Network errors keep the current photo (or black + clock) and retry after 30 s, backing off to 5 min.
- Nothing runs while the screensaver is not active.

**No server address, album or API key is in the code or the APK.** They are entered on the TV at
runtime through a QR-code setup page and stored in app-private storage (backup disabled).

## Settings (open the app on the TV)

Opening **Immich Screensaver** from the apps row shows a D-pad settings list over a blurred photo
from your album:

| Setting | Options |
|---|---|
| Preview screensaver | Runs it now; any button exits |
| Albums | Tick/untick albums with the remote (saved instantly; at least one stays selected) |
| Server | QR-code setup (below) to change the server or API key |
| Order | Shuffle (no repeats until all shown) · Newest first · Oldest first |
| Photo duration | 10 s, 20 s, 30 s, 1 min, 2 min, 5 min |
| Photo style | Fit with blurred background · Fit with black bars · Fill screen (crop) |
| Slow zoom | Ken Burns push-in (off by default: it keeps the GPU busy) |
| Clock | Time and date · Time only · Off |
| Photo date & place | Caption from the photo's EXIF, e.g. "October 12, 2023 · Austin, Texas" |
| Dim screen | Lower brightness while the screensaver runs |

The screen also tells you whether the app is currently the TV's screensaver. Other touches: a soft
gradient behind the clock for readability, and the clock shifts a few pixels with each photo to
avoid burn-in. The blur is computed on a ~64 px copy of each photo, so it costs ~10 KB, not a
GPU blur pass.

## Server setup (QR code)

1. In the app, choose **Server**. It shows a QR code.
2. Scan it with your phone (phone and TV on the same network). A setup page opens.
3. Enter the Immich server address (e.g. `http://192.168.1.10:2283`) and an API key, then tick
   one or more albums. The TV confirms and returns to settings.

Create the API key in Immich under *Account settings → API keys* with only these permissions:
`album.read`, `asset.read`, `asset.view`. The app never calls a write endpoint.

The setup page is served by the TV itself only while the setup screen is open. Every link carries
a random one-time token. The page is plain HTTP on your LAN, so set it up from your home network.
Albums can be changed later from the TV itself (**Albums**), without the phone.

## Build

Requirements: Android SDK (platform 35), JDK 17+ (Android Studio's bundled JBR works).

```sh
./gradlew test assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release build is minified and signed with your local debug keystore
(`~/.android/debug.keystore`). Keystores are not part of this repo.

`QrCodeTest` writes sample QR images to `app/build/qr-dump/`. Optionally verify them with a real
decoder:

```sh
python3 -c "import cv2,glob;d=cv2.QRCodeDetector();[print(f,d.detectAndDecode(cv2.imread(f,0))[0]) for f in sorted(glob.glob('app/build/qr-dump/*.pgm'))]"
```

## Install

```sh
adb connect <tv-ip>:5555        # after enabling network debugging on the TV
adb install -r app-release.apk
# note the current screensaver so you can restore it later:
adb shell settings get secure screensaver_components
adb shell settings put secure screensaver_components com.fehlenfusion.immichscreensaver/.PhotoDreamService
```

Google TV does not list third-party screensavers in its settings, which is why the `adb` line is needed.

## Undo

```sh
adb shell settings put secure screensaver_components <value you noted above>
adb uninstall com.fehlenfusion.immichscreensaver
```

## Notes

- Cleartext HTTP is allowed app-wide because the server address is only known at runtime
  (Android's network security config cannot whitelist "any LAN IP"). HTTPS URLs work too.
- Immich API: on servers that still return `assets` from `GET /api/albums/{id}` that list is used.
  Immich v3.2+ uses `POST /api/search/metadata` with `filter.albumIds` and cursor paging, and older
  servers fall back to the legacy `albumIds`/`page` search body. Tested against the v3.3.5 OpenAPI spec.
- If you see colour banding in skies or gradients, change `BITMAP_CONFIG` in
  `Slideshow` to `ARGB_8888` (doubles bitmap memory to ~8 MB each).
