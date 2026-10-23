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
