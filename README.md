# Sotreus

Private, local-first electronic situational awareness for Android (Kotlin, Jetpack Compose).
Core awareness works with no account, no wallet and no network.

## Build

Requires JDK 17+ and the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```
./gradlew test lint assembleGenericDebug assembleSolanaMobileDebug
```

Two flavors on the `distribution` dimension:

| Flavor | Application ID | Adds |
|---|---|---|
| `generic` | `com.sotreus.app` | optional Google / email profile |
| `solanaMobile` | `com.sotreus.app` | optional wallet sign-in, devnet proof stamping |

Both IDs are placeholders until publishing IDs are final.

## Modules

| Module | Holds |
|---|---|
| `:app` | Application, activity, nav shell, `src/generic` + `src/solanaMobile` flavor bindings |
| `:core:model` | Android-free domain types (`Distribution`, `AppCapabilities`, `Provenance`, …) |
| `:core:designsystem` | `SotreusTheme`, tokens mirrored from `design/tokens.json`, bundled fonts, icons |
| `:core:ui` | Shared composables (bottom nav, rows, panels) |
| `:core:database` | Room database, entities, DAOs; schemas exported to `core/database/schemas` |
| `:feature:now` · `:feature:history` · `:feature:journey` · `:feature:settings` | One module per tab |

Convention plugins live in `build-logic/convention`; versions in `gradle/libs.versions.toml`.
