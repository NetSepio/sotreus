# Changelog

All notable changes to Sotreus Android. Versions follow `versionName` in `app/build.gradle.kts`.

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
