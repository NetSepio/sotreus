plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.onboarding"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
