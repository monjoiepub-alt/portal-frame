# Tablet playback, phase 1

Development branch: `dev/tablet-playback-phase1`, based on `4affee8`.
The intended tablet is Lenovo Tab M10 TB328FU / Android 12. Hardware compatibility
has **not** been verified. Existing MIT attribution, LICENSE and NOTICE are retained.

## Controls

- Settings → Customize → Playback: enter hours, minutes and seconds, then **Save duration**.
  Valid range is 1 second–24 hours, with minutes/seconds 0–59. Presets save immediately.
  The existing `delay_ms` preference is retained. This is the settled photo hold time;
  image loading and crossfade time can extend the interval between photo changes.
- Settings → Playback & album selection: choose Auto, Manual Landscape, Manual Portrait,
  or All orientations. Auto is the default and follows rotation. Strict modes exclude
  squares and unknown bounds, disable pairing/zoom/Ken Burns without overwriting their
  preferences, and fit the entire image on a black background. Local image dimensions
  are resolved off the UI thread, with EXIF rotation/mirroring taken into account.
  Google/iCloud provide dimensions; legacy caches get header probes using cached bytes.
  Strict rendering also verifies actual downloaded bounds before displaying the image.
- Choose Repeat current album, Next album, or Shuffle all, then select an enabled album.
  Repeat stays on that album. Next plays each eligible photo in order and advances,
  skipping empty/nonmatching albums and wrapping. Shuffle all deduplicates by image URL/id.
  Photos from phones are a separate selectable source. Bundled samples remain the fallback
  when no albums are configured and there are no local uploads.
- **Start slideshow** applies saved settings immediately; the slideshow's **Albums & playback**
  button and existing long-press gesture return to settings. Android Back returns to playback.
  Rotation recalculates selection; entering settings pauses playback and refresh timers.
- Existing clock/date styles, iCloud, Google Photos, uploads, and Portal dream entry remain.

## Development validation

Use JDK 17–21 and Android SDK 36. In the prepared cloud environment:

```bash
source /workspace/.cloud-setup/env.sh
cd /workspace/portal-frame
./gradlew assembleDebug testDebugUnitTest lintDebug detekt ktlintCheck --no-daemon --max-workers=4
```

JVM tests are under `app/tests`, explicitly configured outside the flat production
`app/src` tree. They cover duration limits/invalid input, EXIF axis swaps, Auto rotation,
manual modes, squares/unknown bounds, album traversal/wrapping, repeat and deduplication.
The ktlint baseline retains only existing findings on unchanged source lines, rebased
when their line numbers moved; it does not accept new findings. Compose function naming
is configured through `.editorconfig` instead of individually baselining new composables.

## Physical-device checks still required

Install the debug APK with `adb install -r`. A differently signed installed Frame may
require an upgrade signed with the same key; uninstalling would erase preferences/uploads.

1. Use photos with visible borders, portrait/landscape/square dimensions and EXIF
   rotations 1–8. Verify whole-image fit and strict exclusion in both tablet orientations.
2. Save 2m30s and each boundary/preset; return with Back and Start slideshow. Verify
   the new duration without restarting the process. Check clock/date and low-light mode.
3. Test all three playback modes with empty, disabled, single-photo and mixed albums;
   rotate halfway through an album and verify progression and last-to-first wrapping.
4. Add/delete photos in actual shared albums, wait for refresh, interrupt Wi-Fi and
   reopen settings during a fetch. Verify recovery without stale or paused-screen updates.
5. Exercise Android gesture navigation, background/foreground, screensaver entry,
   reboot, and extended playback. Portal dream/privileged guard behavior needs regression
   testing on Portal hardware separately.

## Known external limitations

Google Photos scraping is unofficial and can change. Existing foreground refresh behavior
is preserved (on resume and every 20 minutes while playback is visible), rather than adding
background scheduling. An empty scraper response remains ambiguous and retains the previous
cache to avoid destroying offline playback. Distinct image URLs for the same underlying
photo cannot be deduplicated reliably without stable provider media identities.

For the hardware-reported bug fixes and current gestures, see [TABLET_BUGFIX.md](TABLET_BUGFIX.md).
