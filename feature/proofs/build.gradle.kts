plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.proofs"
}

dependencies {
    implementation(project(":core:crypto"))
}
