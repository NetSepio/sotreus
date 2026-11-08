// Sotreus Edge protocol v1 (../sotreus-edge/docs/protocol.md): framing, the 256-byte record
// codec, command/event shapes, capabilities, time anchoring, sync rules and the location rules
// for Edge sightings (HANDOFF_V2_EDGE.md §4). Android-free so it can become Kotlin
// Multiplatform for the iOS app.
plugins {
    alias(libs.plugins.sotreus.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
