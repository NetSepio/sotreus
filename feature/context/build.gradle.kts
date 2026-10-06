plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.context"
}

dependencies {
    implementation(project(":context:core"))
}
