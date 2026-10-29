# Changelog

All notable changes to Sotreus Android. Versions follow `versionName` in `app/build.gradle.kts`.

## 1.1.2 — 2026-10-29

### Added

- Add a signal map to the proximity check (Fieldwatch 1.1.31–1.1.32 Hunt heat cloud, upstream
  commit `b67f207`): opt-in "Map this walk" follows steps and compass, places the walk on two
  agreeing GPS fixes, colors each spot by loudness and rings the loudest patch. It never places the
  other radio. In memory only; street tiles only with Online street maps on.
- Allow the proximity check for Wi-Fi entities, with a caveat that Android throttles Wi-Fi scans.
- Mark radios heard during a session on the route map, at this phone's position when heard. A mark
  uses the session timeline's wording.

### Fixed

- Show a session's route when only network fixes (about ±100 m) were saved, labelled as an
  approximate path, instead of "No path to display". Accurate GPS fixes are still preferred.
- Fall back to the session's saved route when live path is on but has no GPS fix yet.

### Changed

- Rewrite the welcome screen: "Perceive the signals." headline, a shorter lead, and a Memory row in
  place of the Evidence row.
- Bump Android version to 1.1.2 (code 5). No schema change.

## 1.1.1 — 2026-10-23

### Added

- Add opt-in, seven-day tracker stop history, bounded to eight stops per stable identifier and
  400 identifiers. Require accurate matched fixes and repeated samples; skip unknown/rotating BLE
  addresses, mock fixes, simulated devices and Mine/Expected/Ignore labels.
- Add calibrated GNSS health monitoring, receiver pause recovery, sensitivity controls and optional
  position, motion and network-time checks. Calibration stores receiver statistics without coordinates.
- Add live smoothed phone paths, saved-route viewing, offline path maps and separately opted-in
  OpenFreeMap street maps. Live paths remain in memory unless route saving is enabled.
- Add privacy-aware GeoJSON and ATAK Mission Package exports with phone paths, radio hear points
  and separately labelled, unverified Remote ID aircraft/operator positions.
- Add 26 partial community language packs, safe local pack import and an exportable English template.
- Add bounded signature-pack preview/import/export and bundled-catalog restore.
- Add local crash recovery diagnostics with address/URL/coordinate redaction and an explicit
  Continue action before restarting observation.

### Fixed

- Recover scanner state after Location/radio changes, isolate malformed scan records and callbacks,
  and restore an unfinished session's foreground service when the app becomes visible again.
- Wire pairing/name/Wi-Fi flood evidence into live conditions, optional burst hiding and session
  noise filtering, while preserving raw observations.
- Apply current signature classifications and expose decoded states on live and detail views.
- Preserve Remote ID operator location semantics, altitude, height reference and EU classification.
- Keep continuous sightings distinct from quiet returns even when GPS is temporarily unavailable.

### Changed

- Selectively port Fieldwatch 1.1.30 / catalog 96 (commit `ddc0d8c`), including recognition corrections
  and Polar/GoPro disambiguation. Keep Sotreus' newer vendor database, opt-in keyless prefix lookup,
  hardware measurement freshness and detection/route-saving separation.
- Bump Android version to 1.1.1 (code 4). Schema 7 adds tracker history, flood flags and Remote ID
  metadata through a non-destructive migration from schema 6.

### Validation and limits

- Passed 549 Gradle test executions, seven Python tests, lint, both debug and signed release APK
  builds, and the generic bundle. Connected Android 9 checks covered data migration, screen-off
  route-free observation, service recovery, languages, maps and spatial exports; details are in
  `RADIO_VALIDATION.md`.
- Shared movement and repeated broadcasts are evidence, not proof of a device identity, owner or
  intent. GNSS warnings require calibration and can reflect poor reception. Language coverage is
  partial. Detection accuracy, battery cost and outdoor GNSS behaviour still require field trials.

## 1.1.0 — 2026-10-23

### Added

- Add Journey movement evidence with geographic, accuracy, duration, sample and continuity checks.
- Separate active Journey location use from route storage, with Privacy controls and live availability status.
- Add opt-in manual MACLookup vendor-prefix lookups, source dates, persistent positive/negative caches,
  request cancellation, bounded responses and rate-limit handling. Private/unknown addresses stay local.
- Support Android 9 (API 28), with legacy Wi-Fi settings and foreground-service fallbacks.

### Fixed

- Reject repeated and stale Wi-Fi/BLE hardware measurements; retain their actual observation time.
- Match stored coordinates to measurement time and stop attributing observations to an expired automatic place.
- Correct Wi-Fi and BLE address confidence handling and refresh classification when payload evidence changes.
- Keep the End Journey / End Sit action visible while scrolling a busy live timeline.

### Changed

- Refresh the local radio database and add a reproducible source-hashed updater, including legacy IAB blocks.
- Database schema 6 adds the public vendor-prefix cache with a non-destructive migration from schema 5.
- Release tags must match the app version; GitHub release notes include this changelog section.

### Validation and limits

- Passed 460 Gradle tests, five Python tests, lint, both debug and signed release APK builds, and
  the Play bundle build. Tested on a Huawei Mate 9
  running Android 9, including opt-in lookup, cache reuse and screen-off BLE observations without route storage.
- Movement thresholds still need field calibration across separated locations. Address rotation breaks
  continuity, Wi-Fi scan throttling limits coverage, and vendor records do not establish an exact product or owner.

## 1.0.1 — 2026-10-09

### Fixed

- Devices discovered after reopening the app, or after moving, were tagged with the place that
  was selected earlier. The place now follows the phone's location
  ([#1](https://github.com/NetSepio/sotreus/issues/1)).
- When the app opens, the radios wait for a new location fix (up to 10 s, shown as **Locating**
  on Now) before scanning, so nothing is tagged to where the app was last used. A cached fix from
  before the app opened doesn't count.
- One-off location fixes carry the time they were taken. An old last-known fix is no longer
  stamped as current.
- Location updates use `LocationListenerCompat`, avoiding a known Android 10 crash when a
  location provider is turned off.

### Added

- **Pick places by location** (Privacy & retention, on by default): a saved place with a location
  is picked while the phone is within its radius. It is left only once the phone is clearly
  outside, so small moves and GPS jitter don't change the place. Stores no path.
- **Place radius**: choose 50, 100, 150 or 300 m on the place screen.
- A place you pick by hand stays while the phone is near where you picked it, then location takes
  over again. Now shows **Place · by location** when location picked the place.
- **Tag observations with a plus code** (Privacy & retention, off by default): each observation
  keeps a ~14 m plus code of where the phone was while Sotreus is open. Shown in raw evidence
  (masked with coordinates), exported in full or shortened to ~5.5 km in privacy-reduced exports,
  and never part of a proof.
- Sensors › Location shows what location is used for and how old the last fix is.

### Changed

- Leaving a place and coming back within 30 minutes continues the same visit.
- Database schema 5: observations get a nullable `plus_code` column (migration from 4).
- Location runs only while Sotreus is open and observing, or during a geotagged session. No
  background location.

## 1.0.0 — 2026-10-06

First release. See [What's in V1](README.md#whats-in-v1).
