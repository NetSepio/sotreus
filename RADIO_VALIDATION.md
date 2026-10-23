# Radio evidence validation

## Implemented checks

- Wi-Fi and BLE use monotonic hardware observation timestamps, not callback time. Measurements
  older than 30 seconds, in the future, or already processed for that address are rejected.
- Coordinates are matched from a short in-memory fix buffer, not the most recent stored route point.
  Route coordinates are written only when route saving is enabled. Movement summaries contain no coordinates.
- Movement evidence is scoped to an active Journey and one radio/address. It resets after scanning
  restarts, session changes, disabled detection or an observation gap over two minutes.
- At least three geographic anchors, 300 m plus both fixes' accuracy apart, 600 m overall separation,
  eight minutes, twelve samples at least ten seconds apart, and fixes within twenty seconds with
  accuracy no worse than fifty metres are required. These are initial thresholds for field calibration.
- Online results enrich a public /24 vendor prefix only. Locally assigned, random, unknown and
  anonymous addresses are not queried. A more specific local registry match is not overwritten.
- Requests are manual and opt-in. Disabling lookup blocks subsequent requests; revoking consent
  during a request prevents caching its answer. Leaving the screen cancels its ViewModel request
  when that ViewModel is cleared. HTTP calls are cancelled with the coroutine.
- Cache expiry is 30 days for a vendor and one day for a miss. HTTP failures do not overwrite a
  previously saved result. Requests are serialised with one-second spacing and Retry-After handling.
  Clearing the cache serialises with in-flight requests so their answers cannot repopulate it.

## Automated validation

Run the normal CI command:

```bash
./gradlew test lint assembleGenericDebug assembleSolanaMobileDebug
python3 -m unittest discover -s tools/lookups -p 'test_*.py'
python3 -m unittest discover -s tools/tests -p 'test_*.py'
```

Regressions cover hardware timestamps, cached observations, GPS jitter, address changes, stale or
inaccurate fixes, outages, duplicate packets, family-independent movement attention, prefix-only
requests, consent changes, cancellation, cache TTLs, concurrency, malformed/oversized replies and
rate limiting. The generated binary is loaded by the real runtime lookup in a test. Schema 6
adds the vendor cache and corrects legacy private-address confidence/vendor fields; existing evidence and user labels are retained.

## Connected phone validation — 2026-10-23

Tested the generic debug APK on a Huawei Mate 9 (MHA-AL00), Android 9 / API 28,
using real radios and location. Android 9 support includes a legacy Wi-Fi settings intent,
foreground-service compatibility and GPS/network providers without a Play services dependency.

| Check | Observed result |
|---|---|
| Install, launch, grant permissions and observe | Real BLE and Wi-Fi results; no app crash in the checked logs |
| Update installed test build without clearing data | All 1,432 earlier observations retained |
| Manual online lookup after consent | Public Wi-Fi prefix returned TP-Link Systems Inc.; source and dates visible |
| Repeat lookup | Existing persistent cache reused without changing its fetch time |
| Locally assigned Wi-Fi address | Online request unavailable; limitation explained |
| Active Journey, route saving off, screen off for 179 s | 584 BLE observations across 183 addresses; latest observation 176 s after sleep |
| Stored location after the completed 415 s Journey | Zero route points and zero observation coordinates or plus codes |
| Stationary Journey | Zero movement alerts; this is a smoke check, not a false-alert rate measurement |
| Long live timeline | End Journey remains visible while scrolling and successfully ends the session |

The phone kept the foreground service and its GPS/network requests active while asleep.
No fresh Wi-Fi observations arrived during the screen-off interval. Android reported throttling,
and the UI displayed aging results; those cached results were not counted as new measurements.
Android 9 does not expose BLE address-type metadata needed to establish a public prefix, so
unknown BLE addresses remain ineligible for online lookup. A manufacturer payload can still
identify a protocol family locally without establishing the product model or owner.

Final automated run: 460 Gradle tests, zero failures/errors; five Python tests passed;
lint and both generic/Solana debug APK builds succeeded. These are debug-build smoke tests,
not release performance or battery benchmarks. Real movement across separated locations,
address rotation and detection accuracy still need the field trials below. No physical trip
was performed during this connected-phone test. Both test Journeys were ended, online lookup
was returned to off, and the final APK was left installed with no active observation service.

Release preparation for 1.1.0 (version code 3) also passed both signed release APK builds and the
generic Play bundle build. APK signatures, distinct distribution certificates and minimum API 28
were verified. Release workflow validation accepted `v1.1.0`, rejected a mismatched tag and
extracted only the matching changelog section for GitHub release notes.

## Version 1.1.1 integration

Compared with [Fieldwatch 1.1.30 at ddc0d8c](https://github.com/OffGridPete/Fieldwatch/commit/ddc0d8cde52b7708884668c9c666c92db1c2f427),
updating the prior e71b9b2 import selectively rather than replacing Sotreus' radio/lookup pipeline.

| Priority | Recommendation implemented | Sotreus integration |
|---|---|---|
| High | Catalog and recognition corrections | Catalog 96, narrowed Flock tests, current Chipolo matching, Polar/GoPro disambiguation, richer payload decoders |
| High | Scanner recovery and callback isolation | Radio/provider broadcasts, Location off/on quiet window, malformed record/callback guards, foreground session restoration |
| Medium | Local crash recovery | Java/native exit notes where available, redaction, copy/Continue gate, no automatic upload |
| Medium | Seven-day tracker stops | Separate opt-in, public finder address or explicit UAS ID, two samples, accurate nonmock fixes, eight stops/400 identifiers, deletion and retention |
| Medium | Remote ID metadata | Operator location kind/altitude, height/reference, EU category/class, separately labelled spatial exports |
| Optional | GNSS health | One-minute calibration, no-coordinate profile, receiver pause handling, sensitivity and optional side checks, session notes |
| Optional | Route maps and smoothing | In-memory GPS path, saved-route view, offline blank map, explicit online tile consent, recenter/zoom/pan |
| Optional | GeoJSON/ATAK | Privacy choices applied to all geometries and identifiers, escaped CoT/KML, bounded saved routes |
| Optional | Language packs | 26 partial community packs, English fallback/template, bounded local imports with key/printf validation |
| Integration | Existing imported components | Pairing/name/Wi-Fi flood evidence, decoded states and signature pack management are now surfaced |

Preserved: the 23 October IEEE/Nordic vendor database, longest-prefix local lookup, manual keyless
online prefix lookup, actual hardware observation times and the distinction between detection and
route saving. Database schema 7 has an explicit 6→7 migration; no destructive migration is used
for this upgrade. Vendor asset SHA-256 remains
`5b9c66e5b64c68a6f9495670265922b1c3c4681e47411928cc460d5b93ef3d43`.

New automated regressions cover continuous travel with GPS gaps versus true quiet returns,
public/unknown/random identifiers, opt-in/excluded states, invalid/mock fixes, stop deletion,
spatial coordinate/time reduction and per-export salts, one-point KML, XML escaping and package
manifest references, signature export/import round trips and older-stock rejection, translation
placeholders, crash redaction and schema-7 preservation/defaults.

Connected Android 9 validation for 1.1.1:

| Check | Observed result |
|---|---|
| Actual schema 6→7 upgrade | All 11,621 pre-upgrade observation rows and the vendor cache retained; both tracker tables created |
| Field tools defaults | Stop history, GNSS monitoring, live path and online maps off; monitoring requires calibration |
| GNSS calibration | Start/countdown worked; no profile saved in the indoor smoke test; no GNSS warning inferred |
| French pack and English restore | Existing resource-backed screens translated; English restored; template exported locally |
| Staged recovery note (no deliberate crash) | Recovery dialog shown before observation, Continue resumes, no foreground session service started |
| Route-off Journey with screen asleep | 63 observations by the checkpoint, latest 158 s after start; zero saved route points or observation coordinates/plus codes |
| Force-stop/reopen active Journey | Foreground session service restored when visible; test Journey ended normally |
| Synthetic saved route | MapLibre rendered the offline path; explicitly enabled OpenFreeMap tiles loaded; online maps returned to off |
| Phone-generated GeoJSON and ATAK | Route and two advertised Remote ID markers; coarsened coordinates/time, salted UAS ID; ZIP/manifest/KML/CoT parsed |
| Delete synthetic session | Normal deletion flow used; temporary synthetic data and export files removed after checks |
| Location off/on | Original GPS/network providers restored, observation resumed with a fresh BLE result; no callback/runtime crash in checked logs |

Release preparation passed **549 Gradle test executions (544 unique methods)** with zero failures,
**seven Python tests**, lint, both debug APKs, both signed release APKs and the generic release
bundle. The final generic debug APK was left installed; no Journey or observation service is active.
The smoke tests do not measure detection accuracy or battery use. No physical trip or successful
outdoor GNSS calibration was performed.

A GNSS warning is a receiver condition, not proof of interference. Outdoor calibration and
controlled interference trials were not performed. Partial translations need human review.
ATAK packages are structurally checked; interoperability needs an actual ATAK client trial.
The live map displays the phone's path, not the positions of nearby radios, and can reveal that
path even while coordinate text is masked.

## Field test matrix before release

Use devices whose identity and movement are known; record ground truth separately from app results.
Do not treat simulated radios or an emulator as detection-accuracy validation.

| Scenario | Expected evidence |
|---|---|
| Carry a stable-address BLE device across three separated locations for 10+ min | Movement summary with correct sample count, duration and geographic span |
| Leave a BLE device or AP at the starting location | No continued movement from cached observations |
| Repeat a Wi-Fi scan with unchanged per-AP timestamps | No new observation or encounter |
| Use two identical products with different addresses | Separate entities |
| Let a BLE address rotate halfway through a trip | Continuity breaks; no automatic identity merge |
| Remain stationary with GPS jitter | No movement summary |
| Disable location or enter a building with poor fixes | Waiting-for-location state; no unsupported movement samples |
| Turn off route saving, then lock the screen during an active Journey | Location supports detection; session_locations and observation coordinates remain empty |
| Stop scanning or force-stop the process for more than two minutes | New evidence starts from zero; earlier summary remains historical |
| Travel with other passengers, then separate | Shared transport can produce evidence; no inference of intent |
| Disable online lookup or inspect a private BLE address | No vendor API request |
| Go offline, receive HTTP 429 or clear cache during a lookup | Graceful failure/backoff; existing local classification retained |
| Upgrade an install with schema 5 | Labels, observations and sessions preserved; cache table created |

Measure false alerts per journey-hour, missed carried devices, time to first evidence, observation
coverage, location availability and battery use. Run screen-on/off trials on multiple Android
versions and OEMs. Android scan throttling, radio visibility and private addresses limit coverage.
