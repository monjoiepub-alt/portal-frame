# Lenovo validation: 1.2.2-tablet (16)

For subsequent light-sensor, clock and Celsius corrections, see [DISPLAY_FIXES.md](DISPLAY_FIXES.md).

Verified on 2026-11-06 using Lenovo TB328FU, Android 12/API 31, serial HA21T78E.
Changes remain on `dev/tablet-playback-phase1`.

## Corrections

- Four explicit orientation choices observe preferences inside the controls. Text uses the
  existing dark palette so selected and unselected values remain readable.
- Preference observation is shared with the duration editor. A physical test found that saving
  seven seconds persisted correctly but left the Saved label at ten seconds; this is fixed.
- AUTO uses display configuration and actual viewport bounds, rather than a physical sensor
  value that can disagree with display rotation. Strict modes retain whole-photo rendering,
  EXIF validation, and disabled crop/zoom/pairing.
- Next Album tracks completed IDs and a shuffled order per album. Late dimension resolution
  appends eligible photos without replaying completed entries. Returning cycles reshuffle.
- Existing touch thirds, display history, pause holds and long-press controls are retained.
- Local file detection uses `File.isAbsolute`, allowing real EXIF fixtures to work in Windows
  Robolectric tests as well as on Android.
- A separate validation build uses `com.portalhacks.frame.validation` and the label Frame Validation.
  No original package data needs to be replaced for testing.

## Automated verification

`./gradlew assembleDebug assembleValidation testDebugUnitTest lintDebug detekt ktlintCheck`
passed locally. All 36 JVM/Robolectric/Compose tests passed. Android Lint reported zero errors
and 57 warnings; existing static-analysis baselines were retained. No instrumented Android test
suite is configured; hardware interaction was tested separately with ADB.

## Physical-device evidence

- Repeated orientation/mode choices updated checked radio semantics without reopening Settings.
  Preferences and checked values survived force-stop/relaunch.
- Next Album completed two synthetic cycles, with two eligible portrait photos in each album,
  no repeat within each album visit, and a different order on the returning cycle.
- Previous/Next returned through actual history across an album boundary. Repeat stayed in
  one album; Shuffle All visited all four eligible photos from both albums without repetition.
- ADB rotation simulation changed AUTO between portrait and landscape on the real tablet.
  Manual Landscape remained landscape on a portrait display. ALL included both shapes and squares.
- Synthetic JPEG corner markers remained visible with correct proportions and letterboxing.
  An EXIF-90 JPEG was correctly treated as portrait. Square/unknown-only selection showed a
  useful empty state; Next Album skipped the ineligible album.
- Long press opened the menu without a photo jump. Center pause held a photo for eleven seconds
  beyond testing progress, and resume retained about seven seconds rather than advancing at once.
  Manual navigation while paused reset the full ten-second interval. Rotation retained user pause.
- Seven-second entry, one-second minimum, 24-hour maximum, rejection above 24 hours, a ten-second
  preset, and duration persistence all passed through the actual Settings UI.
- Both copied Google Photos albums refreshed successfully (111 and 184 photos) and played
  correctly oriented photos. Provider parsing and the existing periodic refresh path were retained.

## Preservation and installation

The original `com.portalhacks.frame` remains version 1.2.0 (14). Its preferences SHA-256 and
cache/file sizes remained unchanged. No uninstall, data clear, original-data restore or reset
was performed. A read-only run-as archive preserved preferences, files and caches locally;
the preference hash matched before importing a copy into the isolated validation app.

The original certificate SHA-256 starts `668c6fa0`; the local development build starts `a3b17ea4`.
They differ, so no original-package update was attempted. The original Actions workflow did not
restore/save the debug keystore; a Gradle dependency cache does not preserve that signing key.
The installed app disallows Android backup. There is no in-app export in this source; debug
run-as export provided the safe copy method instead.

The local tested APK and Actions APKs may have different debug certificates. Compare certificates
before every update, including validation updates. Never uninstall the original to bypass signing.
No private configuration, album URLs, screenshots of personal photos, backup archives or keys are
included in the repository or Actions artifacts.

## Remaining limits

Physical hand-turning, long-duration soak reliability, the full twenty-minute periodic refresh
interval, and live iCloud/upload synchronization were not established by this session. The
24-hour setting was validated as a setting, not by waiting 24 hours. ADB rotation simulation
on real hardware does not establish accelerometer/firmware behavior under physical turning.
