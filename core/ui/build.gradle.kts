plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.android.compose)
}

android {
    namespace = "app.sotreus.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:model"))
}
