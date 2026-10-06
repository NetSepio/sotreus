// Solana Mobile: Mobile Wallet Adapter sign-in (SIWS) and devnet Memo proof stamping.
// Only narrow values cross this boundary: a public key and a 32-byte commitment.
plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.sotreus.integration.solana"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:crypto"))
    implementation(libs.solana.mwa.clientlib)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
