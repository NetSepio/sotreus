// Android side of the Sotreus Edge link: Bluetooth GATT transport, Companion Device Manager
// pairing and presence, and (later) the Wi-Fi transport. Protocol logic lives in :edge:protocol.
plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
}

android {
    namespace = "app.sotreus.edge.android"
}

dependencies {
    api(project(":edge:protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
}
