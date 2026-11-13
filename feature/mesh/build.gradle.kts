// V3: Mesh mode screens (HANDOFF_V3_MESH.md §4, mocks M1–M8): messages, nodes, map, channels and
// mesh settings for an Edge in Mesh mode.
plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.mesh"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.maplibre.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
}
