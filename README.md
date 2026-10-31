# Immich Screensaver for Android TV

A very small (~75 KB) Android TV screensaver (`DreamService`) that shows photos from one
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

## Photo home screen (launcher)

`HomeActivity` is a minimal home screen: clock top-left, and along the bottom edge a row of
favourite apps ending in a muted ⚙ tile (Play Store, TV settings, Photos & screensaver, All apps).
Focus follows the Apple TV style: no outline; the selected tile rises, grows, casts a soft shadow,
catches a soft gloss and briefly tilts toward the direction you moved.
Hold OK or press Menu on a tile to move/remove it; add apps from **All apps**. Your Immich photos
cross-fade behind everything (one every 30 s; falls back to a gradient offline). After a period
with no remote input (*Hide home apps when idle*, default 30 s) the app row fades out, leaving
photos and the clock; the first key press only brings it back.
The slideshow runs only while the home screen is visible. Turn the photos off under
*Home screen background* in Settings.

On the Chromecast with Google TV the stock launcher (`launcherx`) and the setup app
(`setupwraith`) must stay enabled: disabling them breaks phone casting after a reboot, and
disabling setupwraith can delete a saved Wi-Fi network (see
[cleanyourtv's Chromecast notes](https://github.com/Gathin23/cleanyourtv/tree/main/examples/google-chromecast-gtv-4k)).
So Google TV remains Android's Home, and the **Immich Home button** accessibility service
(`HomeRedirectService`) sends you here instead:

- when a launcherx home screen comes to the front, it opens `HomeActivity`;
- a short Home press while launcherx is already showing does the same (long press is left alone);
- whenever the service binds (boot, or after this app is updated) it briefly opens an invisible
  activity, so a launcherx home that was already on screen still gets redirected;
- it starts `HomeActivity` as a normal app (not a Home intent), so launcherx stays the home process
  and keeps handling the remote's YouTube/Netflix buttons;
- it stops after 3 failed starts and pauses if it ever fires in a tight loop.

It only reads which window is in front and the Home key; it never reads screen content, passes every
key through, and never disables packages or changes the Home role.

Enable it (this replaces any other Home-redirect service; keep others you use, separated by `:`):

```sh
adb shell settings get secure enabled_accessibility_services   # note this to undo
adb shell settings put secure enabled_accessibility_services \
  com.fehlenfusion.immichscreensaver/com.fehlenfusion.immichscreensaver.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
```

Undo: put the noted value back.

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
| Home screen background | Photos · Gradient |
| Hide home apps when idle | After 15 s · 30 s · 1 min · 2 min · Never |

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
