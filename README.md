# Sotreus

Private, local-first electronic situational awareness for Android (Kotlin, Jetpack Compose).

Sotreus shows what the phone can observe over Bluetooth LE and Wi-Fi, learns what is normal for a
place, remembers what you have encountered before, and explains why something may be worth a
look — without claiming that a radio is a threat. Core awareness works with no account, no wallet
and no network. Observations, places, labels and notes stay on the phone.

## What's in V1

- **Now** — live radios as Proximity Bands (rings by 30 s average signal strength; position
  around a ring is never a direction; pinch to zoom until crowded dots separate) or a sortable list
  with freshness, stale state and Wi-Fi throttling surfaced.
- **Entities** — device-family classification, "most likely…" explanations, your labels (Mine,
  Expected, Tagged, Watch, Ignore), names, notes, addresses, raw evidence and a proximity check.
- **Attention** — explainable events with human-readable reasons and inputs. Not a threat score.
- **Places** — user-defined places with optional coordinates (current location or map pick) and a
  baseline learned from visits.
- **Sessions** — Journey and Sit sessions with a live timeline, summaries and sit comparison.
- **Privacy** — retention, coordinate masking, privacy-reduced exports, per-item deletion.
- **Friends** — QR pairing and opt-in Nearby presence using rotating anonymous BLE tokens.
- **Context** — optional sources, each labelled with where it came from and each switchable off:
  - **Satellites** (PREDICTED) — orbital elements from CelesTrak (Earth observation, weather and
    space stations), downloaded once a day; what is passing overhead now and the next 24 h of passes
    are computed on the phone with SGP4. Predictions are checked in unit tests against Skyfield.
  - **Aircraft** (NETWORK) — positions reported by OpenSky Network within a chosen radius. *Coarse
    area* asks for the 3° × 3° box around the 1° grid cell you are in and filters on the phone;
    *Exact area* asks for a box around the radius. Off by default. Polled once a minute while the
    Context tab is open and every 5 minutes during a session.
  - **Remote ID** (SENSED, beta) — ASTM F3411 drone broadcasts decoded from the phone's own
    Bluetooth (service data `FFFA`) and Wi-Fi beacon (vendor IE `FA:0B:BC`) scans: UAS ID, maker,
    position, altitude, speed, course and operator position. Nothing is sent anywhere.
  - A **3D scene** heads the tab, drawn on the phone with no map tiles: a dot-matrix Earth with
    the day/night terminator, every catalog satellite moving in real time (or as a ×60 / ×600
    time-lapse) and lines of sight to those above your horizon; switching to Aircraft or Remote ID
    flies the camera down to a tilted plate around you with range rings, coastline, altitude
    stems, courses, trails, and drone operator positions. Drag to turn, pinch to zoom, tap an
    object for its details.
  - Session summaries list context that overlapped the session in time — never as a cause.
- **Solana Mobile devices** — optional wallet sign-in (Sign In With Solana via Mobile Wallet
  Adapter) and salted-Merkle proof stamping on **devnet**. Only a 32-byte commitment goes on-chain.

## Build

Requires JDK 17+ and the Android SDK (`sdk.dir` in `local.properties` or `ANDROID_HOME`).

```
./gradlew test lint assembleGenericDebug assembleSolanaMobileDebug
```

Instrumented UI tests (emulator or device):

```
./gradlew connectedDebugAndroidTest
```

### Distributions

Both distributions use the application ID `com.sotreus.app` (the same ID is used on iOS). The
`distribution` flavors are build profiles — store and signing key — not feature sets:

| Flavor | Ships to | Signing |
|---|---|---|
| `generic` | Google Play (AAB) and a sideload APK | `signing/generic.properties` |
| `solanaMobile` | Solana dApp Store (APK) | `signing/solanaMobile.properties` |

Each `signing/<flavor>.properties` holds `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. The folder is git-ignored. Debug builds keep the debug key. A release build
fails until that flavor's properties file, or the `SOTREUS_*` env vars used in CI, is present.
The two release APKs cannot be installed side by side.

### Release signing

Create two different keys from the repo root. JDK 17+ writes a PKCS12 keystore; the `.jks` name
is only a filename. `keytool` prompts for the passwords and the certificate name. Back both files
up. Losing the Solana key blocks dApp Store updates. The generic key is the Play upload key; let
Google generate the Play app-signing key.

```bash
mkdir -p signing

keytool -genkeypair \
  -v \
  -keystore signing/sotreus-generic.jks \
  -alias sotreus-generic \
  -keyalg RSA \
  -keysize 4096 \
  -validity 25000

keytool -genkeypair \
  -v \
  -keystore signing/sotreus-solana-mobile.jks \
  -alias sotreus-solana-mobile \
  -keyalg RSA \
  -keysize 4096 \
  -validity 25000
```

`signing/generic.properties` (PKCS12 keeps one password, so `storePassword` and `keyPassword` are the same value):

```properties
storeFile=signing/sotreus-generic.jks
storePassword=REPLACE
keyAlias=sotreus-generic
keyPassword=REPLACE
```

`signing/solanaMobile.properties` uses `storeFile=signing/sotreus-solana-mobile.jks` and
`keyAlias=sotreus-solana-mobile`. Then:

```bash
./gradlew :app:bundleGenericRelease :app:assembleGenericRelease :app:assembleSolanaMobileRelease
```

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before each store upload.

Pushing a `v*` tag runs `.github/workflows/release.yml` and publishes
`Sotreus-<version>-generic.apk`, `Sotreus-<version>-solana-mobile.apk`, and
`Sotreus-<version>-generic.aab`. A manual run publishes a prerelease tagged `ci-<short sha>`.
Add Actions secrets `GENERIC_KEYSTORE_BASE64`, `GENERIC_STORE_PASSWORD`, `GENERIC_KEY_ALIAS`,
`GENERIC_KEY_PASSWORD`, `SOLANA_MOBILE_KEYSTORE_BASE64`, `SOLANA_MOBILE_STORE_PASSWORD`,
`SOLANA_MOBILE_KEY_ALIAS`, and `SOLANA_MOBILE_KEY_PASSWORD`. Encode a keystore with
`base64 -i signing/sotreus-generic.jks | tr -d '\n'`.

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Which auth and settings screens appear is decided **at runtime by the device**: Solana Mobile
hardware (Saga, Seeker) gets the wallet and Proofs screens; every other device gets the local
profile. Debug builds can force the Solana screens from Settings › Diagnostics.

## Modules

| Module | Holds |
|---|---|
| `:app` | Application, activity, navigation shell, distribution bindings |
| `:core:model` | Android-free domain types |
| `:core:navigation` | Type-safe routes shared by all features |
| `:core:designsystem` | `SotreusTheme`, design tokens, bundled fonts, icons |
| `:core:ui` | Shared components (Bands canvas, rows, chips, panels, dialogs) |
| `:core:database` | Room schema, DAOs and migrations (schemas in `core/database/schemas`) |
| `:core:data` | Observation pipeline, repositories, settings, export, session service |
| `:core:crypto` | Deterministic CBOR, salted Merkle batches, presence tokens, Base58 |
| `:core:testing` | `FakeSotreusData` sample world for previews |
| `:intelligence:fieldwatch` | Ported Fieldwatch domain logic (MIT) |
| `:intelligence:core` | Classification, baselines, attention engine, proximity, compare |
| `:sensing:android` | BLE / Wi-Fi radios (ported from Fieldwatch), permissions, location, simulator |
| `:social:presence` | Nearby presence advertising and token resolution |
| `:integration:solana` | Mobile Wallet Adapter gateway, devnet RPC, Memo transaction |
| `:context:core` | Context sources: OpenSky client, CelesTrak catalog, SGP4 pass prediction, Remote ID tracker |
| `:feature:*` | onboarding, now, entity, attention, history, place, journey, settings, friends, proofs, context |

Convention plugins live in `build-logic/convention`; versions in `gradle/libs.versions.toml`.

## References and inspiration

Sotreus builds on the work below. Licences for bundled third-party material are listed in
[`NOTICE`](NOTICE) and [`licenses/`](licenses).

### Radio awareness

- **Fieldwatch** — <https://github.com/OffGridPete/Fieldwatch> (MIT, © 2026 Off Grid Pete LLC).
  The field-tested foundation for Sotreus' radio layer. Ported with notices kept in each file:
  the BLE and Wi-Fi scanners and their scheduling around Android throttling, advertisement and
  Wi-Fi IE parsers, the device-family signature catalog and matching engine, plain-language device
  explanations, the relative-loudness proximity logic (Hunt), the live-sighting merge logic, the
  vendor/company lookup tables and Fieldwatch's own unit tests.

### Solana Mobile

- **Mobile Wallet Adapter** — <https://github.com/solana-mobile/mobile-wallet-adapter>
  ([specification](https://github.com/solana-mobile/mobile-wallet-adapter/blob/main/spec/spec.md)) —
  wallet connection, Sign In With Solana and transaction signing.
- **Solana Mobile documentation** — <https://github.com/solana-mobile/solana-mobile-docs>
- **Solana Kotlin Compose scaffold** — <https://github.com/solana-mobile/solana-kotlin-compose-scaffold>
  (reference for MWA usage in Compose).
- **dApp Store publishing** — <https://github.com/solana-mobile/dapp-publishing>
- **Sign In With Solana** — <https://github.com/phantom/sign-in-with-solana>

### Context sources

- **OpenSky Network** — <https://opensky-network.org> ([REST API](https://openskynetwork.github.io/opensky-api/rest.html),
  [GitHub](https://github.com/openskynetwork/opensky-api)) — aircraft state vectors, anonymous
  access. Matthias Schäfer et al., "Bringing up OpenSky", IPSN 2014.
- **CelesTrak** — <https://celestrak.org/NORAD/elements/> — orbital elements (GP data) by group.
- **predict4java** — <https://github.com/g4dpz/predict4java> (MIT) — SGP4/SDP4 propagation and
  pass prediction on the phone.
- **Skyfield** — <https://github.com/skyfielders/python-skyfield> — independent reference values
  for the prediction tests.
- **FAA Remote ID** — <https://www.faa.gov/uas/getting_started/remote_id> (14 CFR Part 89),
  ASTM F3411 broadcast format; **OpenDroneID** — <https://github.com/opendroneid/opendroneid-core-c>
  — the reference message layout followed by the Fieldwatch decoder Sotreus uses.

- **Natural Earth** — <https://github.com/nvkelso/natural-earth-vector> (public domain) — land and
  1:50m coastlines for the Context globe, packed by `tools/geodata/build_globe_assets.py`.

### Maps

- **MapLibre Native** — <https://github.com/maplibre/maplibre-native> — the map view for places.
- **OpenFreeMap** — <https://github.com/hyperknot/openfreemap> — free vector tiles (no API key),
  built on [OpenMapTiles](https://github.com/openmaptiles/openmaptiles). Map data ©
  [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. Tiles load only when you
  ask to see a map.

### Fonts

- **Geist and Geist Mono** — <https://github.com/vercel/geist-font> (SIL OFL 1.1)
- **Instrument Serif** — <https://github.com/Instrument/instrument-serif> (SIL OFL 1.1)

### Libraries

- **Android Jetpack** (Compose, Room, Navigation, WorkManager, DataStore) — <https://github.com/androidx/androidx>
- **Dagger / Hilt** — <https://github.com/google/dagger>
- **kotlinx.serialization** — <https://github.com/Kotlin/kotlinx.serialization>
- **Bouncy Castle** (X25519, Ed25519) — <https://github.com/bcgit/bc-java>
- **OkHttp** — <https://github.com/square/okhttp>
- **ZXing** — <https://github.com/zxing/zxing> and **ZXing Android Embedded** — <https://github.com/journeyapps/zxing-android-embedded> (friend QR codes)

### Project structure

- **Now in Android** — <https://github.com/android/nowinandroid> — the convention-plugin and
  modular feature layout follow its patterns.
