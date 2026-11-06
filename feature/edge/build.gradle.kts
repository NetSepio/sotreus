// V2: Sotreus Edge pairing and device screens (HANDOFF_V2_EDGE.md §5, mocks 17 and 18).
plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.edge"
}

dependencies {
    implementation(libs.maplibre.android)
}
