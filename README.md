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
| `generic` | Google Play (AAB) | `signing/generic.properties` |
| `solanaMobile` | Solana dApp Store (APK) | `signing/solanaMobile.properties` |

Each `signing/<flavor>.properties` holds `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`; the folder is git-ignored. Until a key exists, release builds use the debug key.

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
| `:feature:*` | onboarding, now, entity, attention, history, place, journey, settings, friends, proofs |

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

### Planned context sources (V1.1, not used yet)

- OpenSky Network — <https://opensky-network.org/data/>
- CelesTrak orbital data — <https://celestrak.org/NORAD/elements/>
- FAA Remote ID — <https://www.faa.gov/uas/getting_started/remote_id>
