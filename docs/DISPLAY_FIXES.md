# Display fixes: 1.2.3-tablet (17)

Development branch: `dev/tablet-playback-phase1`. Tested on Lenovo TB328FU,
Android 12/API 31, using `com.portalhacks.frame.validation`.

## Findings and corrections

The old ambient-light listener enabled clock-only mode immediately at or below 8 lux,
blanked the photograph, and waited for 25 lux to restore it. It was enabled by default.
This path is independent of portrait/landscape orientation. Tilting/occluding a light
sensor can therefore produce the reported behavior; previous diagnostics included
readings below the threshold. A hand-tilt reproduction was not possible remotely.

Clock-only mode is now off unless explicitly opted into. Only that option registers the
light listener; display orientation sensing remains enabled. The optional mode requires
15 seconds of sustained darkness and two seconds of bright light before changing state.
Disabling it releases the light pause and restores photographs immediately. Invalid sensor
readings fail open to photographs. A periodic check also supports on-change light sensors
that report a stable value only once. Night warmth remains a separate, unchanged tint.

The initial capture already showed a clock, but in 12-hour format; complete clock absence
was not reproduced. Digital clocks now use `HH:mm` with no AM/PM. Existing selected faces,
position and scale are preserved; new installations default to the smaller minimal face.
White text, shadow and a local translucent backing improve contrast. Date and temperature
have separate switches, and these preferences apply immediately during playback.

The minimal/custom clock faces previously hid the shared date/weather line. Weather was
also coupled to the clock switch, and US locales selected Fahrenheit. Weather is now
independent of face/clock visibility, requests Celsius explicitly and always labels `°C`.
The existing GeoJS IP-location and Open-Meteo sources remain; no key or saved location
configuration was missing. Location is approximate network-IP location, not GPS. No
coordinates, credentials or personal data are added to this repository.

Network failures produce no fabricated temperature: the UI can say Weather unavailable
while clock/photos continue. Concurrent duplicate weather requests are prevented, and
normal photo presentation no longer postpones the thirty-minute refresh indefinitely.

Sources: [GeoJS endpoint](https://www.geojs.io/docs/v1/endpoints/geo/) and
[Open-Meteo documentation](https://open-meteo.com/en/docs).

## Verification

- Both debug variants built; all 43 JVM/Robolectric/Compose tests passed.
- Android Lint: zero errors, 55 warnings. detekt and ktlint passed. Existing ktlint
  entries were repositioned for unchanged code; no additional suppressions were added.
- Real Lenovo: 24-hour clock and a live Open-Meteo Celsius reading displayed over photos.
- Clock/date/temperature changed independently without leaving playback, and persisted
  after restarting the app. Actual Settings switches were also tapped and verified.
- ADB portrait/landscape simulation and real brightness changes did not activate clock-only
  mode when disabled. Original Android brightness/rotation settings were restored.
- Explicit opt-in registered the light sensor; disabling unregistered it and kept photos.
  The measured ambient light remained about 19–25 lux, so entry into the optional black-clock
  mode was tested with automated sensor-policy tests, not forced on the physical device.

No original Frame package update, uninstall, data clear, reset, kiosk feature or main-branch
merge was performed. The validation update used `adb install -r` after certificate checking.
Its existing albums, uploads, settings and cache were backed up first. Temporary display
test preferences were restored without replacing current album/cache data or clock transforms.

The local tested APK retains its existing development signing key. Actions artifacts can
have a different debug certificate; compare certificates before any update.

Remaining limits: physical hand-tilting through the reported angles and a sustained real
below-8-lux transition still need direct hardware observation. Long-term weather outages
and the full thirty-minute refresh interval were not timed end-to-end.
