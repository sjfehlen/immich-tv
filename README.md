# Immich Home & Screensaver for Android TV

A photo home screen and screensaver for Android TV / Google TV, showing your [Immich](https://immich.app)
albums, with Jellyfin, Home Assistant and now playing built in.

**Just want to install it?** Grab `immich-home.apk` from the
[latest release](https://github.com/sjfehlen/immich-tv/releases/latest) and follow
[Install the APK](#install-the-apk). No Android Studio needed.

## Screensaver

A very small Android TV screensaver (`DreamService`) that shows photos from one
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
favourite apps ending in two muted tiles: **All apps** (grid icon) and **⚙**, which opens a small
frosted menu (Settings, TV settings, Play Store).
Focus follows the Apple TV style: no outline; the selected tile rises, grows, casts a soft shadow,
catches a soft gloss and briefly tilts toward the direction you moved.
Hold OK or press Menu on a tile to move/remove it; add apps from **All apps** (hold OK on an app
there; apps already on Home carry a small ✓). Your Immich photos
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

Turn it on in **TV settings › Accessibility › Immich Home button**, or over `adb` (this replaces
the list; keep services you already use, separated by `:`):

```sh
adb shell settings get secure enabled_accessibility_services   # note this to undo
adb shell settings put secure enabled_accessibility_services \
  com.fehlenfusion.immichscreensaver/com.fehlenfusion.immichscreensaver.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
```

Undo: put the noted value back. Note: force-stopping the app (`adb shell am force-stop`, or
*Force stop* in TV settings) makes Android switch this service off; turn it on again afterwards.

## Jellyfin: Continue watching

Press **Down** from the app row to open three Jellyfin rows; the apps slide up, the clock fades and
the screen scrolls like Apple TV:

- **Continue watching**: what you are partway through (progress bar, minutes left), then next-up episodes;
- **Recently added movies** (with year);
- **Recently added shows**: new episodes grouped per series ("3 new episodes").

Selecting a card opens it in the Jellyfin TV app on its details page, where **Resume** is one click
away. **Up** from the first row, or **Back**, folds the rows away (so does going idle). It is fetched fresh each time you open it, its images are
freed when you close it, and it is read-only.

Connect it under **Settings › Accounts › Jellyfin**: scan the QR code to enter the server
address on your phone, then approve the code the TV shows in the Jellyfin app or web
(**Settings › Quick Connect**; Quick Connect must be enabled in the Jellyfin dashboard). The TV
stores only the resulting access token, which you can revoke under *Dashboard › Devices*.

## Now playing (Spotify and other media)

When an app is playing or paused (Spotify, YouTube, Jellyfin, ...), a compact frosted
**Now playing** card shows album art, title and artist (long titles scroll). Its background is a
blurred snapshot of the photo behind it, refreshed when the photo changes:

- **Home screen**: directly below the clock, with previous / play-pause / next buttons (press **Up** from the apps);
  select the album cover to open the playing app's player.
- **Screensaver**: bottom-right; the remote controls it without waking the TV:
  **◀** previous, **▶** next, **OK** play/pause. Any other button ends the screensaver.

This uses Android's media sessions, so no Spotify developer account is needed. Android only shows
other apps' media sessions to an app with notification-listener access; `MediaListenerService`
exists for that and ignores the notifications themselves. Enable once:

```sh
adb shell cmd notification allow_listener \
  com.fehlenfusion.immichscreensaver/com.fehlenfusion.immichscreensaver.MediaListenerService
```

Undo: `adb shell cmd notification disallow_listener <same component>`.

## Settings

Open **Settings** from the ⚙ menu on the home screen (or open the app from the TV's app list).
A table of contents on the left jumps between sections; the settings sit on frosted cards over a
blurred photo from your album.

| Section | Setting | Options |
|---|---|---|
| Screensaver | Immich | QR-code setup (below) to connect or change the server and API key |
| | Albums | Tick/untick albums with the remote (saved instantly; at least one stays selected) |
| | Preview | Runs the screensaver now; any button exits |
| | Order | Shuffle (no repeats until all shown) · Newest first · Oldest first |
| | Photo duration | 10 s, 20 s, 30 s, 1 min, 2 min, 5 min |
| | Photo style | Fit with blurred background · Fit with black bars · Fill screen (crop) |
| | Slow zoom | Ken Burns push-in (off by default: it keeps the GPU busy) |
| | Clock | Time and date · Time only · Off |
| | Photo date & place | Caption from the photo's EXIF, e.g. "October 12, 2023 · Austin, Texas" |
| | Dim screen | Lower brightness while the screensaver runs |
| Home screen | Background | Photos · Gradient |
| | Hide apps when idle | After 15 s · 30 s · 1 min · 2 min · Never |
| Accounts | Jellyfin | Connect with Quick Connect, reconnect or disconnect |
| | Home Assistant | Server address and long-lived token, entered from your phone |
| System | TV screensaver, Now playing controls, Home button | Whether each is turned on, and how to turn it on if not |

Other touches: a soft gradient behind the clock for readability, and the clock shifts a few pixels
with each photo to avoid burn-in. The blur is computed on a ~64 px copy of each photo, so it costs
~10 KB, not a GPU blur pass.

## Server setup (QR code)

1. In **Settings**, choose **Immich**. It shows a QR code.
2. Scan it with your phone (phone and TV on the same network). A setup page opens.
3. Enter the Immich server address (e.g. `http://192.168.1.10:2283`) and an API key, then tick
   one or more albums. The TV confirms and returns to settings.

Create the API key in Immich under *Account settings → API keys* with only these permissions:
`album.read`, `asset.read`, `asset.view`. The app never calls a write endpoint.

The setup page is served by the TV itself only while the setup screen is open. Every link carries
a random one-time token. The page is plain HTTP on your LAN, so set it up from your home network.
Albums can be changed later from the TV itself (**Albums**), without the phone.

## Install the APK

You don't need Android Studio, just the APK and one of these ways to get it onto the TV.

1. Download `immich-home.apk` from the
   [latest release](https://github.com/sjfehlen/immich-tv/releases/latest).
2. Install it, either way works:
   - **From a computer with `adb`** (recommended, you need it for step 3 anyway). Install Google's
     [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (a small zip,
     no Android Studio). On the TV, enable *Developer options* (Settings › System › About, press
     *Android TV OS build* 7 times), then *Developer options › USB debugging* / *Wireless debugging*.
     Then:

     ```sh
     adb connect <tv-ip>:5555        # or the IP:port shown under Wireless debugging (pair first on Android 11+)
     adb install -r immich-home.apk
     ```

   - **Without a computer**: install the free **Downloader** app (by AFTVnews) from the Play Store
     on the TV, allow it under *Settings › Apps › Security & restrictions › Unknown sources*, and
     open this address in it:
     `https://github.com/sjfehlen/immich-tv/releases/latest/download/immich-home.apk`
3. Turn on the parts you want. Google TV hides these settings, so they need `adb` once:

   ```sh
   # Screensaver (note the old value first so you can undo it):
   adb shell settings get secure screensaver_components
   adb shell settings put secure screensaver_components com.fehlenfusion.immichscreensaver/.PhotoDreamService
   # Now playing card:
   adb shell cmd notification allow_listener \
     com.fehlenfusion.immichscreensaver/com.fehlenfusion.immichscreensaver.MediaListenerService
   ```

   The Home button redirect can be turned on from the TV itself:
   **TV settings › Accessibility › Immich Home button** (see [above](#photo-home-screen-launcher)).
4. Open the app and connect Immich under **Settings › Screensaver › Immich**.

To update, install a newer APK the same way. Your settings are kept.

## Build from source

Requirements: Android SDK (platform 35), JDK 17+ (Android Studio's bundled JBR works).

```sh
./gradlew test assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Local release builds are minified and signed with your local debug keystore
(`~/.android/debug.keystore`). Keystores are not part of this repo. An APK you build yourself has
a different signature from the published ones, so uninstall one before installing the other.

`QrCodeTest` writes sample QR images to `app/build/qr-dump/`. Optionally verify them with a real
decoder:

```sh
python3 -c "import cv2,glob;d=cv2.QRCodeDetector();[print(f,d.detectAndDecode(cv2.imread(f,0))[0]) for f in sorted(glob.glob('app/build/qr-dump/*.pgm'))]"
```

### Publishing a release

`.github/workflows/release.yml` builds, tests and signs the APK and attaches it to a GitHub release
whenever a `v*` tag is pushed. The version comes from the tag (`v1.2.3` -> versionName `1.2.3`,
versionCode `10203`).

One-time setup: create a signing key and add it as repository secrets
(*Settings › Secrets and variables › Actions*). Keep the keystore safe and private: every future
release must be signed with it, or users can't update over the top.

```sh
keytool -genkeypair -v -keystore release.jks -alias immich-home -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # -> secret RELEASE_KEYSTORE_BASE64
# secrets RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS (immich-home), RELEASE_KEY_PASSWORD
```

Then release:

```sh
git tag v1.1.0 && git push origin v1.1.0
```

The Home Assistant console tiles use drawn banners in published builds; the product photos are
kept out of this repo.

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
