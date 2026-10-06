plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.place"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.maplibre.android)
}
