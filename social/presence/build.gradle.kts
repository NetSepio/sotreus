// Nearby friend presence (architecture handoff §17.3): opt-in BLE advertising of rotating tokens.
plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
}

android {
    namespace = "app.sotreus.social.presence"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:crypto"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
