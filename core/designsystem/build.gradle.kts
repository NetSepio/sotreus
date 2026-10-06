plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.android.compose)
}

android {
    namespace = "app.sotreus.core.designsystem"
}

dependencies {
    testImplementation(libs.kotlinx.serialization.json)
}
