plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.journey"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(project(":core:crypto"))
}
