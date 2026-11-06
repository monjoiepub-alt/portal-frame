# Lenovo tablet regression build: 1.2.1-tablet (15)

Historical implementation notes. See [TABLET_VALIDATION.md](TABLET_VALIDATION.md) for the
1.2.2-tablet corrections and physical Lenovo results, including display-based AUTO orientation.

Branch: `dev/tablet-playback-phase1`. Hardware verification of this new build is pending.

## Causes found and changes

- PlaybackControls read non-observable preferences in a skippable Compose child. The parent
  tick did not guarantee recomposition of that child, so labels and captured cycling handlers
  could stay on an old value. Real radio selections now observe preference changes inside the
  controls, write the existing keys and reflect persisted values. Radio and label share one
  selectable event handler. Compose tests click these actual controls and drive playback from
  the resulting preferences.
- Activity filtering, bitmap validation and controller navigation used independent orientation
  and list state. Resource metrics alone also did not detect physical turning with rotation
  locked. A stable-angle physical orientation listener (including natural-landscape displays)
  now supplies the single AUTO selection. The slideshow requests sensor rotation. Actual view
  bounds supply rendering geometry; the resolved selection is also passed to bitmap validation.
- Old cache metadata could exclude an image before its real file was inspected. Existing cached
  images and local uploads now have header/EXIF probes even when old metadata has dimensions.
  Probes are deduplicated and run off the main thread. Corrupt remote cache entries can be
  replaced; local uploaded originals are never deleted by dimension validation.
- Index-minus-one navigation could not return to the actual previous image across albums or
  shuffle. PlaybackSession now owns all selection and a bounded 200-frame display history;
  only successful presentations enter history. Selection is revalidated before every move.
  NEXT ALBUM skips empty/nonmatching/failed items, wraps and includes late-resolved photos.
- There is one monotonic slideshow timer. User pause, lifecycle suspension, loading and low light
  are independent holds. Center tap retains the current photo and remaining hold time. Manual
  navigation resets the full interval while retaining user pause. Crossfade/loading time does
  not count as settled photo hold time.

## Playback gestures

Invisible thirds apply to the current view width in portrait and landscape:

- Left tap: actual previous displayed frame (stays put if there is no eligible history).
- Center tap: pause/resume. Clock/date continue while photos are paused.
- Right tap: next eligible frame; NEXT ALBUM crosses album boundaries.
- Long press anywhere: menu for Settings & albums, clock editing, note editing or closing.
- Existing horizontal swipes remain. A handled swipe/long press cannot also become a zone tap;
  multi-touch is not a tap and rapid duplicate taps are debounced.

Clock/note dragging is explicitly entered through the menu so their invisible touch areas do
not intercept ordinary zone taps. Strict AUTO/Manual modes continue to disable crop, zoom,
pairing and Ken Burns without overwriting the user's stored effect settings. ALL allows them.
Google/iCloud configuration, foreground refresh (resume + 20 minutes), local uploads and the
Portal dream/guard remain. No license, NOTICE or copyright attribution is changed.

## Installation and signing

Use the `Tablet debug APK` Actions workflow on this development branch. Its
`Frame-tablet-fix-debug` artifact includes the APK, checksum, public signing-certificate details
and source commit. It runs the build, automated tests, Android Lint, detekt and ktlint.

Try `adb install -r Frame-tablet-fix-debug.apk`; **do not uninstall automatically**. Version
code 15 is newer than Phase 1's 14. In-place upgrades also require the same signing key.
The old workflow generated a fresh debug key on each runner, so compatibility with a previously
installed Actions APK cannot be guaranteed. Stop on `INSTALL_FAILED_UPDATE_INCOMPATIBLE` to keep
existing app data. Keep the old APK, record existing album/settings choices, and arrange the
original signing key or an explicit data-preservation plan before any uninstall. The workflow
now caches its debug keystore for subsequent builds, but a GitHub cache can expire; it is not a
substitute for a durable private signing-key secret. No keystore is committed or published.

## Physical Lenovo verification checklist

1. Confirm App info says **1.2.1-tablet**. Record current album URLs and settings before testing.
   Do not clear data. Keep Android logs for any failure.
2. In Settings, tap ALL → MANUAL → Portrait → Landscape → AUTO repeatedly. Verify the radio
   selection changes each time, survives leaving/reopening settings and survives process restart.
   Repeat for Repeat Current / Next Album / Shuffle All. Tap both labels and radio circles.
3. Use two enabled albums: A with wide A1/A2 and tall A3; B with wide B1 and tall B2, plus a square
   photo, EXIF-rotated JPEG and an empty/disabled album. Turn the tablet horizontally/vertically
   with system auto-rotate both enabled and disabled. AUTO must show only matching photos in
   either case. Manual selection must stay forced regardless of turning. ALL must allow both.
   Repeat after offline playback from cache and after returning from Settings mid-fetch.
4. Choose a 30-second interval. After 10 seconds, center tap, wait at least 40 seconds: the same
   photo must remain. Center tap again: it should hold roughly the remaining 20 seconds before
   the next transition, not immediately skip. Test with the app backgrounded while paused and
   with low-light clock mode enabled/disabled.
5. Tap left/center/right in both screen orientations, including over the displayed clock/note.
   Long press each third and verify the menu opens without a photo jump. Rapid double taps and
   multi-touch must not navigate twice. Use Settings & albums from the menu and return with Back.
6. In Repeat A, navigate through several loops: B must never appear. In Next Album, go A1 → A2 →
   B1 → A1 in landscape AUTO, skipping A3/B2/squares/empty albums. From B1, left must return to
   A2; right must return to B1. In portrait, use A3/B2 instead. In Shuffle All, record the actual
   displayed sequence, then verify Previous follows that history rather than an index or reroll.
7. While paused, manually navigate: the new photo must stay paused and get a full 30-second
   interval after resume. Switch orientation or disable/remove an album and verify Previous
   never revives an ineligible photo. Add photos remotely, wait for refresh, and check recovery
   after Wi-Fi loss. Exercise clock/date styles and a long playback session.

Robolectric/Compose tests verify software event routing and preferences, but cannot establish
Lenovo sensor/firmware behavior or long-running hardware reliability. Google shared-album
scraping remains unofficial; empty scraped responses retain offline caches as before.

## Local validation result

Debug APK build, all 33 automated tests (0 failed/skipped), Android Lint, detekt and
ktlint passed. Tests include actual Compose controls, an Android 12 Robolectric activity
rendering real EXIF-tagged JPEGs, real overlay MotionEvents, and playback/timer histories.
These are automated software checks; the new build still needs the Lenovo checklist above.
