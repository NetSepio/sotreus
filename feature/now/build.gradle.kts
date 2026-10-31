plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.now"
}

dependencies {
    // Area names for an unsaved place, looked up on the phone (context/core AreaNameRepository).
    implementation(project(":context:core"))
}
