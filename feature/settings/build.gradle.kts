plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.settings"
}

dependencies {
    implementation(project(":core:crypto"))
}
